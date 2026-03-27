package dev.cinder.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CinderScheduler — task scheduling bridge for Cinder Runtime.
 *
 * Purpose:
 *   The tick loop owns world state exclusively. External systems — networking,
 *   control node communication, USB import hooks, backup triggers — must not
 *   mutate world state directly. They submit tasks here. CinderScheduler
 *   delivers them to the tick thread at safe phase boundaries.
 *
 * Two delivery modes:
 *
 *   SYNC tasks   — queued and drained by the tick thread during the PRE-TICK
 *                  phase (CinderTickLoop.runPreTick → drainSyncQueue).
 *                  Guaranteed to execute on the tick thread before any entity
 *                  or world mutation in that tick. Suitable for: player joins,
 *                  commands, config reloads, plugin callbacks.
 *
 *   ASYNC tasks  — dispatched immediately to a shared ForkJoinPool (or a
 *                  dedicated executor if configured). Results do not touch
 *                  world state unless the task itself posts a follow-up sync
 *                  task. Suitable for: disk IO, network requests, metrics
 *                  emission, USB import validation.
 *
 * Delayed tasks:
 *   Both sync and async tasks may be submitted with a tick-based delay.
 *   Delayed sync tasks are held in a priority queue and released to the
 *   sync drain queue when their scheduled tick arrives.
 *
 * Repeating tasks:
 *   Tasks may be scheduled to repeat every N ticks. Repeating tasks are
 *   re-enqueued automatically after execution. Cancellation is via the
 *   returned TaskHandle.
 *
 * Design constraints:
 *   - submitSync() must be safe to call from any thread at any time.
 *   - drainSyncQueue() is called only on the tick thread.
 *   - The sync drain queue is bounded (default: 4096 tasks per tick drain).
 *     Tasks beyond the bound are carried over to the next tick with a warning.
 *   - No task submitted here should block the tick thread for more than a
 *     few hundred microseconds. Long-running work belongs in async.
 *
 * ARM64 / Pi 4 notes:
 *   - ConcurrentLinkedQueue is used for the sync submission queue because it
 *     is lock-free and performs well under the light contention typical of a
 *     Pi 4 workload (few producer threads, one consumer).
 *   - Async executor is bounded to avoid unbounded thread growth on a 4-core
 *     machine. ForkJoinPool with parallelism=2 is the default.
 */
public final class CinderScheduler {

    private static final Logger LOG = Logger.getLogger("cinder.scheduler");

    /**
     * Maximum number of sync tasks drained per tick.
     * Prevents a task flood from consuming an entire tick budget.
     * Tasks beyond this cap are carried to the next tick.
     */
    private static final int SYNC_DRAIN_CAP = 4096;

    // ── Sync queue (multi-producer, single-consumer on tick thread) ────────

    /**
     * Lock-free queue for sync task submissions from external threads.
     * Drained by the tick thread each PRE-TICK phase.
     */
    private final ConcurrentLinkedQueue<ScheduledTask> syncSubmissionQueue =
        new ConcurrentLinkedQueue<>();

    /**
     * Local drain buffer used during drainSyncQueue().
     * Avoids re-checking the ConcurrentLinkedQueue mid-drain.
     * Only accessed on the tick thread.
     */
    private final ArrayDeque<ScheduledTask> syncDrainBuffer = new ArrayDeque<>(256);

    // ── Delayed task priority queue (tick thread only) ────────────────────

    /**
     * Priority queue for tick-delayed sync tasks.
     * Ordered by scheduled tick (ascending). Accessed only on tick thread.
     */
    private final PriorityQueue<DelayedTask> delayedQueue =
        new PriorityQueue<>(Comparator.comparingLong(t -> t.scheduledTick));

    // ── Async executor ─────────────────────────────────────────────────────

    private final ExecutorService asyncExecutor;

    // ── Task ID generation ─────────────────────────────────────────────────

    private final AtomicLong taskIdCounter = new AtomicLong(0L);

    // ── Statistics ─────────────────────────────────────────────────────────

    private volatile long totalSyncTasksExecuted  = 0L;
    private volatile long totalAsyncTasksSubmitted = 0L;
    private volatile long totalDelayedTasksFired   = 0L;
    private volatile int  lastDrainCount           = 0;

    // ── Constructor ────────────────────────────────────────────────────────

    public CinderScheduler(ExecutorService asyncExecutor) {
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor must not be null");
        LOG.info("[CinderScheduler] Initialized.");
    }

    /**
     * Convenience constructor. Creates a bounded async executor with
     * parallelism = max(1, availableProcessors - 2).
     * Leaves cores for the tick thread and one entity async worker.
     */
    public static CinderScheduler createDefault() {
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        ExecutorService exec = new ForkJoinPool(
            parallelism,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            (t, e) -> LOG.log(Level.WARNING,
                "[CinderScheduler] Unhandled exception in async task thread: " + t.getName(), e),
            /*asyncMode=*/ true
        );
        LOG.info("[CinderScheduler] Default async executor created (parallelism=" + parallelism + ").");
        return new CinderScheduler(exec);
    }

    // ── Sync task submission ───────────────────────────────────────────────

    /**
     * Submits a task to run on the tick thread during the next PRE-TICK phase.
     * Safe to call from any thread at any time.
     *
     * @param label   Human-readable label for logging/profiling (e.g. "player-join:Notch")
     * @param task    The runnable to execute on the tick thread
     * @return        A TaskHandle that can be used to check completion status
     */
    public TaskHandle submitSync(String label, Runnable task) {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(task, "task must not be null");

        long id = taskIdCounter.incrementAndGet();
        ScheduledTask st = new ScheduledTask(id, label, task);
        syncSubmissionQueue.add(st);
        return st.handle;
    }

    /**
     * Submits a task to run on the tick thread after a delay of {@code delayTicks} ticks.
     * The current tick number must be provided so the scheduler can compute
     * the absolute target tick.
     *
     * @param label        Task label for logging
     * @param task         The runnable
     * @param currentTick  The tick number at submission time
     * @param delayTicks   Number of ticks to wait before executing (minimum: 1)
     * @return             TaskHandle
     */
    public TaskHandle submitSyncDelayed(String label, Runnable task, long currentTick, long delayTicks) {
        if (delayTicks < 1) throw new IllegalArgumentException("delayTicks must be >= 1");

        long id = taskIdCounter.incrementAndGet();
        long scheduledTick = currentTick + delayTicks;
        TaskHandle handle = new TaskHandle(id, label);
        DelayedTask dt = new DelayedTask(id, label, task, scheduledTick, 0L, handle);
        // Delayed tasks are posted to the syncSubmissionQueue so the tick thread
        // can move them into the delayedQueue during the next drain.
        syncSubmissionQueue.add(new ScheduledTask(id, "__delayed__", () -> {
            // This wrapper is a no-op — it just carries the DelayedTask into the
            // tick-thread-owned delayedQueue.
        }, dt));
        return handle;
    }

    /**
     * Submits a repeating sync task that fires every {@code periodTicks} ticks.
     *
     * @param label        Task label
     * @param task         The runnable
     * @param currentTick  Current tick at submission time
     * @param delayTicks   Initial delay in ticks before first execution
     * @param periodTicks  Repeat interval in ticks
     * @return             TaskHandle (cancel via handle.cancel())
     */
    public TaskHandle submitSyncRepeating(
            String label, Runnable task,
            long currentTick, long delayTicks, long periodTicks) {

        if (periodTicks < 1) throw new IllegalArgumentException("periodTicks must be >= 1");

        long id = taskIdCounter.incrementAndGet();
        long scheduledTick = currentTick + Math.max(1L, delayTicks);
        TaskHandle handle = new TaskHandle(id, label);
        DelayedTask dt = new DelayedTask(id, label, task, scheduledTick, periodTicks, handle);

        syncSubmissionQueue.add(new ScheduledTask(id, "__repeating-init__", () -> {}, dt));
        return handle;
    }

    // ── Async task submission ──────────────────────────────────────────────

    /**
     * Submits a task for immediate async execution on the async executor pool.
     * The task must not access or mutate world state directly.
     * If it needs to apply results to world state, it should call submitSync()
     * at the end of its execution.
     *
     * @param label  Task label for logging
     * @param task   The runnable
     * @return       A Future representing the async task
     */
    public Future<?> submitAsync(String label, Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        totalAsyncTasksSubmitted++;

        return asyncExecutor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[CinderScheduler] Async task '" + label + "' threw:", e);
            }
        });
    }

    /**
     * Submits a Callable for async execution, returning a Future.
     * Useful when async work needs to return a value.
     */
    public <T> Future<T> submitAsyncCallable(String label, Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        totalAsyncTasksSubmitted++;

        return asyncExecutor.submit(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[CinderScheduler] Async callable '" + label + "' threw:", e);
                throw e;
            }
        });
    }

    // ── Tick-thread drain ──────────────────────────────────────────────────

    /**
     * Drains the sync submission queue and fires any delayed tasks whose
     * scheduled tick has arrived. Called exclusively on the tick thread
     * during the PRE-TICK phase of CinderTickLoop.
     *
     * @param currentTick  The current tick number
     */
    public void drainSyncQueue(long currentTick) {
        // 1. Move pending submissions from the lock-free queue into our local buffer.
        //    We drain into syncDrainBuffer first to avoid repeatedly polling
        //    the ConcurrentLinkedQueue in the hot path.
        ScheduledTask incoming;
        while ((incoming = syncSubmissionQueue.poll()) != null) {
            if (incoming.delayedTask != null) {
                // This is a wrapper for a delayed/repeating task.
                // Move it into the tick-thread priority queue.
                delayedQueue.add(incoming.delayedTask);
            } else {
                syncDrainBuffer.add(incoming);
            }
        }

        // 2. Release any delayed tasks whose scheduled tick has arrived.
        while (!delayedQueue.isEmpty() && delayedQueue.peek().scheduledTick <= currentTick) {
            DelayedTask dt = delayedQueue.poll();
            if (!dt.handle.isCancelled()) {
                syncDrainBuffer.add(new ScheduledTask(dt.id, dt.label, dt.task));
                totalDelayedTasksFired++;

                // If this is a repeating task, re-schedule it.
                if (dt.periodTicks > 0) {
                    DelayedTask next = new DelayedTask(
                        dt.id, dt.label, dt.task,
                        currentTick + dt.periodTicks,
                        dt.periodTicks,
                        dt.handle
                    );
                    delayedQueue.add(next);
                }
            }
        }

        // 3. Execute buffered sync tasks, up to the drain cap.
        int executed = 0;
        ScheduledTask task;
        while ((task = syncDrainBuffer.poll()) != null && executed < SYNC_DRAIN_CAP) {
            if (task.handle.isCancelled()) continue;

            try {
                task.task.run();
                task.handle.markComplete();
                totalSyncTasksExecuted++;
                executed++;
            } catch (Exception e) {
                LOG.log(Level.WARNING,
                    "[CinderScheduler] Sync task '" + task.label + "' threw on tick " + currentTick, e);
                task.handle.markFailed(e);
            }
        }

        // 4. If the cap was hit, remaining tasks stay in syncDrainBuffer for next tick.
        if (!syncDrainBuffer.isEmpty()) {
            LOG.warning(String.format(
                "[CinderScheduler] Sync drain cap hit on tick %d (%d tasks remaining for next tick)",
                currentTick, syncDrainBuffer.size()));
        }

        lastDrainCount = executed;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Shuts down the async executor. Call during server shutdown.
     * Waits up to 5 seconds for in-flight async tasks to complete.
     */
    public void shutdown() {
        LOG.info("[CinderScheduler] Shutting down async executor...");
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
                LOG.warning("[CinderScheduler] Async executor did not terminate cleanly.");
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("[CinderScheduler] Shutdown complete. Total sync tasks executed: "
                + totalSyncTasksExecuted);
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    public SchedulerStats getStats() {
        return new SchedulerStats(
            totalSyncTasksExecuted,
            totalAsyncTasksSubmitted,
            totalDelayedTasksFired,
            syncSubmissionQueue.size(),
            syncDrainBuffer.size(),
            delayedQueue.size(),
            lastDrainCount
        );
    }

    public record SchedulerStats(
        long totalSyncExecuted,
        long totalAsyncSubmitted,
        long totalDelayedFired,
        int pendingSubmissions,
        int bufferedTasks,
        int pendingDelayed,
        int lastDrainCount
    ) {}

    // ── Internal types ─────────────────────────────────────────────────────

    /** A task ready for synchronous execution on the tick thread. */
    private static final class ScheduledTask {
        final long        id;
        final String      label;
        final Runnable    task;
        final TaskHandle  handle;
        final DelayedTask delayedTask;  // non-null only for delayed/repeating wrappers

        ScheduledTask(long id, String label, Runnable task) {
            this(id, label, task, null);
        }

        ScheduledTask(long id, String label, Runnable task, DelayedTask delayedTask) {
            this.id          = id;
            this.label       = label;
            this.task        = task;
            this.handle      = new TaskHandle(id, label);
            this.delayedTask = delayedTask;
        }
    }

    /** A delayed or repeating task held in the priority queue until its tick arrives. */
    private static final class DelayedTask {
        final long       id;
        final String     label;
        final Runnable   task;
        final long       scheduledTick;
        final long       periodTicks;    // 0 = one-shot
        final TaskHandle handle;

        DelayedTask(long id, String label, Runnable task,
                    long scheduledTick, long periodTicks, TaskHandle handle) {
            this.id            = id;
            this.label         = label;
            this.task          = task;
            this.scheduledTick = scheduledTick;
            this.periodTicks   = periodTicks;
            this.handle        = handle;
        }
    }

    // ── TaskHandle ─────────────────────────────────────────────────────────

    /**
     * A handle returned to callers when submitting tasks.
     * Provides status inspection and cancellation.
     *
     * TaskHandle is thread-safe: status transitions are volatile.
     */
    public static final class TaskHandle {

        public enum Status { PENDING, COMPLETE, FAILED, CANCELLED }

        private final long   id;
        private final String label;
        private volatile Status    status    = Status.PENDING;
        private volatile Throwable failCause = null;

        TaskHandle(long id, String label) {
            this.id    = id;
            this.label = label;
        }

        /** Cancel this task. No-op if already complete or failed. */
        public boolean cancel() {
            if (status == Status.PENDING) {
                status = Status.CANCELLED;
                return true;
            }
            return false;
        }

        public boolean isCancelled() { return status == Status.CANCELLED; }
        public boolean isComplete()  { return status == Status.COMPLETE;  }
        public boolean isFailed()    { return status == Status.FAILED;    }
        public boolean isPending()   { return status == Status.PENDING;   }

        public Status    getStatus()    { return status;    }
        public Throwable getFailCause() { return failCause; }
        public long      getId()        { return id;        }
        public String    getLabel()     { return label;     }

        void markComplete()          { status = Status.COMPLETE; }
        void markFailed(Throwable t) { status = Status.FAILED; failCause = t; }

        @Override
        public String toString() {
            return "TaskHandle{id=" + id + ", label='" + label + "', status=" + status + "}";
        }
    }
}
