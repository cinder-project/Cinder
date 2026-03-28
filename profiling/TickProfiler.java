package dev.cinder.profiling;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * TickProfiler — ring buffer, rolling statistics, and diagnostic output
 * for Cinder's tick performance instrumentation.
 *
 * Overview:
 *   TickProfiler sits between the tick loop and all monitoring consumers.
 *   Its two responsibilities are:
 *
 *   1. RECORD — capture per-tick TickSnapshot data produced by CinderTickLoop
 *      into a fixed-size ring buffer. Writing is O(1) and allocation-free in
 *      the steady state.
 *
 *   2. REPORT — expose rolling statistics (TPS, mean/max/p99 MSPT, lag spikes)
 *      to Cinder Control's monitoring dashboard, structured log output, and
 *      the Cinder Bench metrics collection pipeline.
 *
 * Ring buffer design:
 *   The ring buffer stores the last N TickSnapshots (default: 1200, covering
 *   60 seconds at 20 TPS). It is implemented as a pre-allocated array with a
 *   write-head pointer. Reads take a consistent copy of the write head to
 *   allow concurrent reads without locking.
 *
 *   The buffer is sized so that Cinder Control can display a 60-second rolling
 *   TPS graph without any heap growth in normal operation.
 *
 * Statistics:
 *   Rolling stats are computed lazily over the last N snapshots. Cinder
 *   Control polls these at 1 Hz; the computation cost is amortised.
 *
 *   Reported metrics:
 *     - Current TPS (derived from tick timestamps in the last second)
 *     - Mean MSPT over the last 100 ticks
 *     - P50 / P95 / P99 MSPT over the last 100 ticks
 *     - Max MSPT (spike) over the last 100 ticks
 *     - Overrun count (ticks > 45 ms) in the last 100 ticks
 *     - Spike count (ticks > 100 ms) in the last 100 ticks
 *     - Per-phase mean MSPT over the last 100 ticks
 *
 * Thread safety:
 *   - beginTick() and endTick() are called only on the tick thread.
 *   - recordTick() is a lightweight hook called by CinderTickLoop for
 *     coarse MSPT tracking before full snapshot sealing.
 *   - getSnapshot() and computeStats() are safe to call from any thread.
 *     They operate on a volatile read of the write head and treat the
 *     array as effectively immutable for the window they read.
 *
 * ARM64 note:
 *   The ring buffer array is heap-allocated once at startup. On ARM64, array
 *   element access is cache-friendly when iterating sequentially. Reading
 *   100 contiguous TickSnapshot references for stats computation fits within
 *   L2 cache on Pi 4 (1 MB L2 per core cluster).
 */
public final class TickProfiler {

    private static final Logger LOG = Logger.getLogger("cinder.profiler");

    /** Default ring buffer capacity: 60 seconds at 20 TPS. */
    public static final int DEFAULT_RING_CAPACITY = 1200;

    /** Rolling stats window size in ticks. */
    public static final int STATS_WINDOW = 100;

    // ── Ring buffer ───────────────────────────────────────────────────────

    /** Pre-allocated array of snapshot slots. Fixed size; never resized. */
    private final TickSnapshot[] ring;
    private final int            capacity;

    /**
     * Write head: index of the next slot to write.
     * Atomic so readers can take a consistent position snapshot.
     */
    private final AtomicLong writeHead = new AtomicLong(0L);

    /**
     * Total ticks recorded since profiler start.
     * Monotonically increasing; used to derive TPS.
     */
    private final AtomicLong totalTicks = new AtomicLong(0L);

    // ── Builder (reused per-tick, tick-thread-local) ──────────────────────

    /**
     * The single Builder instance reused for every tick.
     * Only accessed on the tick thread.
     */
    private final TickSnapshot.Builder builder = new TickSnapshot.Builder();

    // ── Last snapshot (for lightweight polling) ───────────────────────────

    private final AtomicReference<TickSnapshot> lastSnapshot = new AtomicReference<>(null);

    // ── Coarse MSPT tracker (written by recordTick, read by Control) ──────

    /**
     * Most recent raw MSPT in nanoseconds, written by CinderTickLoop's
     * lightweight recordTick() hook. Used for the live MSPT readout in
     * Cinder Control without waiting for full stats computation.
     */
    private volatile long lastRawNs = 0L;

    // ── Constructor ───────────────────────────────────────────────────────

    public TickProfiler(int ringCapacity) {
        if (ringCapacity < 20) {
            throw new IllegalArgumentException("Ring capacity must be >= 20, got: " + ringCapacity);
        }
        this.capacity = ringCapacity;
        this.ring     = new TickSnapshot[ringCapacity];
        LOG.info("[TickProfiler] Initialized — ring capacity=" + ringCapacity + " ticks.");
    }

    public static TickProfiler createDefault() {
        return new TickProfiler(DEFAULT_RING_CAPACITY);
    }

    // ── Tick lifecycle hooks (tick thread only) ───────────────────────────

    /**
     * Called at the start of each tick by CinderTickLoop, before phase work begins.
     * Initializes the reusable Builder and returns it as a TickSnapshot handle.
     *
     * The returned object is the Builder itself (cast as TickSnapshot for caller
     * convenience). Phase marks should be applied to it during the tick.
     *
     * @param tickNumber  The current tick sequence number
     * @return            The active Builder, ready to receive phase marks
     */
    public TickSnapshot.Builder beginTick(long tickNumber) {
        builder.begin(tickNumber);
        return builder;
    }

    /**
     * Called at the end of each tick by CinderTickLoop after all phases complete.
     * Seals the Builder into an immutable TickSnapshot and writes it to the ring.
     *
     * @param snap  The Builder returned by beginTick (unused parameter name kept
     *              for API symmetry with the caller; internally we use this.builder)
     */
    public void endTick(TickSnapshot.Builder snap) {
        TickSnapshot sealed = builder.seal();
        writeToRing(sealed);
        lastSnapshot.set(sealed);
        totalTicks.incrementAndGet();
    }

    /**
     * Lightweight MSPT recording hook called by CinderTickLoop immediately
     * after each tick, before draining the sleep. This feeds the live MSPT
     * readout in Cinder Control without the overhead of full snapshot sealing.
     *
     * @param tick     Tick number (for logging correlation)
     * @param elapsedNs  Raw tick duration in nanoseconds
     */
    public void recordTick(long tick, long elapsedNs) {
        lastRawNs = elapsedNs;
    }

    // ── Ring buffer write ─────────────────────────────────────────────────

    /**
     * Writes a sealed TickSnapshot into the next ring slot.
     * O(1), no allocation. The old snapshot in the overwritten slot is
     * unreferenced and becomes GC-eligible.
     */
    private void writeToRing(TickSnapshot snapshot) {
        long head = writeHead.getAndIncrement();
        int  slot = (int) (head % capacity);
        ring[slot] = snapshot;
    }

    void injectSnapshot(TickSnapshot snapshot) {
        writeToRing(snapshot);
        lastSnapshot.set(snapshot);
        totalTicks.incrementAndGet();
    }

    // ── Read API (any thread) ─────────────────────────────────────────────

    /**
     * Returns the most recently completed TickSnapshot, or null if no tick
     * has completed yet. Safe to call from any thread.
     */
    public TickSnapshot getLastSnapshot() {
        return lastSnapshot.get();
    }

    /**
     * Returns the raw MSPT of the most recent tick in nanoseconds.
     * Updated by the lightweight recordTick hook; always current.
     */
    public long getLastRawNs() {
        return lastRawNs;
    }

    /** Returns the last raw MSPT in milliseconds. */
    public double getLastMspt() {
        return lastRawNs / 1_000_000.0;
    }

    /**
     * Copies the last {@code count} snapshots into a new List.
     * If fewer than {@code count} snapshots have been recorded, returns
     * all available snapshots. The list is ordered oldest → newest.
     *
     * Allocation note: this allocates a new ArrayList. Call from monitoring
     * threads, not from the tick thread.
     *
     * @param count  Number of recent snapshots to retrieve (clamped to capacity)
     */
    public List<TickSnapshot> getRecentSnapshots(int count) {
        int n = Math.min(count, capacity);
        List<TickSnapshot> result = new ArrayList<>(n);

        long head  = writeHead.get();
        long start = Math.max(0, head - n);

        for (long i = start; i < head; i++) {
            TickSnapshot s = ring[(int) (i % capacity)];
            if (s != null) result.add(s);
        }

        return result;
    }

    // ── Statistics computation ────────────────────────────────────────────

    /**
     * Computes rolling statistics over the last {@code STATS_WINDOW} ticks.
     *
     * Called by Cinder Control's monitoring thread at ~1 Hz.
     * Computes TPS, MSPT percentiles, overrun/spike counts, and per-phase means.
     *
     * @return A RollingStats snapshot representing the last 100 ticks.
     */
    public RollingStats computeStats() {
        List<TickSnapshot> window = getRecentSnapshots(STATS_WINDOW);

        if (window.isEmpty()) {
            return RollingStats.EMPTY;
        }

        int    count         = window.size();
        long   overruns      = 0;
        long   spikes        = 0;
        long[] totalNsArr    = new long[count];
        double[] phaseMs     = new double[TickSnapshot.Phase.values().length];

        for (int i = 0; i < count; i++) {
            TickSnapshot s = window.get(i);
            totalNsArr[i] = s.totalNs;
            if (s.isOverrun) overruns++;
            if (s.isSpike)   spikes++;

            for (TickSnapshot.Phase p : TickSnapshot.Phase.values()) {
                phaseMs[p.ordinal()] += s.phaseMs(p);
            }
        }

        // Normalise phase means.
        for (int i = 0; i < phaseMs.length; i++) {
            phaseMs[i] /= count;
        }

        // Sort a copy for percentile computation.
        long[] sorted = totalNsArr.clone();
        java.util.Arrays.sort(sorted);

        long   minNs  = sorted[0];
        long   maxNs  = sorted[sorted.length - 1];
        long   p50Ns  = sorted[(int) (count * 0.50)];
        long   p95Ns  = sorted[(int) Math.min(count - 1, count * 0.95)];
        long   p99Ns  = sorted[(int) Math.min(count - 1, count * 0.99)];

        LongSummaryStatistics stats = java.util.Arrays.stream(totalNsArr).summaryStatistics();
        double meanMs = stats.getAverage() / 1_000_000.0;

        // Derive TPS from tick timestamps in the window.
        double tps = deriveTps(window);

        return new RollingStats(
            count,
            tps,
            meanMs,
            minNs  / 1_000_000.0,
            maxNs  / 1_000_000.0,
            p50Ns  / 1_000_000.0,
            p95Ns  / 1_000_000.0,
            p99Ns  / 1_000_000.0,
            overruns,
            spikes,
            phaseMs
        );
    }

    /**
     * Derives TPS from the wall-clock timestamps of snapshots in the window.
     * Uses the elapsed real time between the oldest and newest snapshot to
     * compute how many ticks occurred per second.
     *
     * Falls back to the nominal 20.0 TPS if the window is too small.
     */
    private double deriveTps(List<TickSnapshot> window) {
        if (window.size() < 2) return 20.0;

        TickSnapshot oldest = window.get(0);
        TickSnapshot newest = window.get(window.size() - 1);

        long elapsedNs = java.time.Duration.between(
            oldest.startInstant, newest.startInstant).toNanos();

        if (elapsedNs <= 0) return 20.0;

        double elapsedSeconds = elapsedNs / 1_000_000_000.0;
        return (window.size() - 1) / elapsedSeconds;
    }

    // ── Total uptime stats ────────────────────────────────────────────────

    /** Returns the total number of ticks recorded since profiler start. */
    public long getTotalTicks() {
        return totalTicks.get();
    }

    // ── RollingStats record ───────────────────────────────────────────────

    /**
     * Immutable snapshot of rolling tick performance statistics.
     * Produced by computeStats() and consumed by Cinder Control and Cinder Bench.
     */
    public static final class RollingStats {

        public static final RollingStats EMPTY = new RollingStats(
            0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L,
            new double[TickSnapshot.Phase.values().length]
        );

        /** Number of ticks in the sample window. */
        public final int    sampleCount;

        /** Derived TPS over the sample window. */
        public final double tps;

        /** Mean MSPT over the sample window. */
        public final double meanMspt;

        public final double minMspt;
        public final double maxMspt;
        public final double p50Mspt;
        public final double p95Mspt;
        public final double p99Mspt;

        /** Number of overrun ticks (> 45 ms) in the window. */
        public final long overrunCount;

        /** Number of spike ticks (> 100 ms) in the window. */
        public final long spikeCount;

        /**
         * Per-phase mean MSPT. Indexed by Phase.ordinal().
         * Use phaseMeanMs(Phase) for named access.
         */
        private final double[] phaseMeanMs;

        RollingStats(
                int sampleCount, double tps,
                double meanMspt, double minMspt, double maxMspt,
                double p50Mspt, double p95Mspt, double p99Mspt,
                long overrunCount, long spikeCount,
                double[] phaseMeanMs
        ) {
            this.sampleCount  = sampleCount;
            this.tps          = tps;
            this.meanMspt     = meanMspt;
            this.minMspt      = minMspt;
            this.maxMspt      = maxMspt;
            this.p50Mspt      = p50Mspt;
            this.p95Mspt      = p95Mspt;
            this.p99Mspt      = p99Mspt;
            this.overrunCount = overrunCount;
            this.spikeCount   = spikeCount;
            this.phaseMeanMs  = phaseMeanMs.clone();
        }

        /** Returns the mean MSPT for the given phase over the sample window. */
        public double phaseMeanMs(TickSnapshot.Phase phase) {
            return phaseMeanMs[phase.ordinal()];
        }

        /** Returns true if TPS is below the healthy threshold (< 19.0). */
        public boolean isTpsDegraded() {
            return tps < 19.0;
        }

        /** Returns true if p99 MSPT exceeds the tick budget (50 ms). */
        public boolean isP99Degraded() {
            return p99Mspt > 50.0;
        }

        /**
         * Formats a compact summary line for Cinder Control's status output.
         *
         * Example:
         *   TPS=19.87  MSPT mean=8.21 p50=7.94 p95=12.03 p99=18.45 max=34.12
         *   phases [PRE=0.12 WORLD=0.04 ENTITY=4.31 CHUNK=2.18 POST=1.56]
         *   overruns=0 spikes=0 (n=100)
         */
        public String toStatusLine() {
            StringBuilder sb = new StringBuilder(256);
            sb.append(String.format("TPS=%.2f  MSPT mean=%.2f p50=%.2f p95=%.2f p99=%.2f max=%.2f",
                tps, meanMspt, p50Mspt, p95Mspt, p99Mspt, maxMspt));
            sb.append("\n  phases [");

            TickSnapshot.Phase[] phases = TickSnapshot.Phase.values();
            for (int i = 0; i < phases.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(phases[i].name()).append('=')
                  .append(String.format("%.2f", phaseMeanMs[i]));
            }
            sb.append(']');
            sb.append(String.format("  overruns=%d spikes=%d (n=%d)",
                overrunCount, spikeCount, sampleCount));

            if (isTpsDegraded()) sb.append("  [TPS DEGRADED]");
            if (isP99Degraded()) sb.append("  [P99 OVER BUDGET]");

            return sb.toString();
        }

        @Override
        public String toString() {
            return "RollingStats{" + toStatusLine().replace('\n', ' ') + "}";
        }
    }
}
