package dev.cinder.entity;

import dev.cinder.profiling.TickSnapshot;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * EntityUpdatePipeline — Cinder's batched, priority-ordered entity processing system.
 *
 * Overview:
 *   On a Raspberry Pi 4, entity update cost is the primary TPS limiter.
 *   Vanilla-style flat entity iteration does not scale past ~150 active
 *   entities without MSPT degrading noticeably. Cinder addresses this
 *   through three mechanisms:
 *
 *   1. PRIORITY TIERS — entities are bucketed into three update tiers:
 *        CRITICAL  — player-attached entities, projectiles, vehicles.
 *                    Updated every tick. Must be synchronous and fast.
 *        STANDARD  — mobs, animals, NPCs, dropped items.
 *                    Updated every tick, but may be deferred if over budget.
 *        DEFERRED  — decorative entities, distant mobs, sleeping entities.
 *                    Updated every N ticks (configurable; default: 4).
 *
 *   2. BUDGET ENFORCEMENT — each tier has a per-tick CPU budget in nanoseconds.
 *      If a tier exceeds its budget, remaining entities are deferred to the
 *      next tick. This prevents a single bad tick from cascading into a
 *      lag spiral.
 *
 *   3. ASYNC PRE-COMPUTATION — path-finding, AI goal evaluation, and
 *      collision pre-checks run on a bounded async worker pool between
 *      ticks. Results are applied synchronously during the ENTITY phase.
 *      This keeps the tick thread fast while still doing real work.
 *
 * Thread safety:
 *   - entity() is called on the tick thread only.
 *   - submitAsync() may be called from any thread.
 *   - The async result queue (pendingAsyncResults) uses a ConcurrentLinkedQueue
 *     and is drained at the start of each entity phase.
 *
 * ARM64 / Pi 4 notes:
 *   - Worker pool is sized to (availableProcessors - 1), leaving one core
 *     for the tick thread. On Pi 4 (quad-core), this means 3 async workers.
 *   - Object reuse (via entity handle pools) reduces GC pressure during
 *     high-entity ticks. This matters on ARM64 where G1GC pause times are
 *     less predictable than on x86.
 *
 * Usage:
 *   EntityUpdatePipeline pipeline = EntityUpdatePipeline.create(config);
 *   pipeline.register(entity, EntityTier.STANDARD);
 *   // called by CinderTickLoop during ENTITY phase:
 *   pipeline.tick(tickNumber);
 */
public final class EntityUpdatePipeline {

    private static final Logger LOG = Logger.getLogger("cinder.entity");

    // ── Tier CPU budgets (nanoseconds per tick) ────────────────────────────
    // These defaults are tuned for Pi 4 at 20 TPS with a 50 ms total budget.
    // The three tiers together should not exceed ~20 ms to leave headroom for
    // world, chunk, and network phases.

    /** CRITICAL tier budget: 4 ms. Always runs; hard-coded ceiling. */
    private static final long BUDGET_CRITICAL_NS  =  4_000_000L;

    /** STANDARD tier budget: 10 ms. May defer tail entities if exceeded. */
    private static final long BUDGET_STANDARD_NS  = 10_000_000L;

    /** DEFERRED tier budget: 3 ms. Runs every N ticks, spread across ticks. */
    private static final long BUDGET_DEFERRED_NS  =  3_000_000L;

    /** Deferred entities update once every this many ticks. */
    private static final int DEFAULT_DEFERRED_TICK_INTERVAL = 4;

    // ── Entity registries ──────────────────────────────────────────────────

    /**
     * CRITICAL tier: updated every tick, no deferral.
     * Backed by an ArrayList for tight iteration; modifications are safe
     * only on the tick thread.
     */
    private final List<CinderEntity> criticalEntities = new ArrayList<>(64);

    /**
     * STANDARD tier: updated every tick, with budget-based tail deferral.
     * Backed by an ArrayDeque so deferred entities can be re-queued cheaply.
     */
    private final ArrayDeque<CinderEntity> standardEntities = new ArrayDeque<>(256);

    /**
     * DEFERRED tier: updated on a round-robin schedule.
     * The deferredOffset pointer advances each tick to spread CPU cost.
     */
    private final List<CinderEntity> deferredEntities = new ArrayList<>(512);
    private int deferredOffset = 0;
    private volatile int deferredTickInterval = DEFAULT_DEFERRED_TICK_INTERVAL;

    // ── Removal queue ──────────────────────────────────────────────────────
    // Entities are not removed mid-iteration. Instead, they're queued here
    // and purged at the start of the next entity phase.

    private final Queue<CinderEntity> removalQueue = new ConcurrentLinkedQueue<>();

    // ── Async worker pool ──────────────────────────────────────────────────

    private final ExecutorService asyncWorkers;
    private final Queue<AsyncEntityResult> pendingAsyncResults = new ConcurrentLinkedQueue<>();
    private final AtomicInteger asyncInFlight = new AtomicInteger(0);

    // ── Statistics (for Cinder Control / Cinder Bench) ────────────────────

    private volatile long lastCriticalNs  = 0L;
    private volatile long lastStandardNs  = 0L;
    private volatile long lastDeferredNs  = 0L;
    private volatile int  lastEntityCount = 0;
    private volatile int  lastDeferredCount = 0;

    // ── Constructor ────────────────────────────────────────────────────────

    private EntityUpdatePipeline(int asyncWorkerCount) {
        this.asyncWorkers = Executors.newFixedThreadPool(
            asyncWorkerCount,
            r -> {
                Thread t = new Thread(r, "cinder-entity-async-" + asyncWorkerCount);
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);  // slightly below tick thread
                return t;
            }
        );
        LOG.info("[EntityPipeline] Initialized with " + asyncWorkerCount + " async workers.");
    }

    /**
     * Factory method. Derives async worker count from available processors.
     * Leaves one core reserved for the tick thread.
     */
    public static EntityUpdatePipeline create() {
        int cores = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, cores - 1);
        return new EntityUpdatePipeline(workers);
    }

    /**
     * Factory method with explicit worker count override (useful for benchmarking
     * and testing on non-Pi hardware).
     */
    public static EntityUpdatePipeline createWithWorkers(int workers) {
        if (workers < 1) throw new IllegalArgumentException("Worker count must be >= 1");
        return new EntityUpdatePipeline(workers);
    }

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Registers an entity in the pipeline at the given tier.
     * Must be called on the tick thread.
     */
    public void register(CinderEntity entity, EntityTier tier) {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(tier, "tier must not be null");

        switch (tier) {
            case CRITICAL  -> criticalEntities.add(entity);
            case STANDARD  -> standardEntities.add(entity);
            case DEFERRED  -> deferredEntities.add(entity);
        }

        entity.setTier(tier);
        LOG.fine("[EntityPipeline] Registered entity " + entity.getEntityId() + " at tier " + tier);
    }

    /**
     * Queues an entity for removal. Safe to call from any thread.
     * The entity will be removed at the start of the next entity phase.
     */
    public void remove(CinderEntity entity) {
        removalQueue.add(entity);
    }

    /**
     * Promotes an entity from DEFERRED or STANDARD to CRITICAL.
     * Useful when a distant entity becomes player-relevant (e.g., a projectile
     * entering player render range). Must be called on the tick thread.
     */
    public void promote(CinderEntity entity, EntityTier newTier) {
        remove(entity);
        // The entity will be purged at the next tick's cleanup phase.
        // Re-register at the new tier via the scheduler if needed.
        entity.setTier(newTier);
        register(entity, newTier);
    }

    // ── Main tick entry point ─────────────────────────────────────────────

    /**
     * Executes one full entity update phase. Called exclusively by
     * CinderTickLoop on the tick thread during the ENTITY phase.
     */
    public void tick(long tickNumber) {
        // Step 1: Purge entities that were removed since the last tick.
        purgeRemovals();

        // Step 2: Apply results computed asynchronously between ticks.
        drainAsyncResults();

        // Step 3: Run the three update tiers in priority order.
        long t0 = System.nanoTime();
        runCriticalTier(tickNumber);
        lastCriticalNs = System.nanoTime() - t0;

        long t1 = System.nanoTime();
        runStandardTier(tickNumber);
        lastStandardNs = System.nanoTime() - t1;

        long t2 = System.nanoTime();
        runDeferredTier(tickNumber);
        lastDeferredNs = System.nanoTime() - t2;

        // Step 4: Schedule async pre-computation for the next tick.
        scheduleAsyncWork(tickNumber);

        lastEntityCount = criticalEntities.size()
                        + standardEntities.size()
                        + deferredEntities.size();
    }

    // ── Tier runners ──────────────────────────────────────────────────────

    /**
     * CRITICAL tier: update all critical entities unconditionally.
     *
     * If the critical tier itself exceeds its budget, Cinder logs a severe
     * warning. Critical entities are expected to be fast; if they're not,
     * the entity implementations need profiling attention.
     */
    private void runCriticalTier(long tick) {
        long start = System.nanoTime();

        for (int i = 0; i < criticalEntities.size(); i++) {
            CinderEntity e = criticalEntities.get(i);
            if (e.isAlive()) {
                e.tick(tick);
            }
        }

        long elapsed = System.nanoTime() - start;
        if (elapsed > BUDGET_CRITICAL_NS) {
            LOG.warning(String.format(
                "[EntityPipeline] CRITICAL tier over budget: %.2f ms (budget: %.2f ms, entities: %d)",
                elapsed / 1e6, BUDGET_CRITICAL_NS / 1e6, criticalEntities.size()));
        }
    }

    /**
     * STANDARD tier: update entities with budget enforcement.
     *
     * Iterates the standard entity deque. If the cumulative update time
     * exceeds the budget, remaining entities are moved to the back of the
     * deque and will be processed in the next tick. This ensures TPS
     * stability at the cost of some standard entities updating slightly
     * less frequently under load.
     *
     * The deque is used (not a list) so that re-queuing tail entities is O(1).
     */
    private void runStandardTier(long tick) {
        long start = System.nanoTime();
        int processed = 0;
        int deferred = 0;
        int total = standardEntities.size();

        for (int i = 0; i < total; i++) {
            CinderEntity e = standardEntities.poll();
            if (e == null) break;

            long elapsed = System.nanoTime() - start;

            if (elapsed < BUDGET_STANDARD_NS) {
                // Within budget — update this entity.
                if (e.isAlive()) {
                    e.tick(tick);
                    processed++;
                }
                standardEntities.add(e);  // re-add to back of deque for next tick
            } else {
                // Over budget — defer this entity to the next tick.
                standardEntities.addFirst(e);  // priority re-insertion at front
                deferred++;

                // Count remaining entities that were not reached.
                for (int j = i + 1; j < total; j++) {
                    CinderEntity remaining = standardEntities.poll();
                    if (remaining != null) {
                        standardEntities.addFirst(remaining);
                        deferred++;
                    }
                }
                break;
            }
        }

        lastDeferredCount = deferred;

        if (deferred > 0) {
            LOG.fine(String.format(
                "[EntityPipeline] Standard tier deferred %d/%d entities (budget exhausted at %.2f ms)",
                deferred, total, (System.nanoTime() - start) / 1e6));
        }
    }

    /**
     * DEFERRED tier: update a slice of deferred entities each tick.
     *
     * Rather than trying to update all deferred entities every DEFERRED_TICK_INTERVAL
     * ticks in one burst, Cinder spreads the cost across ticks using an offset
     * pointer. Each tick updates a contiguous slice of the deferred list.
     * This produces a smooth CPU profile rather than periodic spikes.
     *
     * Slice size = ceil(size / DEFERRED_TICK_INTERVAL)
     */
    private void runDeferredTier(long tick) {
        if (deferredEntities.isEmpty()) return;

        long start = System.nanoTime();

        int size = deferredEntities.size();
        int interval = Math.max(1, deferredTickInterval);
        int sliceSize = (int) Math.ceil((double) size / interval);

        int from = deferredOffset % size;
        int to   = Math.min(from + sliceSize, size);

        for (int i = from; i < to; i++) {
            CinderEntity e = deferredEntities.get(i);
            if (e.isAlive()) {
                long elapsed = System.nanoTime() - start;
                if (elapsed > BUDGET_DEFERRED_NS) {
                    break;  // Over budget; resume next tick
                }
                e.tick(tick);
            }
        }

        // Advance the offset for next tick's slice.
        deferredOffset = (deferredOffset + sliceSize) % (size == 0 ? 1 : size);
    }

    // ── Removal and async ─────────────────────────────────────────────────

    /** Purges entities queued for removal from all tier lists. */
    private void purgeRemovals() {
        if (removalQueue.isEmpty()) return;

        Set<CinderEntity> toRemove = new HashSet<>();
        CinderEntity e;
        while ((e = removalQueue.poll()) != null) {
            toRemove.add(e);
        }

        criticalEntities.removeIf(toRemove::contains);
        standardEntities.removeIf(toRemove::contains);
        deferredEntities.removeIf(toRemove::contains);
    }

    /**
     * Drains async computation results back into entity state.
     * Results are applied synchronously here, on the tick thread,
     * so no locking is needed for entity state mutation.
     */
    private void drainAsyncResults() {
        AsyncEntityResult result;
        while ((result = pendingAsyncResults.poll()) != null) {
            try {
                result.apply();
            } catch (Exception ex) {
                LOG.warning("[EntityPipeline] Failed to apply async result for entity "
                        + result.entityId() + ": " + ex.getMessage());
            }
            asyncInFlight.decrementAndGet();
        }
    }

    /**
     * Submits async pre-computation work for the next tick.
     *
     * Currently a placeholder. Intended to dispatch:
     *   - Pathfinding requests for mobs with queued movement goals
     *   - AI goal evaluation for standard-tier entities
     *   - Collision pre-check geometry for fast-moving projectiles
     *
     * Results are posted back to pendingAsyncResults and applied at
     * the start of the next entity phase via drainAsyncResults().
     */
    private void scheduleAsyncWork(long tick) {
        // TODO(cinder-core): submit pathfinding and AI goal work to asyncWorkers
        // Example pattern:
        //
        //   for (CinderEntity e : standardEntities) {
        //       if (e.needsPathfindingUpdate()) {
        //           asyncInFlight.incrementAndGet();
        //           asyncWorkers.submit(() -> {
        //               AsyncEntityResult r = e.computePathfinding();
        //               pendingAsyncResults.add(r);
        //           });
        //       }
        //   }
    }

    /**
     * Submits a custom async task on behalf of an entity.
     * May be called from any thread. The result will be applied on the
     * tick thread during the next drainAsyncResults() call.
     *
     * @param task   A callable that returns an AsyncEntityResult
     */
    public void submitAsync(Callable<AsyncEntityResult> task) {
        asyncInFlight.incrementAndGet();
        asyncWorkers.submit(() -> {
            try {
                AsyncEntityResult result = task.call();
                pendingAsyncResults.add(result);
            } catch (Exception ex) {
                asyncInFlight.decrementAndGet();
                LOG.warning("[EntityPipeline] Async task failed: " + ex.getMessage());
            }
        });
    }

    // ── Diagnostics ──────────────────────────────────────────────────────

    /** Returns a diagnostic snapshot for Cinder Control / Cinder Bench. */
    public EntityPipelineStats getStats() {
        return new EntityPipelineStats(
            criticalEntities.size(),
            standardEntities.size(),
            deferredEntities.size(),
            lastDeferredCount,
            asyncInFlight.get(),
            lastCriticalNs,
            lastStandardNs,
            lastDeferredNs
        );
    }

    /** Returns the current deferred-tier update interval in ticks. */
    public int getDeferredTickInterval() {
        return deferredTickInterval;
    }

    /**
     * Updates the deferred-tier cadence at runtime.
     * Must be called on the tick thread.
     */
    public void setDeferredTickInterval(int intervalTicks) {
        int normalized = Math.max(1, intervalTicks);
        if (normalized == deferredTickInterval) {
            return;
        }
        int old = deferredTickInterval;
        deferredTickInterval = normalized;
        LOG.info("[EntityPipeline] Deferred interval updated: " + old + " -> " + normalized);
    }

    /** Shuts down the async worker pool. Call during server shutdown. */
    public void shutdown() {
        asyncWorkers.shutdown();
        LOG.info("[EntityPipeline] Async worker pool shut down.");
    }

    @Override
    public String toString() {
        return String.format(
            "EntityUpdatePipeline{critical=%d, standard=%d, deferred=%d, asyncInFlight=%d}",
            criticalEntities.size(), standardEntities.size(),
            deferredEntities.size(), asyncInFlight.get());
    }

    // ── Supporting types ──────────────────────────────────────────────────

    /**
     * Entity priority tier. Determines update frequency and CPU budget allocation.
     */
    public enum EntityTier {
        /** Player-attached entities, projectiles. Updated every tick without deferral. */
        CRITICAL,
        /** Mobs, animals, items. Updated every tick with budget deferral. */
        STANDARD,
        /** Decorative, distant, or sleeping entities. Updated every N ticks. */
        DEFERRED
    }

    /**
     * Immutable snapshot of pipeline statistics for one tick.
     * Used by Cinder Control's monitoring dashboard and Cinder Bench.
     */
    public record EntityPipelineStats(
        int criticalCount,
        int standardCount,
        int deferredCount,
        int lastDeferredCount,
        int asyncInFlight,
        long criticalNs,
        long standardNs,
        long deferredNs
    ) {
        public double criticalMs()  { return criticalNs  / 1_000_000.0; }
        public double standardMs()  { return standardNs  / 1_000_000.0; }
        public double deferredMs()  { return deferredNs  / 1_000_000.0; }
        public double totalMs()     { return (criticalNs + standardNs + deferredNs) / 1_000_000.0; }
        public int    totalCount()  { return criticalCount + standardCount + deferredCount; }
    }

    /**
     * Contract for async computation results.
     * Implementations carry entity state mutations computed off-thread
     * and apply them synchronously on the tick thread.
     */
    public interface AsyncEntityResult {
        /** The entity ID this result belongs to. Used for logging. */
        long entityId();

        /**
         * Apply the pre-computed result to entity state.
         * Called on the tick thread. Must not block.
         */
        void apply();
    }
}
