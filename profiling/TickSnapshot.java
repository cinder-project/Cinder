package dev.cinder.profiling;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/**
 * TickSnapshot — immutable per-tick diagnostic record for Cinder Runtime.
 *
 * Purpose:
 *   Every tick executed by CinderTickLoop produces one TickSnapshot. The
 *   snapshot captures:
 *     - The absolute tick number
 *     - Wall-clock timestamp at tick start (for log correlation)
 *     - Total tick duration in nanoseconds (MSPT)
 *     - Per-phase duration for each of the five tick phases
 *     - Whether the tick was a lag spike or overrun
 *
 *   Snapshots are written into a ring buffer by TickProfiler and consumed
 *   by Cinder Control's monitoring dashboard, Cinder Bench's metric
 *   collection pipeline, and the structured log system.
 *
 * Mutability model:
 *   TickSnapshot is built incrementally during a tick via the Builder inner
 *   class, which is mutable and tick-thread-local. Once endTick() is called,
 *   the snapshot is sealed and handed to the ring buffer as an immutable
 *   record. External consumers receive read-only snapshots.
 *
 *   This avoids allocating a new EnumMap per tick in the steady state:
 *   the Builder reuses its phase arrays and only materialises a TickSnapshot
 *   when the tick completes.
 *
 * Phase model:
 *   The five tick phases are defined in the Phase enum. Each phase has a
 *   start and end timestamp captured via markPhaseStart / markPhaseEnd.
 *   Phase durations are stored as nanosecond longs.
 *
 * Usage (from CinderTickLoop):
 *   TickSnapshot snap = profiler.beginTick(tickNumber);
 *   snap.markPhaseStart(Phase.PRE);
 *   runPreTick();
 *   snap.markPhaseEnd(Phase.PRE);
 *   // ... other phases ...
 *   profiler.endTick(snap);
 *
 * ARM64 note:
 *   System.nanoTime() on Linux ARM64 uses the hardware CNTPCT_EL0 register
 *   via vDSO, which is fast (< 10 ns per call) and monotonic. Phase timing
 *   overhead is negligible.
 */
public final class TickSnapshot {

    // ── Phase enum ────────────────────────────────────────────────────────

    /**
     * The five ordered phases of a Cinder tick.
     * Ordinal values are used as array indices for zero-allocation phase storage.
     */
    public enum Phase {
        PRE,    // sync task drain, deferred event processing
        WORLD,  // time, weather, environment
        ENTITY, // entity update pipeline
        CHUNK,  // chunk load/unload/save scheduling
        POST    // network flush, metrics finalization
    }

    private static final int PHASE_COUNT = Phase.values().length;

    // ── Fields ────────────────────────────────────────────────────────────

    /** Monotonically increasing tick sequence number. */
    public final long tickNumber;

    /** Wall-clock time at the start of this tick. For log correlation. */
    public final Instant startInstant;

    /**
     * Total tick duration in nanoseconds.
     * Derived: endNanos - startNanos across the full tick boundary.
     */
    public final long totalNs;

    /**
     * Per-phase durations in nanoseconds.
     * Indexed by Phase.ordinal().
     */
    private final long[] phaseNs;

    /** True if totalNs exceeded the WARN threshold (45 ms at 20 TPS). */
    public final boolean isOverrun;

    /** True if totalNs exceeded the SPIKE threshold (100 ms at 20 TPS). */
    public final boolean isSpike;

    // ── Constructor (package-private — created via Builder) ───────────────

    TickSnapshot(
            long tickNumber,
            Instant startInstant,
            long totalNs,
            long[] phaseNs,
            boolean isOverrun,
            boolean isSpike
    ) {
        this.tickNumber    = tickNumber;
        this.startInstant  = startInstant;
        this.totalNs       = totalNs;
        this.phaseNs       = phaseNs.clone();  // defensive copy from Builder array
        this.isOverrun     = isOverrun;
        this.isSpike       = isSpike;
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    /** Returns the duration of the given phase in nanoseconds. */
    public long phaseNs(Phase phase) {
        return phaseNs[phase.ordinal()];
    }

    /** Returns the duration of the given phase in milliseconds. */
    public double phaseMs(Phase phase) {
        return phaseNs[phase.ordinal()] / 1_000_000.0;
    }

    /** Returns total tick duration in milliseconds (MSPT). */
    public double totalMs() {
        return totalNs / 1_000_000.0;
    }

    /**
     * Returns a read-only map of phase name → duration in nanoseconds.
     * Intended for serialization, dashboard display, and structured logging.
     * Allocates a new EnumMap — use phaseNs(Phase) in hot paths.
     */
    public Map<Phase, Long> phaseBreakdown() {
        EnumMap<Phase, Long> map = new EnumMap<>(Phase.class);
        for (Phase p : Phase.values()) {
            map.put(p, phaseNs[p.ordinal()]);
        }
        return map;
    }

    /**
     * Returns a compact single-line summary suitable for structured logs
     * and the Cinder Control monitoring stream.
     *
     * Format: tick=N total=X.XXms [PRE=X.XX WORLD=X.XX ENTITY=X.XX CHUNK=X.XX POST=X.XX] [SPIKE|OVERRUN|OK]
     */
    public String toLogLine() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("tick=").append(tickNumber);
        sb.append(" total=").append(String.format("%.2f", totalMs())).append("ms");
        sb.append(" [");
        Phase[] phases = Phase.values();
        for (int i = 0; i < phases.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(phases[i].name()).append('=')
              .append(String.format("%.2f", phaseMs(phases[i])));
        }
        sb.append(']');
        if (isSpike) {
            sb.append(" [SPIKE]");
        } else if (isOverrun) {
            sb.append(" [OVERRUN]");
        } else {
            sb.append(" [OK]");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "TickSnapshot{" + toLogLine() + "}";
    }

    // ── Builder ───────────────────────────────────────────────────────────

    /**
     * Mutable builder used by TickProfiler to accumulate phase timings
     * during a live tick. Reused across ticks by TickProfiler to avoid
     * per-tick allocation in the steady state.
     *
     * Thread safety: Builder is tick-thread-local. Do not share across threads.
     */
    public static final class Builder {

        /** MSPT warn threshold: 45 ms in nanoseconds. */
        private static final long WARN_THRESHOLD_NS  = 45_000_000L;

        /** MSPT spike threshold: 100 ms in nanoseconds. */
        private static final long SPIKE_THRESHOLD_NS = 100_000_000L;

        private long     tickNumber;
        private Instant  startInstant;
        private long     startNanos;

        /** Phase start timestamps. Indexed by Phase.ordinal(). */
        private final long[] phaseStart = new long[PHASE_COUNT];

        /** Accumulated phase durations. Indexed by Phase.ordinal(). */
        private final long[] phaseNs    = new long[PHASE_COUNT];

        /** Whether this builder is currently in an open tick. */
        private boolean open = false;

        /**
         * Initializes this builder for a new tick.
         * Resets all phase accumulators.
         *
         * @param tickNumber  The tick sequence number
         */
        public void begin(long tickNumber) {
            this.tickNumber   = tickNumber;
            this.startInstant = Instant.now();
            this.startNanos   = System.nanoTime();
            this.open         = true;

            // Reset phase arrays (avoids allocation).
            for (int i = 0; i < PHASE_COUNT; i++) {
                phaseStart[i] = 0L;
                phaseNs[i]    = 0L;
            }
        }

        /**
         * Records the start of a phase.
         * Must be called before the corresponding markPhaseEnd.
         */
        public void markPhaseStart(Phase phase) {
            phaseStart[phase.ordinal()] = System.nanoTime();
        }

        /**
         * Records the end of a phase and accumulates its duration.
         * Accumulates so that a phase may be entered/exited multiple times
         * (e.g., if the PRE phase drains tasks in multiple passes in future).
         */
        public void markPhaseEnd(Phase phase) {
            long start = phaseStart[phase.ordinal()];
            if (start != 0L) {
                phaseNs[phase.ordinal()] += System.nanoTime() - start;
                phaseStart[phase.ordinal()] = 0L;
            }
        }

        /**
         * Seals the builder and produces an immutable TickSnapshot.
         * The builder is reset and may be reused for the next tick.
         *
         * @return An immutable TickSnapshot representing the completed tick.
         */
        public TickSnapshot seal() {
            if (!open) {
                throw new IllegalStateException(
                    "TickSnapshot.Builder.seal() called without a matching begin().");
            }

            long totalNs   = System.nanoTime() - startNanos;
            boolean spike  = totalNs > SPIKE_THRESHOLD_NS;
            boolean overrun= totalNs > WARN_THRESHOLD_NS;

            open = false;

            return new TickSnapshot(
                tickNumber,
                startInstant,
                totalNs,
                phaseNs,
                overrun,
                spike
            );
        }

        /** Returns true if this builder is currently tracking an open tick. */
        public boolean isOpen() {
            return open;
        }
    }
}
