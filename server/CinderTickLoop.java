package dev.cinder.server;

import dev.cinder.chunk.ChunkLifecycleManager;
import dev.cinder.entity.EntityUpdatePipeline;
import dev.cinder.profiling.TickProfiler;
import dev.cinder.profiling.TickSnapshot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CinderTickLoop — the primary world tick loop for Cinder Runtime.
 *
 * This is the heart of Cinder's server engine. It drives the server at a
 * configurable target TPS (default: 20), measures per-tick MSPT (milliseconds
 * per tick), and dispatches phase-ordered work to the entity pipeline and
 * chunk lifecycle manager.
 *
 * Design decisions:
 *
 *   - Uses LockSupport.parkNanos() for sub-millisecond sleep precision,
 *     avoiding the 1-2ms floor of Thread.sleep() on Linux/ARM64.
 *
 *   - Tick phases are executed in a fixed order (pre → world → entity →
 *     chunk → post) to preserve causal consistency across systems.
 *
 *   - Tick overrun detection: if a tick exceeds the MSPT budget, Cinder
 *     logs a warning and skips the idle sleep for that tick to avoid
 *     compounding lag.
 *
 *   - Profiling hooks are embedded at phase boundaries so that TickProfiler
 *     can capture per-phase MSPT without branching in the hot path.
 *
 *   - The loop runs on a dedicated thread. All inter-system communication
 *     must go through the async task queue (CinderScheduler), not by
 *     calling into the tick thread directly.
 *
 * Thread safety:
 *   - The tick loop thread is the single owner of world state.
 *   - External systems (networking, monitoring) must not mutate world state
 *     directly. They post tasks via CinderScheduler.submitSync().
 *
 * ARM64 / Pi 4 notes:
 *   - On Raspberry Pi 4, parkNanos accuracy degrades under thermal throttling.
 *     Cinder compensates with drift correction: if a tick runs late, the next
 *     tick's sleep is shortened by the overrun duration.
 *   - CPU affinity should be pinned to a performance core if available.
 *     This is handled at the OS level by Cinder Runtime's launch scripts.
 */
public final class CinderTickLoop implements Runnable {

    private static final Logger LOG = Logger.getLogger("cinder.tickloop");

    /** Target ticks per second. 20 is the Minecraft standard. */
    public static final int DEFAULT_TPS = 20;

    /** Nanoseconds per tick at 20 TPS. */
    private static final long NS_PER_TICK_20TPS = 1_000_000_000L / DEFAULT_TPS;  // 50,000,000 ns

    /**
     * MSPT threshold above which Cinder logs a tick overrun warning.
     * At 20 TPS, the budget is 50 ms per tick. We warn at 45 ms
     * to give operators early visibility before TPS actually drops.
     */
    private static final long WARN_THRESHOLD_NS = 45_000_000L;

    /**
     * If a single tick exceeds this threshold, it is recorded as a
     * "severe lag spike" in the profiler. This feeds into Cinder Bench
     * metrics and the control node dashboard.
     */
    private static final long SPIKE_THRESHOLD_NS = 100_000_000L;  // 100 ms

    // ── State ──────────────────────────────────────────────────────────────

    private final AtomicBoolean running   = new AtomicBoolean(false);
    private final AtomicLong    tickCount = new AtomicLong(0L);

    /** Nanoseconds per tick, derived from the configured TPS. */
    private final long nsPerTick;

    /** Configured TPS for this loop instance. */
    private final int targetTps;

    // ── Dependencies ───────────────────────────────────────────────────────

    private final EntityUpdatePipeline   entityPipeline;
    private final ChunkLifecycleManager  chunkManager;
    private final TickProfiler           profiler;
    private final CinderScheduler        scheduler;

    // ── Constructor ────────────────────────────────────────────────────────

    public CinderTickLoop(
            int targetTps,
            EntityUpdatePipeline entityPipeline,
            ChunkLifecycleManager chunkManager,
            TickProfiler profiler,
            CinderScheduler scheduler
    ) {
        if (targetTps < 1 || targetTps > 100) {
            throw new IllegalArgumentException(
                "Target TPS must be between 1 and 100, got: " + targetTps);
        }
        this.targetTps      = targetTps;
        this.nsPerTick      = 1_000_000_000L / targetTps;
        this.entityPipeline = entityPipeline;
        this.chunkManager   = chunkManager;
        this.profiler       = profiler;
        this.scheduler      = scheduler;
    }

    /** Convenience constructor using the default 20 TPS. */
    public CinderTickLoop(
            EntityUpdatePipeline entityPipeline,
            ChunkLifecycleManager chunkManager,
            TickProfiler profiler,
            CinderScheduler scheduler
    ) {
        this(DEFAULT_TPS, entityPipeline, chunkManager, profiler, scheduler);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Starts the tick loop. This method is intended to be passed to a
     * dedicated Thread. The caller is responsible for thread naming and
     * CPU affinity (see Cinder Runtime launch scripts).
     *
     * Example:
     *   Thread t = new Thread(tickLoop, "cinder-tick");
     *   t.setPriority(Thread.MAX_PRIORITY);
     *   t.start();
     */
    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("CinderTickLoop is already running.");
        }

        LOG.info(String.format(
            "[CinderTickLoop] Starting at %d TPS (%,d ns/tick)", targetTps, nsPerTick));

        // Track accumulated drift so we can compensate in subsequent ticks.
        // Positive drift = we're running behind schedule.
        long driftNs = 0L;

        long tickStart;
        long tickEnd;
        long elapsed;
        long sleepNs;

        while (running.get()) {
            tickStart = System.nanoTime();

            try {
                runTick(tickCount.incrementAndGet());
            } catch (Exception e) {
                // A single bad tick must not crash the server. Log and continue.
                // Severe or repeated exceptions should be caught by the watchdog.
                LOG.log(Level.SEVERE, "[CinderTickLoop] Uncaught exception during tick "
                        + tickCount.get(), e);
            }

            tickEnd = System.nanoTime();
            elapsed = tickEnd - tickStart;

            // Record tick metrics into the profiler ring buffer.
            profiler.recordTick(tickCount.get(), elapsed);

            if (elapsed > SPIKE_THRESHOLD_NS) {
                LOG.warning(String.format(
                    "[CinderTickLoop] LAG SPIKE on tick %d — %.2f ms (budget: %d ms)",
                    tickCount.get(), elapsed / 1_000_000.0, nsPerTick / 1_000_000));
            } else if (elapsed > WARN_THRESHOLD_NS) {
                LOG.fine(String.format(
                    "[CinderTickLoop] Tick %d ran long: %.2f ms",
                    tickCount.get(), elapsed / 1_000_000.0));
            }

            // Compute how long to sleep, accounting for accumulated drift.
            // If the tick overran its budget, sleepNs will be 0 or negative;
            // we skip the park and add the overrun to next tick's drift.
            sleepNs = nsPerTick - elapsed - driftNs;

            if (sleepNs > 0) {
                long sleepStart = System.nanoTime();
                LockSupport.parkNanos(sleepNs);
                long actualSleep = System.nanoTime() - sleepStart;

                // Recalculate drift: positive = we slept longer than intended.
                driftNs = actualSleep - sleepNs;
            } else {
                // Tick overran. Carry forward the deficit as positive drift.
                driftNs = -sleepNs;  // sleepNs is negative; invert to positive deficit
            }
        }

        LOG.info("[CinderTickLoop] Loop stopped after " + tickCount.get() + " ticks.");
    }

    /**
     * Signals the tick loop to stop after completing the current tick.
     * This is the only safe way to stop the loop from an external thread.
     */
    public void requestStop() {
        LOG.info("[CinderTickLoop] Stop requested.");
        running.set(false);
    }

    /** Returns true if the loop is currently running. */
    public boolean isRunning() {
        return running.get();
    }

    /** Returns the total number of ticks executed since start. */
    public long getTickCount() {
        return tickCount.get();
    }

    // ── Tick Phases ────────────────────────────────────────────────────────

    /**
     * Executes one complete tick cycle.
     *
     * Phase order:
     *   1. PRE-TICK   — drain the sync task queue; process deferred events
     *   2. WORLD      — time advancement, weather, environment
     *   3. ENTITY     — entity update pipeline (batched, prioritized)
     *   4. CHUNK      — chunk load/unload/light/save scheduling
     *   5. POST-TICK  — flush outbound network queues; update profiler snapshot
     *
     * This ordering is intentional. Entities must see a consistent world
     * state before they update. Chunks must be managed after entities have
     * potentially triggered loads. Network flushes happen last to batch
     * as many state changes as possible into one packet window.
     */
    private void runTick(long tick) {
        TickSnapshot snap = profiler.beginTick(tick);

        // ── Phase 1: Pre-tick ───────────────────────────────────────────
        snap.markPhaseStart(TickSnapshot.Phase.PRE);
        runPreTick(tick);
        snap.markPhaseEnd(TickSnapshot.Phase.PRE);

        // ── Phase 2: World ──────────────────────────────────────────────
        snap.markPhaseStart(TickSnapshot.Phase.WORLD);
        runWorldTick(tick);
        snap.markPhaseEnd(TickSnapshot.Phase.WORLD);

        // ── Phase 3: Entity ─────────────────────────────────────────────
        snap.markPhaseStart(TickSnapshot.Phase.ENTITY);
        entityPipeline.tick(tick);
        snap.markPhaseEnd(TickSnapshot.Phase.ENTITY);

        // ── Phase 4: Chunk ──────────────────────────────────────────────
        snap.markPhaseStart(TickSnapshot.Phase.CHUNK);
        chunkManager.tick(tick);
        snap.markPhaseEnd(TickSnapshot.Phase.CHUNK);

        // ── Phase 5: Post-tick ──────────────────────────────────────────
        snap.markPhaseStart(TickSnapshot.Phase.POST);
        runPostTick(tick);
        snap.markPhaseEnd(TickSnapshot.Phase.POST);

        profiler.endTick(snap);
    }

    /**
     * PRE-TICK: Drain the sync task queue and process deferred callbacks
     * posted from async threads (networking, IO, control node).
     *
     * Tasks queued here are guaranteed to execute before any entity or
     * world mutation in the same tick. This makes them safe for state
     * transitions (e.g., player join, command handling, config reload).
     */
    private void runPreTick(long tick) {
        scheduler.drainSyncQueue(tick);
    }

    /**
     * WORLD TICK: Advance world time, tick environment systems.
     *
     * Currently a stub. Intended to host:
     *   - Day/night cycle advancement
     *   - Weather state machine
     *   - Block tick scheduling
     *   - Random block ticks
     *   - Fluid updates
     *   - Portal frame validation
     *
     * Performance note: block ticks and random ticks are the most
     * CPU-intensive world operations on large loaded areas. Cinder
     * uses a configurable per-chunk tick budget to bound their cost.
     */
    private void runWorldTick(long tick) {
        // TODO(cinder-core): implement world environment systems
        // Placeholder: no-op until CinderWorld is wired in.
    }

    /**
     * POST-TICK: Flush outbound network queues and finalize the tick.
     *
     * Flushing happens last so that all entity and world mutations are
     * batched into as few packets as possible. This is especially important
     * on Pi 4's gigabit Ethernet, where per-packet overhead matters more
     * than raw bandwidth.
     */
    private void runPostTick(long tick) {
        // TODO(cinder-core): flush CinderNetworkManager outbound queues
        // TODO(cinder-core): record TPS/MSPT into Cinder Control metrics
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return String.format(
            "CinderTickLoop{tps=%d, running=%b, ticks=%d}",
            targetTps, running.get(), tickCount.get());
    }
}
