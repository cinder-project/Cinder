package dev.cinder.server;

import dev.cinder.chunk.ChunkLifecycleManager;
import dev.cinder.chunk.ChunkPosition;
import dev.cinder.chunk.CinderChunk;
import dev.cinder.entity.EntityUpdatePipeline;
import dev.cinder.network.CinderDashboardServer;
import dev.cinder.network.CinderNetworkManager;
import dev.cinder.plugin.CinderEventBus;
import dev.cinder.plugin.command.CinderCommandRegistry;
import dev.cinder.plugin.loader.CinderPluginLoader;
import dev.cinder.profiling.TickProfiler;
import dev.cinder.profiling.TickProfiler.RollingStats;
import dev.cinder.world.CinderWorld;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
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

    private static final double DEGRADED_TPS_THRESHOLD       = 18.5;
    private static final double DEGRADED_MEAN_MSPT_THRESHOLD = 45.0;
    private static final double DEGRADED_P99_MSPT_THRESHOLD  = 60.0;
    private static final int    DEGRADED_WINDOWS_FOR_SHED    = 3;
    private static final int    HEALTHY_WINDOWS_FOR_RECOVER  = 6;

    public static final String PROP_TPS          = "cinder.tps";
    public static final String PROP_CACHE_SIZE   = "cinder.chunk.cacheSize";
    public static final String PROP_IO_THREADS   = "cinder.chunk.ioThreads";
    public static final String PROP_VIEW_DISTANCE = "cinder.viewDistance";
    public static final String PROP_SIMULATION_DISTANCE = "cinder.simulationDistance";
    public static final String PROP_DEFERRED_INTERVAL = "cinder.entity.deferredInterval";
    public static final String PROP_SAVE_INTERVAL_TICKS = "cinder.saveIntervalTicks";
    public static final String PROP_RUNTIME_CONFIG_PATH = "cinder.runtimeConfigPath";
    public static final String PROP_ADAPTIVE_ENABLED = "cinder.adaptive.enabled";
    public static final String PROP_ADAPTIVE_MIN_SIMULATION_DISTANCE =
        "cinder.adaptive.minSimulationDistance";
    public static final String PROP_ADAPTIVE_MAX_DEFERRED_INTERVAL =
        "cinder.adaptive.maxDeferredInterval";
    public static final String PROP_PLUGINS_DIR = "cinder.pluginsDir";
    public static final String PROP_PLUGIN_DATA_DIR = "cinder.pluginDataDir";

    public enum State { CREATED, STARTING, RUNNING, STOPPING, STOPPED }

    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);

    private final CinderScheduler        scheduler;
    private final EntityUpdatePipeline   entityPipeline;
    private final ChunkLifecycleManager  chunkManager;
    private final CinderWorld            world;
    private final TickProfiler           profiler;
    private final CinderTickLoop         tickLoop;
    private final CinderWatchdogNotifier watchdog;
    private final CinderNetworkManager   networkManager;
    private final CinderDashboardServer  dashboardServer;
    private final CinderEventBus         eventBus;
    private final CinderCommandRegistry  commandRegistry;
    private final CinderPluginLoader     pluginLoader;
    private final String                 presetName;
    private final String                 logDir;
    private final String                 runtimeConfigPath;
    private final boolean                adaptiveEnabled;
    private final int                    adaptiveMinSimulationDistance;
    private final int                    adaptiveMaxDeferredInterval;
    private final Instant                startInstant = Instant.now();
    private final long                   gcEventsAtStartup;

    private volatile RuntimeTargets baseTargets;
    private volatile RuntimeTargets activeTargets;
    private volatile int peakPlayerCount = 0;
    private volatile int degradedWindows = 0;
    private volatile int healthyWindows = 0;
    private volatile long runtimeConfigLastModifiedMs = -1L;

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
        int viewDistance = intProperty(PROP_VIEW_DISTANCE, 8);
        int simulationDistance = intProperty(PROP_SIMULATION_DISTANCE, viewDistance);
        int deferredInterval = intProperty(PROP_DEFERRED_INTERVAL, 4);
        int saveIntervalTicks = intProperty(
            PROP_SAVE_INTERVAL_TICKS,
            ChunkLifecycleManager.DEFAULT_SAVE_INTERVAL_TICKS);

        this.baseTargets = RuntimeTargets.of(
            cacheSize,
            deferredInterval,
            Math.min(viewDistance, simulationDistance),
            saveIntervalTicks
        );
        this.activeTargets = baseTargets;

        this.scheduler      = CinderScheduler.createDefault();
        this.eventBus       = new CinderEventBus(scheduler);
        this.commandRegistry = new CinderCommandRegistry(scheduler);
        this.entityPipeline = EntityUpdatePipeline.create();
        this.chunkManager   = new ChunkLifecycleManager(scheduler, storage, cacheSize, ioThreads);
        this.world          = new CinderWorld("world", chunkManager, entityPipeline, scheduler);
        this.profiler       = TickProfiler.createDefault();
        this.watchdog       = new CinderWatchdogNotifier();

        Path pluginsDir = Paths.get(System.getProperty(PROP_PLUGINS_DIR, "plugins"));
        Path pluginDataDir = Paths.get(System.getProperty(PROP_PLUGIN_DATA_DIR, "plugin-data"));
        this.pluginLoader = new CinderPluginLoader(
            scheduler,
            eventBus,
            commandRegistry,
            pluginsDir,
            pluginDataDir
        );

        this.chunkManager.setSaveIntervalTicks(baseTargets.saveIntervalTicks());
        this.entityPipeline.setDeferredTickInterval(baseTargets.deferredIntervalTicks());
        this.world.setSimulationDistance(baseTargets.simulationDistance());

        this.tickLoop = new CinderTickLoop(tps, entityPipeline, chunkManager, profiler, scheduler);

        int bindPort     = intProperty("cinder.network.port", 25565);
        String bindHost  = System.getProperty("cinder.network.host", "0.0.0.0");

        this.networkManager = new CinderNetworkManager(
                scheduler, bindHost, bindPort, entityPipeline, chunkManager,
                baseTargets.simulationDistance());
        this.tickLoop.setWorld(world);
        this.tickLoop.setPluginEventBus(eventBus);
        this.tickLoop.setNetworkManager(networkManager);

        try {
            this.dashboardServer = CinderDashboardServer.createDefault(
                this::getStats,
                networkManager::getConnectionCount);
        } catch (IOException e) {
            throw new RuntimeException("[CinderServer] Failed to initialise dashboard server", e);
        }

        this.presetName = System.getProperty("cinder.preset", "unknown");
        this.logDir = System.getProperty("cinder.logDir", "logs");
        this.runtimeConfigPath = System.getProperty(PROP_RUNTIME_CONFIG_PATH, "");
        this.adaptiveEnabled = boolProperty(PROP_ADAPTIVE_ENABLED, true);
        this.adaptiveMinSimulationDistance = intProperty(
            PROP_ADAPTIVE_MIN_SIMULATION_DISTANCE, 4);
        this.adaptiveMaxDeferredInterval = intProperty(
            PROP_ADAPTIVE_MAX_DEFERRED_INTERVAL, 8);
        this.gcEventsAtStartup = getGcEventCount();

        LOG.info(String.format(
            "[CinderServer] Constructed — tps=%d cacheSize=%d ioThreads=%d bind=%s:%d " +
            "simulationDistance=%d deferredInterval=%d saveInterval=%d adaptive=%s",
            tps, cacheSize, ioThreads, bindHost, bindPort,
            baseTargets.simulationDistance(),
            baseTargets.deferredIntervalTicks(),
            baseTargets.saveIntervalTicks(),
            adaptiveEnabled));
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

        try {
            networkManager.start();
        } catch (java.io.IOException e) {
            throw new RuntimeException("[CinderServer] Failed to start network manager", e);
        }

        dashboardServer.start();

        pluginLoader.loadAll();

        state.set(State.RUNNING);
        watchdog.notifyReady();
        pollAndApplyRuntimeConfig(true);
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

        dashboardServer.stop();
        networkManager.stop();
        pluginLoader.shutdown();
        chunkManager.shutdown();
        entityPipeline.shutdown();
        scheduler.shutdown();
        writeSessionReport();

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
                int players = networkManager.getConnectionCount();
                peakPlayerCount = Math.max(peakPlayerCount, players);

                String status = String.format("TPS=%.1f MSPT=%.2fms p99=%.2fms ticks=%d players=%d",
                    stats.tps, stats.meanMspt, stats.p99Mspt, profiler.getTotalTicks(), players);
                watchdog.notifyStatus(status);
                watchdog.ping();

                pollAndApplyRuntimeConfig(false);
                if (adaptiveEnabled) {
                    evaluateAdaptiveGovernor(stats);
                }

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

    private void pollAndApplyRuntimeConfig(boolean force) {
        if (runtimeConfigPath == null || runtimeConfigPath.isBlank()) {
            return;
        }

        Path path = Paths.get(runtimeConfigPath);
        if (!Files.exists(path)) {
            return;
        }

        try {
            long modifiedMs = Files.getLastModifiedTime(path).toMillis();
            if (!force && modifiedMs == runtimeConfigLastModifiedMs) {
                return;
            }

            RuntimeTargets loadedTargets = loadRuntimeTargets(path, baseTargets);
            runtimeConfigLastModifiedMs = modifiedMs;

            if (!force && loadedTargets.equals(baseTargets)) {
                return;
            }

            baseTargets = loadedTargets;
            activeTargets = loadedTargets;
            degradedWindows = 0;
            healthyWindows = 0;

            applyRuntimeTargets(loadedTargets, "runtime-hot-swap");
            LOG.info("[CinderServer] Applied runtime config from " + path + ": " + loadedTargets);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[CinderServer] Failed to read runtime config: " + path, e);
        }
    }

    private RuntimeTargets loadRuntimeTargets(Path path, RuntimeTargets defaults) throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        }

        int cacheSize = intProperty(props, PROP_CACHE_SIZE, defaults.chunkCacheSize());
        int deferredInterval = intProperty(props, PROP_DEFERRED_INTERVAL, defaults.deferredIntervalTicks());
        int viewDistance = intProperty(props, PROP_VIEW_DISTANCE, defaults.simulationDistance());
        int simulationDistance = intProperty(props, PROP_SIMULATION_DISTANCE, viewDistance);
        int saveInterval = intProperty(props, PROP_SAVE_INTERVAL_TICKS, defaults.saveIntervalTicks());

        return RuntimeTargets.of(
            cacheSize,
            deferredInterval,
            Math.min(viewDistance, simulationDistance),
            saveInterval
        );
    }

    private void applyRuntimeTargets(RuntimeTargets targets, String reason) {
        networkManager.updateViewDistance(targets.simulationDistance());
        scheduler.submitSync("runtime-targets:" + reason, () -> {
            chunkManager.updateCacheCapacity(targets.chunkCacheSize());
            chunkManager.setSaveIntervalTicks(targets.saveIntervalTicks());
            entityPipeline.setDeferredTickInterval(targets.deferredIntervalTicks());
            world.setSimulationDistance(targets.simulationDistance());
        });
    }

    private void evaluateAdaptiveGovernor(RollingStats stats) {
        boolean degraded = stats.tps < DEGRADED_TPS_THRESHOLD
            || stats.meanMspt > DEGRADED_MEAN_MSPT_THRESHOLD
            || stats.p99Mspt > DEGRADED_P99_MSPT_THRESHOLD;

        if (degraded) {
            degradedWindows++;
            healthyWindows = 0;
        } else {
            healthyWindows++;
            degradedWindows = 0;
        }

        if (degraded && degradedWindows >= DEGRADED_WINDOWS_FOR_SHED) {
            degradedWindows = 0;
            shedLoad(stats);
            return;
        }

        if (!degraded && healthyWindows >= HEALTHY_WINDOWS_FOR_RECOVER) {
            healthyWindows = 0;
            recoverLoad(stats);
        }
    }

    private void shedLoad(RollingStats stats) {
        RuntimeTargets current = activeTargets;
        RuntimeTargets next = current;

        if (current.simulationDistance() > adaptiveMinSimulationDistance) {
            next = current.withSimulationDistance(current.simulationDistance() - 1);
        } else if (current.deferredIntervalTicks() < adaptiveMaxDeferredInterval) {
            next = current.withDeferredInterval(current.deferredIntervalTicks() + 1);
        }

        if (next.equals(current)) {
            return;
        }

        activeTargets = next;
        applyRuntimeTargets(next, "adaptive-shed");

        LOG.warning(String.format(Locale.ROOT,
            "[CinderServer] Adaptive governor shed load: simDistance=%d deferredInterval=%d " +
            "(TPS=%.2f mean=%.2fms p99=%.2fms)",
            next.simulationDistance(),
            next.deferredIntervalTicks(),
            stats.tps,
            stats.meanMspt,
            stats.p99Mspt));
    }

    private void recoverLoad(RollingStats stats) {
        RuntimeTargets baseline = baseTargets;
        RuntimeTargets current = activeTargets;

        if (current.equals(baseline)) {
            return;
        }

        RuntimeTargets next = current;
        if (current.deferredIntervalTicks() > baseline.deferredIntervalTicks()) {
            next = current.withDeferredInterval(current.deferredIntervalTicks() - 1);
        } else if (current.simulationDistance() < baseline.simulationDistance()) {
            next = current.withSimulationDistance(current.simulationDistance() + 1);
        }

        if (next.equals(current)) {
            return;
        }

        activeTargets = next;
        applyRuntimeTargets(next, "adaptive-recover");

        LOG.info(String.format(Locale.ROOT,
            "[CinderServer] Adaptive governor recovered load: simDistance=%d deferredInterval=%d " +
            "(TPS=%.2f mean=%.2fms p99=%.2fms)",
            next.simulationDistance(),
            next.deferredIntervalTicks(),
            stats.tps,
            stats.meanMspt,
            stats.p99Mspt));
    }

    private void writeSessionReport() {
        try {
            Path logsPath = Paths.get(logDir);
            Files.createDirectories(logsPath);

            Instant endInstant = Instant.now();
            RollingStats stats = profiler.computeStats();
            long totalTicks = profiler.getTotalTicks();
            long gcEvents = Math.max(0L, getGcEventCount() - gcEventsAtStartup);
            double durationSeconds = Duration.between(startInstant, endInstant).toMillis() / 1000.0;

            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(endInstant);
            Path reportPath = logsPath.resolve("session-" + stamp + ".json");

            String report = formatSessionReport(
                endInstant,
                durationSeconds,
                stats,
                totalTicks,
                gcEvents
            );

            Files.writeString(reportPath, report, StandardCharsets.UTF_8);
            LOG.info("[CinderServer] Session report written: " + reportPath);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[CinderServer] Failed to write session report.", e);
        }
    }

    private String formatSessionReport(
            Instant endInstant,
            double durationSeconds,
            RollingStats stats,
            long totalTicks,
            long gcEvents
    ) {
        return String.format(Locale.ROOT,
            "{%n" +
            "  \"preset\": \"%s\",%n" +
            "  \"startedAtUtc\": \"%s\",%n" +
            "  \"endedAtUtc\": \"%s\",%n" +
            "  \"durationSeconds\": %.3f,%n" +
            "  \"meanMspt\": %.3f,%n" +
            "  \"p99Mspt\": %.3f,%n" +
            "  \"peakPlayers\": %d,%n" +
            "  \"totalTicks\": %d,%n" +
            "  \"gcEventCount\": %d%n" +
            "}%n",
            jsonEscape(presetName),
            startInstant,
            endInstant,
            durationSeconds,
            stats.meanMspt,
            stats.p99Mspt,
            peakPlayerCount,
            totalTicks,
            gcEvents
        );
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

    private record RuntimeTargets(
        int chunkCacheSize,
        int deferredIntervalTicks,
        int simulationDistance,
        int saveIntervalTicks
    ) {
        static RuntimeTargets of(
                int chunkCacheSize,
                int deferredIntervalTicks,
                int simulationDistance,
                int saveIntervalTicks
        ) {
            int cache = Math.max(16, chunkCacheSize);
            int deferred = Math.max(1, deferredIntervalTicks);
            int simulation = Math.max(2, Math.min(simulationDistance, 32));
            int saveInterval = Math.max(0, saveIntervalTicks);
            return new RuntimeTargets(cache, deferred, simulation, saveInterval);
        }

        RuntimeTargets withDeferredInterval(int intervalTicks) {
            return of(chunkCacheSize, intervalTicks, simulationDistance, saveIntervalTicks);
        }

        RuntimeTargets withSimulationDistance(int newSimulationDistance) {
            return of(chunkCacheSize, deferredIntervalTicks, newSimulationDistance, saveIntervalTicks);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static boolean boolProperty(String key, boolean defaultValue) {
        String val = System.getProperty(key);
        if (val == null) return defaultValue;

        if ("true".equalsIgnoreCase(val) || "1".equals(val) || "yes".equalsIgnoreCase(val)) {
            return true;
        }
        if ("false".equalsIgnoreCase(val) || "0".equals(val) || "no".equalsIgnoreCase(val)) {
            return false;
        }

        LOG.warning("[CinderServer] Invalid boolean for " + key + "='" + val
            + "', using default=" + defaultValue);
        return defaultValue;
    }

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

    private static int intProperty(Properties props, String key, int defaultValue) {
        String val = props.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            LOG.warning("[CinderServer] Invalid runtime config value for " + key + "='" + val
                + "', using default=" + defaultValue);
            return defaultValue;
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long getGcEventCount() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0) {
                total += count;
            }
        }
        return total;
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
