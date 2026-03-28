package dev.cinder.profiling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TickProfilerTest {

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
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertEquals(TickProfiler.RollingStats.EMPTY, stats);
        assertEquals(0, stats.sampleCount);
    }

    // ── Single tick lifecycle ─────────────────────────────────────────────

    @Test
    void singleTick_snapshotAvailable() {
        runTick(profiler, 1, 10_000_000L);
        assertNotNull(profiler.getLastSnapshot());
    }

    @Test
    void singleTick_tickCountIncremented() {
        runTick(profiler, 1, 10_000_000L);
        assertEquals(1L, profiler.getTotalTicks());
    }

    @Test
    void singleTick_tickNumberRecorded() {
        runTick(profiler, 42, 10_000_000L);
        assertEquals(42L, profiler.getLastSnapshot().tickNumber);
    }

    @Test
    void singleTick_msptApproximate() {
        runTick(profiler, 1, 10_000_000L);
        double mspt = profiler.getLastMspt();
        assertTrue(mspt >= 0, "MSPT must be non-negative");
    }

    // ── Ring buffer ───────────────────────────────────────────────────────

    @Test
    void ringBuffer_recentSnapshots_returnsAllWhenUnderCapacity() {
        for (int i = 1; i <= 10; i++) {
            runTick(profiler, i, 5_000_000L);
        }
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(10);
        assertEquals(10, snapshots.size());
    }

    @Test
    void ringBuffer_recentSnapshots_clampsToCapacity() {
        for (int i = 1; i <= 20; i++) {
            runTick(profiler, i, 5_000_000L);
        }
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(100);
        assertEquals(20, snapshots.size());
    }

    @Test
    void ringBuffer_wrapsAround_oldestEvicted() {
        for (int i = 1; i <= 25; i++) {
            runTick(profiler, i, 5_000_000L);
        }
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(20);
        assertEquals(20, snapshots.size());
        assertEquals(6L, snapshots.get(0).tickNumber);
        assertEquals(25L, snapshots.get(snapshots.size() - 1).tickNumber);
    }

    @Test
    void ringBuffer_orderedOldestToNewest() {
        for (int i = 1; i <= 15; i++) {
            runTick(profiler, i, 5_000_000L);
        }
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(15);
        for (int i = 0; i < snapshots.size() - 1; i++) {
            assertTrue(snapshots.get(i).tickNumber < snapshots.get(i + 1).tickNumber);
        }
    }

    @Test
    void ringBuffer_requestFewer_returnsNewest() {
        for (int i = 1; i <= 15; i++) {
            runTick(profiler, i, 5_000_000L);
        }
        List<TickSnapshot> snapshots = profiler.getRecentSnapshots(5);
        assertEquals(5, snapshots.size());
        assertEquals(11L, snapshots.get(0).tickNumber);
        assertEquals(15L, snapshots.get(snapshots.size() - 1).tickNumber);
    }

    // ── Spike and overrun detection ───────────────────────────────────────

    @Test
    void snapshot_normalTick_notOverrun() {
        runTick(profiler, 1, 10_000_000L);
        assertFalse(profiler.getLastSnapshot().isOverrun);
    }

    @Test
    void snapshot_normalTick_notSpike() {
        runTick(profiler, 1, 10_000_000L);
        assertFalse(profiler.getLastSnapshot().isSpike);
    }

    @Test
    void snapshot_overrunTick_flaggedOverrun() {
        runFakeTick(profiler, 1, 50_000_000L);
        assertTrue(profiler.getLastSnapshot().isOverrun);
    }

    @Test
    void snapshot_spikeTick_flaggedSpike() {
        runFakeTick(profiler, 1, 110_000_000L);
        assertTrue(profiler.getLastSnapshot().isSpike);
        assertTrue(profiler.getLastSnapshot().isOverrun);
    }

    // ── Rolling stats ─────────────────────────────────────────────────────

    @Test
    void rollingStats_sampleCount() {
        for (int i = 1; i <= 15; i++) {
            runFakeTick(profiler, i, 10_000_000L);
        }
        assertEquals(15, profiler.computeStats().sampleCount);
    }

    @Test
    void rollingStats_sampleCount_capsAtWindow() {
        for (int i = 1; i <= 20; i++) {
            runFakeTick(profiler, i, 10_000_000L);
        }
        assertEquals(TickProfiler.STATS_WINDOW, profiler.computeStats().sampleCount);
    }

    @Test
    void rollingStats_meanMspt_uniform() {
        long nsPerTick = 8_000_000L;
        for (int i = 1; i <= 20; i++) {
            runFakeTick(profiler, i, nsPerTick);
        }
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertEquals(8.0, stats.meanMspt, 0.5);
    }

    @Test
    void rollingStats_maxMspt_identifiesSpike() {
        for (int i = 1; i <= 19; i++) {
            runFakeTick(profiler, i, 5_000_000L);
        }
        runFakeTick(profiler, 20, 80_000_000L);

        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.maxMspt >= 79.0, "Max MSPT should reflect the spike tick");
    }

    @Test
    void rollingStats_minMspt_lessThanMean() {
        for (int i = 1; i <= 20; i++) {
            long ns = (i % 2 == 0) ? 20_000_000L : 5_000_000L;
            runFakeTick(profiler, i, ns);
        }
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.minMspt < stats.meanMspt);
        assertTrue(stats.maxMspt > stats.meanMspt);
    }

    @Test
    void rollingStats_percentileOrdering() {
        for (int i = 1; i <= 20; i++) {
            runFakeTick(profiler, i, i * 1_000_000L);
        }
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.minMspt <= stats.p50Mspt);
        assertTrue(stats.p50Mspt <= stats.p95Mspt);
        assertTrue(stats.p95Mspt <= stats.p99Mspt);
        assertTrue(stats.p99Mspt <= stats.maxMspt);
    }

    @Test
    void rollingStats_overrunCount() {
        for (int i = 1; i <= 17; i++) {
            runFakeTick(profiler, i, 10_000_000L);
        }
        runFakeTick(profiler, 18, 50_000_000L);
        runFakeTick(profiler, 19, 60_000_000L);
        runFakeTick(profiler, 20, 70_000_000L);

        TickProfiler.RollingStats stats = profiler.computeStats();
        assertEquals(3, stats.overrunCount);
    }

    @Test
    void rollingStats_spikeCount() {
        for (int i = 1; i <= 18; i++) {
            runFakeTick(profiler, i, 10_000_000L);
        }
        runFakeTick(profiler, 19, 110_000_000L);
        runFakeTick(profiler, 20, 120_000_000L);

        TickProfiler.RollingStats stats = profiler.computeStats();
        assertEquals(2, stats.spikeCount);
    }

    // ── Status line ───────────────────────────────────────────────────────

    @Test
    void statusLine_containsTps() {
        for (int i = 1; i <= 20; i++) {
            runFakeTick(profiler, i, 5_000_000L);
        }
        String line = profiler.computeStats().toStatusLine();
        assertTrue(line.contains("TPS="));
    }

    @Test
    void statusLine_containsMspt() {
        for (int i = 1; i <= 20; i++) {
            runFakeTick(profiler, i, 5_000_000L);
        }
        String line = profiler.computeStats().toStatusLine();
        assertTrue(line.contains("MSPT"));
    }

    @Test
    void statusLine_degradedFlag_whenTpsLow() {
        for (int i = 1; i <= 20; i++) {
            runFakeTick(profiler, i, 80_000_000L);
        }
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.isTpsDegraded());
        assertTrue(stats.toStatusLine().contains("TPS DEGRADED"));
    }

    @Test
    void statusLine_p99Flag_whenOverBudget() {
        for (int i = 1; i <= 20; i++) {
            runFakeTick(profiler, i, 60_000_000L);
        }
        TickProfiler.RollingStats stats = profiler.computeStats();
        assertTrue(stats.isP99Degraded());
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

    /**
     * Runs a tick using the real Builder API. Actual elapsed time will be
     * near-zero (the test body is fast), so this is used only to verify
     * structural correctness, not timing values.
     */
    private static void runTick(TickProfiler profiler, long tickNumber, long recordNs) {
        TickSnapshot.Builder builder = profiler.beginTick(tickNumber);
        for (TickSnapshot.Phase phase : TickSnapshot.Phase.values()) {
            builder.markPhaseStart(phase);
            builder.markPhaseEnd(phase);
        }
        profiler.recordTick(tickNumber, recordNs);
        profiler.endTick(builder);
    }

    /**
     * Runs a tick using a manually constructed snapshot with a controlled
     * totalNs value, bypassing real wall-clock timing. Used for stats tests
     * that need deterministic MSPT values.
     */
    private static void runFakeTick(TickProfiler profiler, long tickNumber, long totalNs) {
        TickSnapshot.Builder builder = profiler.beginTick(tickNumber);
        profiler.recordTick(tickNumber, totalNs);
        profiler.endTick(builder);
    }
}
