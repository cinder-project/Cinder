package dev.cinder.profiling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TickProfilerTest {

    private static final long WARN_NS  = 45_000_000L;
    private static final long SPIKE_NS = 100_000_000L;

    private TickProfiler profiler;

    @BeforeEach
    void setUp() {
        profiler = new TickProfiler(20);
    }

    // ── Initial state ─────────────────────────────────────────────────────

    @Test
    void initialState_noSnapshot() {
        assertNull(profiler.getLastSnapshot());
    }

    @Test
    void initialState_zeroTicks() {
        assertEquals(0L, profiler.getTotalTicks());
    }

    @Test
    void initialState_emptyStats() {
        assertEquals(TickProfiler.RollingStats.EMPTY, profiler.computeStats());
        assertEquals(0, profiler.computeStats().sampleCount);
    }

    // ── Single tick lifecycle ─────────────────────────────────────────────

    @Test
    void singleTick_snapshotAvailable() {
        runRealTick(profiler, 1);
        assertNotNull(profiler.getLastSnapshot());
    }

    @Test
    void singleTick_tickCountIncremented() {
        runRealTick(profiler, 1);
        assertEquals(1L, profiler.getTotalTicks());
    }

    @Test
    void singleTick_tickNumberRecorded() {
        runRealTick(profiler, 42);
        assertEquals(42L, profiler.getLastSnapshot().tickNumber);
    }

    @Test
    void singleTick_msptNonNegative() {
        runRealTick(profiler, 1);
        assertTrue(profiler.getLastMspt() >= 0);
    }

    // ── Ring buffer ───────────────────────────────────────────────────────

    @Test
    void ringBuffer_recentSnapshots_returnsAllWhenUnderCapacity() {
        for (int i = 1; i <= 10; i++) inject(profiler, i, 5_000_000L);
        assertEquals(10, profiler.getRecentSnapshots(10).size());
    }

    @Test
    void ringBuffer_recentSnapshots_clampsToRingCapacity() {
        for (int i = 1; i <= 20; i++) inject(profiler, i, 5_000_000L);
        assertEquals(20, profiler.getRecentSnapshots(100).size());
    }

    @Test
    void ringBuffer_statsWindow_capsAtWindowSize() {
        TickProfiler large = new TickProfiler(200);
        for (int i = 1; i <= 200; i++) inject(large, i, 5_000_000L);
        assertEquals(TickProfiler.STATS_WINDOW, large.computeStats().sampleCount);
    }

    @Test
    void ringBuffer_wrapsAround_oldestEvicted() {
        for (int i = 1; i <= 25; i++) inject(profiler, i, 5_000_000L);
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(20);
        assertEquals(20, snapshots.size());
        assertEquals(6L,  snapshots.get(0).tickNumber);
        assertEquals(25L, snapshots.get(snapshots.size() - 1).tickNumber);
    }

    @Test
    void ringBuffer_orderedOldestToNewest() {
        for (int i = 1; i <= 15; i++) inject(profiler, i, 5_000_000L);
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(15);
        for (int i = 0; i < snapshots.size() - 1; i++) {
            assertTrue(snapshots.get(i).tickNumber < snapshots.get(i + 1).tickNumber);
        }
    }

    @Test
    void ringBuffer_requestFewer_returnsNewest() {
        for (int i = 1; i <= 15; i++) inject(profiler, i, 5_000_000L);
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(5);
        assertEquals(5,   snapshots.size());
        assertEquals(11L, snapshots.get(0).tickNumber);
        assertEquals(15L, snapshots.get(snapshots.size() - 1).tickNumber);
    }

    // ── Spike and overrun detection ───────────────────────────────────────

    @Test
    void snapshot_normalTick_notOverrun() {
        inject(profiler, 1, 10_000_000L);
        assertFalse(profiler.getLastSnapshot().isOverrun);
    }

    @Test
    void snapshot_normalTick_notSpike() {
        inject(profiler, 1, 10_000_000L);
        assertFalse(profiler.getLastSnapshot().isSpike);
    }

    @Test
    void snapshot_overrunTick_flaggedOverrun() {
        inject(profiler, 1, WARN_NS + 1);
        assertTrue(profiler.getLastSnapshot().isOverrun);
    }

    @Test
    void snapshot_spikeTick_flaggedSpikeAndOverrun() {
        inject(profiler, 1, SPIKE_NS + 1);
        assertTrue(profiler.getLastSnapshot().isSpike);
        assertTrue(profiler.getLastSnapshot().isOverrun);
    }

    // ── Rolling stats ─────────────────────────────────────────────────────

    @Test
    void rollingStats_sampleCount() {
        for (int i = 1; i <= 15; i++) inject(profiler, i, 10_000_000L);
        assertEquals(15, profiler.computeStats().sampleCount);
    }

    @Test
    void rollingStats_meanMspt_uniform() {
        for (int i = 1; i <= 20; i++) inject(profiler, i, 8_000_000L);
        assertEquals(8.0, profiler.computeStats().meanMspt, 0.1);
    }

    @Test
    void rollingStats_maxMspt_identifiesSpike() {
        for (int i = 1; i <= 19; i++) inject(profiler, i, 5_000_000L);
        inject(profiler, 20, 80_000_000L);
        assertTrue(profiler.computeStats().maxMspt >= 79.0);
    }

    @Test
    void rollingStats_minMspt_lessThanMean() {
        for (int i = 1; i <= 20; i++) {
            inject(profiler, i, (i % 2 == 0) ? 20_000_000L : 5_000_000L);
        }
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.minMspt < stats.meanMspt);
        assertTrue(stats.maxMspt > stats.meanMspt);
    }

    @Test
    void rollingStats_percentileOrdering() {
        for (int i = 1; i <= 20; i++) inject(profiler, i, i * 1_000_000L);
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.minMspt  <= stats.p50Mspt);
        assertTrue(stats.p50Mspt  <= stats.p95Mspt);
        assertTrue(stats.p95Mspt  <= stats.p99Mspt);
        assertTrue(stats.p99Mspt  <= stats.maxMspt);
    }

    @Test
    void rollingStats_overrunCount() {
        for (int i = 1; i <= 17; i++) inject(profiler, i, 10_000_000L);
        inject(profiler, 18, WARN_NS + 1);
        inject(profiler, 19, WARN_NS + 2);
        inject(profiler, 20, WARN_NS + 3);
        assertEquals(3, profiler.computeStats().overrunCount);
    }

    @Test
    void rollingStats_spikeCount() {
        for (int i = 1; i <= 18; i++) inject(profiler, i, 10_000_000L);
        inject(profiler, 19, SPIKE_NS + 1);
        inject(profiler, 20, SPIKE_NS + 2);
        assertEquals(2, profiler.computeStats().spikeCount);
    }

    // ── TPS derivation ────────────────────────────────────────────────────

    @Test
    void tps_approximatelyTwenty_withFiftyMsSpacing() {
        Instant base = Instant.now();
        for (int i = 1; i <= 20; i++) {
            injectAt(profiler, i, 5_000_000L, base.plusMillis((long) (i - 1) * 50));
        }
        double tps = profiler.computeStats().tps;
        assertTrue(tps >= 19.0 && tps <= 21.0,
            "Expected ~20 TPS but got: " + tps);
    }

    @Test
    void tps_degraded_withSlowTicks() {
        Instant base = Instant.now();
        for (int i = 1; i <= 20; i++) {
            injectAt(profiler, i, 80_000_000L, base.plusMillis((long) (i - 1) * 80));
        }
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.isTpsDegraded(),
            "Expected TPS degraded but TPS was: " + stats.tps);
    }

    // ── Status line ───────────────────────────────────────────────────────

    @Test
    void statusLine_containsTps() {
        for (int i = 1; i <= 20; i++) inject(profiler, i, 5_000_000L);
        assertTrue(profiler.computeStats().toStatusLine().contains("TPS="));
    }

    @Test
    void statusLine_containsMspt() {
        for (int i = 1; i <= 20; i++) inject(profiler, i, 5_000_000L);
        assertTrue(profiler.computeStats().toStatusLine().contains("MSPT"));
    }

    @Test
    void statusLine_p99Flag_whenOverBudget() {
        for (int i = 1; i <= 20; i++) inject(profiler, i, 60_000_000L);
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.isP99Degraded(),
            "Expected p99 over budget but p99 was: " + stats.p99Mspt);
        assertTrue(stats.toStatusLine().contains("P99 OVER BUDGET"));
    }

    // ── Minimum capacity guard ────────────────────────────────────────────

    @Test
    void constructor_rejectsCapacityBelowMinimum() {
        assertThrows(IllegalArgumentException.class, () -> new TickProfiler(19));
    }

    @Test
    void createDefault_succeeds() {
        assertDoesNotThrow(TickProfiler::createDefault);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void runRealTick(TickProfiler profiler, long tickNumber) {
        TickSnapshot.Builder builder = profiler.beginTick(tickNumber);
        for (TickSnapshot.Phase phase : TickSnapshot.Phase.values()) {
            builder.markPhaseStart(phase);
            builder.markPhaseEnd(phase);
        }
        profiler.recordTick(tickNumber, 0L);
        profiler.endTick(builder);
    }

    private static void inject(TickProfiler profiler, long tickNumber, long totalNs) {
        injectAt(profiler, tickNumber, totalNs, Instant.now());
    }

    private static void injectAt(TickProfiler profiler, long tickNumber,
                                  long totalNs, Instant startInstant) {
        boolean overrun = totalNs > 45_000_000L;
        boolean spike   = totalNs > 100_000_000L;
        TickSnapshot snapshot = new TickSnapshot(
            tickNumber,
            startInstant,
            totalNs,
            new long[TickSnapshot.Phase.values().length],
            overrun,
            spike
        );
        profiler.injectSnapshot(snapshot);
        profiler.recordTick(tickNumber, totalNs);
    }
}
