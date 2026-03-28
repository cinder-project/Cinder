package dev.cinder.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CinderSchedulerTest {

    private CinderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = CinderScheduler.createDefault();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    // ── submitSync — immediate delivery ───────────────────────────────────

    @Test
    void submitSync_taskRunsOnDrain() {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.submitSync("test", counter::incrementAndGet);
        scheduler.drainSyncQueue(1L);
        assertEquals(1, counter.get());
    }

    @Test
    void submitSync_returnsHandle() {
        CinderScheduler.TaskHandle handle = scheduler.submitSync("test", () -> {});
        assertNotNull(handle);
        assertTrue(handle.isPending() || handle.isComplete());
    }

    @Test
    void submitSync_handleCompleteAfterDrain() {
        CinderScheduler.TaskHandle handle = scheduler.submitSync("test", () -> {});
        scheduler.drainSyncQueue(1L);
        assertTrue(handle.isComplete());
    }

    @Test
    void submitSync_multipleTasks_allRun() {
        AtomicInteger counter = new AtomicInteger(0);
        int count = 10;
        for (int i = 0; i < count; i++) {
            scheduler.submitSync("task-" + i, counter::incrementAndGet);
        }
        scheduler.drainSyncQueue(1L);
        assertEquals(count, counter.get());
    }

    @Test
    void submitSync_taskOrderPreserved() {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            final int val = i;
            scheduler.submitSync("task-" + i, () -> order.add(val));
        }
        scheduler.drainSyncQueue(1L);
        assertEquals(List.of(0, 1, 2, 3, 4), order);
    }

    @Test
    void submitSync_fromExternalThread_deliveredOnDrain() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Thread t = new Thread(() -> scheduler.submitSync("external", counter::incrementAndGet));
        t.start();
        t.join(1000);
        scheduler.drainSyncQueue(1L);
        assertEquals(1, counter.get());
    }

    // ── Task cancellation ─────────────────────────────────────────────────

    @Test
    void cancel_beforeDrain_taskDoesNotRun() {
        AtomicInteger counter = new AtomicInteger(0);
        CinderScheduler.TaskHandle handle = scheduler.submitSync("test", counter::incrementAndGet);
        handle.cancel();
        scheduler.drainSyncQueue(1L);
        assertEquals(0, counter.get());
        assertTrue(handle.isCancelled());
    }

    @Test
    void cancel_afterComplete_returnsFalse() {
        CinderScheduler.TaskHandle handle = scheduler.submitSync("test", () -> {});
        scheduler.drainSyncQueue(1L);
        assertFalse(handle.cancel());
    }

    // ── Delayed tasks ─────────────────────────────────────────────────────

    @Test
    void submitSyncDelayed_doesNotRunBeforeDelay() {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.submitSyncDelayed("delayed", counter::incrementAndGet, 1L, 5L);
        scheduler.drainSyncQueue(1L);
        scheduler.drainSyncQueue(2L);
        assertEquals(0, counter.get());
    }

    @Test
    void submitSyncDelayed_runsOnScheduledTick() {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.submitSyncDelayed("delayed", counter::incrementAndGet, 1L, 3L);

        scheduler.drainSyncQueue(1L);
        assertEquals(0, counter.get());

        scheduler.drainSyncQueue(2L);
        assertEquals(0, counter.get());

        scheduler.drainSyncQueue(4L);
        assertEquals(1, counter.get());
    }

    @Test
    void submitSyncDelayed_rejectsZeroDelay() {
        assertThrows(IllegalArgumentException.class, () ->
            scheduler.submitSyncDelayed("bad", () -> {}, 1L, 0L));
    }

    // ── Repeating tasks ───────────────────────────────────────────────────

    @Test
    void submitSyncRepeating_firesEveryPeriod() {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.submitSyncRepeating("repeat", counter::incrementAndGet, 0L, 1L, 2L);

        scheduler.drainSyncQueue(1L);
        assertEquals(1, counter.get());

        scheduler.drainSyncQueue(2L);
        assertEquals(1, counter.get());

        scheduler.drainSyncQueue(3L);
        assertEquals(2, counter.get());

        scheduler.drainSyncQueue(4L);
        assertEquals(2, counter.get());

        scheduler.drainSyncQueue(5L);
        assertEquals(3, counter.get());
    }

    @Test
    void submitSyncRepeating_cancelStopsRepeat() {
        AtomicInteger counter = new AtomicInteger(0);
        CinderScheduler.TaskHandle handle =
            scheduler.submitSyncRepeating("repeat", counter::incrementAndGet, 0L, 1L, 2L);

        scheduler.drainSyncQueue(1L);
        assertEquals(1, counter.get());

        handle.cancel();

        scheduler.drainSyncQueue(3L);
        scheduler.drainSyncQueue(5L);
        assertEquals(1, counter.get());
    }

    @Test
    void submitSyncRepeating_rejectsZeroPeriod() {
        assertThrows(IllegalArgumentException.class, () ->
            scheduler.submitSyncRepeating("bad", () -> {}, 0L, 1L, 0L));
    }

    // ── Async tasks ───────────────────────────────────────────────────────

    @Test
    void submitAsync_taskExecutes() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Future<?> future = scheduler.submitAsync("async", counter::incrementAndGet);
        future.get(5, TimeUnit.SECONDS);
        assertEquals(1, counter.get());
    }

    @Test
    void submitAsync_returnsNonNullFuture() {
        Future<?> future = scheduler.submitAsync("async", () -> {});
        assertNotNull(future);
    }

    @Test
    void submitAsync_exceptionDoesNotPropagate() {
        assertDoesNotThrow(() -> {
            Future<?> f = scheduler.submitAsync("bad", () -> { throw new RuntimeException("boom"); });
            f.get(5, TimeUnit.SECONDS);
        });
    }

    @Test
    void submitAsyncCallable_returnsValue() throws Exception {
        Future<Integer> future = scheduler.submitAsyncCallable("calc", () -> 42);
        assertEquals(42, future.get(5, TimeUnit.SECONDS));
    }

    // ── Exception handling ────────────────────────────────────────────────

    @Test
    void syncTaskException_doesNotStopSubsequentTasks() {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.submitSync("bad",  () -> { throw new RuntimeException("boom"); });
        scheduler.submitSync("good", counter::incrementAndGet);
        scheduler.drainSyncQueue(1L);
        assertEquals(1, counter.get());
    }

    @Test
    void syncTaskException_handleMarkedFailed() {
        CinderScheduler.TaskHandle handle =
            scheduler.submitSync("bad", () -> { throw new RuntimeException("boom"); });
        scheduler.drainSyncQueue(1L);
        assertTrue(handle.isFailed());
        assertNotNull(handle.getFailCause());
    }

    // ── Stats ─────────────────────────────────────────────────────────────

    @Test
    void stats_syncExecutedCountsIncrements() {
        scheduler.submitSync("a", () -> {});
        scheduler.submitSync("b", () -> {});
        scheduler.drainSyncQueue(1L);
        assertEquals(2, scheduler.getStats().totalSyncExecuted());
    }

    @Test
    void stats_asyncSubmittedCountIncrements() {
        scheduler.submitAsync("a", () -> {});
        scheduler.submitAsync("b", () -> {});
        assertEquals(2, scheduler.getStats().totalAsyncSubmitted());
    }

    // ── Null guards ───────────────────────────────────────────────────────

    @Test
    void submitSync_nullLabel_throws() {
        assertThrows(NullPointerException.class, () -> scheduler.submitSync(null, () -> {}));
    }

    @Test
    void submitSync_nullTask_throws() {
        assertThrows(NullPointerException.class, () -> scheduler.submitSync("label", null));
    }
}
