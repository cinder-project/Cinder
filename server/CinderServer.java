package dev.cinder.server;

import dev.cinder.chunk.ChunkLifecycleManager;
import dev.cinder.chunk.ChunkPosition;
import dev.cinder.chunk.CinderChunk;
import dev.cinder.entity.EntityUpdatePipeline;
import dev.cinder.profiling.TickProfiler;
import dev.cinder.profiling.TickProfiler.RollingStats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CinderServer — top-level wiring and lifecycle manager for Cinder Core.
 *
 * Responsibilities:
 *   - Construct and wire all subsystems (tick loop, scheduler, entity pipeline,
 *     chunk manager, profiler, watchdog notifier)
 *   - Own the tick thread and manage its lifecycle
 *   - Drive startup sequencing: validate → initialise → start → steady state
 *   - Drive shutdown sequencing: request stop → drain → save → notify → exit
 *   - Register a JVM shutdown hook for clean exits on SIGTERM
 *   - Emit periodic status updates to systemd via CinderWatchdogNotifier
 *
 * Startup sequence:
 *   1. Construct all subsystems
 *   2. Pre-load spawn chunks into ChunkLifecycleManager
 *   3. Start tick thread
 *   4. Wait for first tick to complete (steady state entry)
 *   5. Send READY=1 via watchdog notifier
 *   6. Begin periodic status reporting
 *
 * Shutdown sequence:
 *   1. Send STOPPING=1
 *   2. Request tick loop stop
 *   3. Join tick thread (up to SHUTDOWN_TIMEOUT_SECONDS)
 *   4. Flush remaining scheduler sync queue
 *   5. Shutdown chunk manager (saves all dirty chunks)
 *   6. Shutdown entity pipeline (drains async workers)
 *   7. Shutdown scheduler
 *
 * Configuration:
 *   CinderServer reads its runtime parameters from system properties set by
 *   launch.sh and the active preset. See property constants below.
 *
 * Thread safety:
 *   CinderServer itself is not thread-safe after start(). All post-start
 *   interaction with world state must go through CinderScheduler.submitSync().
 *   The only safe external calls after start() are shutdown() and getStats().
 */
public final class CinderServer {

    private static final Logger LOG = Logger.getLogger("cinder.server");

    private static final int    SHUTDOWN_TIMEOUT_SECONDS = 90;
    private static final int    STATUS_INTERVAL_SECONDS  = 10;
    private static final int    SPAWN_PRELOAD_RADIUS      = 2;

    public static final String PROP_TPS          = "cinder.tps";
    public static final String PROP_CACHE_SIZE   = "cinder.chunk.cacheSize";
    public static final String PROP_IO_THREADS   = "cinder.chunk.ioThreads";

    public enum State { CREATED, STARTING, RUNNING, STOPPING, STOPPED }

    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

    private final CinderScheduler        scheduler;
    private final EntityUpdatePipeline   entityPipeline;
    private final ChunkLifecycleManager  chunkManager;
    private final TickProfiler           profiler;
    private final CinderTickLoop         tickLoop;
    private final CinderWatchdogNotifier watchdog;

    private Thread tickThread;

    private final ScheduledExecutorService statusReporter = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cinder-status");
        t.setDaemon(true);
        return t;
    });

    // ── Constructor ───────────────────────────────────────────────────────

    public CinderServer(ChunkLifecycleManager.ChunkStorage storage) {
        int tps       = intProperty(PROP_TPS, 20);
        int cacheSize = intProperty(PROP_CACHE_SIZE, ChunkLifecycleManager.DEFAULT_CACHE_CAPACITY);
        int ioThreads = intProperty(PROP_IO_THREADS, 1);

        this.scheduler      = CinderScheduler.createDefault();
        this.entityPipeline = EntityUpdatePipeline.create();
        this.chunkManager   = new ChunkLifecycleManager(scheduler, storage, cacheSize, ioThreads);
        this.profiler       = TickProfiler.createDefault();
        this.watchdog       = new CinderWatchdogNotifier();

        this.tickLoop = new CinderTickLoop(tps, entityPipeline, chunkManager, profiler, scheduler);

        LOG.info(String.format(
            "[CinderServer] Constructed — tps=%d cacheSize=%d ioThreads=%d",
            tps, cacheSize, ioThreads));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Starts the server. Blocks until the tick loop has completed its first
     * tick and the server is in steady state, then returns.
     *
     * @throws IllegalStateException if already started
     * @throws RuntimeException      if startup fails or the tick thread cannot start
     */
    public void start() {
        if (!state.compareAndSet(State.CREATED, State.STARTING)) {
            throw new IllegalStateException("CinderServer is not in CREATED state.");
        }

        LOG.info("[CinderServer] Starting...");

        registerShutdownHook();
        preloadSpawnChunks();
        startTickThread();
        waitForSteadyState();

        state.set(State.RUNNING);
        watchdog.notifyReady();
        startStatusReporter();

        LOG.info("[CinderServer] Started. Tick loop is in steady state.");
    }

    /**
     * Initiates a clean shutdown. Blocks until all subsystems have stopped
     * or the timeout is reached.
     *
     * Safe to call from any thread, including the JVM shutdown hook.
     */
    public void shutdown() {
        if (!state.compareAndSet(State.RUNNING, State.STOPPING)
                && !state.compareAndSet(State.STARTING, State.STOPPING)) {
            return;
        }

        LOG.info("[CinderServer] Shutdown initiated...");

        watchdog.notifyStopping();
        statusReporter.shutdownNow();

        tickLoop.requestStop();

        if (tickThread != null) {
            try {
                tickThread.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_TIMEOUT_SECONDS));
                if (tickThread.isAlive()) {
                    LOG.warning("[CinderServer] Tick thread did not stop within "
                        + SHUTDOWN_TIMEOUT_SECONDS + "s — forcing interrupt.");
                    tickThread.interrupt();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warning("[CinderServer] Interrupted while waiting for tick thread.");
            }
        }

        LOG.info("[CinderServer] Tick loop stopped after " + tickLoop.getTickCount() + " ticks.");

        chunkManager.shutdown();
        entityPipeline.shutdown();
        scheduler.shutdown();

        state.set(State.STOPPED);
        LOG.info("[CinderServer] Shutdown complete.");
    }

    // ── Startup helpers ───────────────────────────────────────────────────

    private void preloadSpawnChunks() {
        LOG.info("[CinderServer] Pre-loading spawn chunks (radius=" + SPAWN_PRELOAD_RADIUS + ")...");
        ChunkPosition spawn = ChunkPosition.of(0, 0);
        int loaded = 0;
        for (int dx = -SPAWN_PRELOAD_RADIUS; dx <= SPAWN_PRELOAD_RADIUS; dx++) {
            for (int dz = -SPAWN_PRELOAD_RADIUS; dz <= SPAWN_PRELOAD_RADIUS; dz++) {
                chunkManager.requestLoad(spawn.offset(dx, dz));
                loaded++;
            }
        }
        LOG.info("[CinderServer] Queued " + loaded + " spawn chunk loads.");
    }

    private void startTickThread() {
        tickThread = new Thread(tickLoop, "cinder-tick");
        tickThread.setPriority(Thread.MAX_PRIORITY - 1);
        tickThread.setDaemon(false);
        tickThread.setUncaughtExceptionHandler((t, e) -> {
            LOG.log(Level.SEVERE, "[CinderServer] Tick thread died with uncaught exception.", e);
            shutdown();
        });
        tickThread.start();
        LOG.info("[CinderServer] Tick thread started.");
    }

    private void waitForSteadyState() {
        LOG.info("[CinderServer] Waiting for first tick...");
        long deadline = System.currentTimeMillis() + 10_000L;

        while (tickLoop.getTickCount() == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException(
                    "[CinderServer] Timed out waiting for first tick after 10s.");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for first tick.", e);
            }
        }

        LOG.info("[CinderServer] First tick completed (tick=" + tickLoop.getTickCount() + ").");
    }

    private void startStatusReporter() {
        statusReporter.scheduleAtFixedRate(() -> {
            try {
                RollingStats stats = profiler.computeStats();
                String status = String.format("TPS=%.1f MSPT=%.2fms p99=%.2fms ticks=%d",
                    stats.tps, stats.meanMspt, stats.p99Mspt, profiler.getTotalTicks());
                watchdog.notifyStatus(status);
                watchdog.ping();

                if (stats.isTpsDegraded()) {
                    LOG.warning("[CinderServer] TPS degraded: " + stats.toStatusLine());
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "[CinderServer] Status reporter error.", e);
            }
        }, STATUS_INTERVAL_SECONDS, STATUS_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (state.get() == State.RUNNING || state.get() == State.STARTING) {
                LOG.info("[CinderServer] JVM shutdown hook triggered.");
                shutdown();
            }
        }, "cinder-shutdown-hook"));
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public State getState() {
        return state.get();
    }

    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    public TickProfiler getProfiler() {
        return profiler;
    }

    public CinderScheduler getScheduler() {
        return scheduler;
    }

    public ChunkLifecycleManager getChunkManager() {
        return chunkManager;
    }

    public EntityUpdatePipeline getEntityPipeline() {
        return entityPipeline;
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    public ServerStats getStats() {
        RollingStats rolling = profiler.computeStats();
        return new ServerStats(
            state.get(),
            tickLoop.getTickCount(),
            rolling,
            chunkManager.getStats(),
            entityPipeline.getStats(),
            scheduler.getStats()
        );
    }

    public record ServerStats(
        State                                    serverState,
        long                                     totalTicks,
        RollingStats                             rolling,
        ChunkLifecycleManager.ChunkManagerStats  chunkStats,
        EntityUpdatePipeline.EntityPipelineStats entityStats,
        CinderScheduler.SchedulerStats           schedulerStats
    ) {
        @Override
        public String toString() {
            return "ServerStats{state=" + serverState
                + ", ticks=" + totalTicks
                + ", " + rolling.toStatusLine().replace('\n', ' ')
                + ", " + chunkStats
                + "}";
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static int intProperty(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            LOG.warning("[CinderServer] Invalid value for " + key + "='" + val
                + "', using default=" + defaultValue);
            return defaultValue;
        }
    }

    // ── Entry point ───────────────────────────────────────────────────────

    /**
     * Main entry point. Expects a ChunkStorage implementation to be wired
     * externally once the storage layer is implemented.
     *
     * During Phase 1, a no-op in-memory storage is used for smoke testing.
     */
    public static void main(String[] args) {
        LOG.info("[CinderServer] Cinder starting.");

        ChunkLifecycleManager.ChunkStorage devStorage = new DevNullStorage();
        CinderServer server = new CinderServer(devStorage);

        try {
            server.start();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "[CinderServer] Startup failed.", e);
            System.exit(1);
        }
    }

    /**
     * No-op in-memory ChunkStorage for development and smoke testing.
     * Chunks generated here are never persisted to disk.
     */
    private static final class DevNullStorage implements ChunkLifecycleManager.ChunkStorage {
        @Override
        public CinderChunk load(ChunkPosition pos) {
            return null;
        }

        @Override
        public void saveAsync(ChunkPosition pos, byte[] snapshot) {
        }

        @Override
        public void saveSync(ChunkPosition pos, CinderChunk chunk) {
        }
    }
}
