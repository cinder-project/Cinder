package dev.cinder.chunk;

import dev.cinder.server.CinderScheduler;

import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ChunkLifecycleManager — Cinder's chunk load, unload, and caching pipeline.
 *
 * Overview:
 *   Chunk management is the second largest TPS cost on Pi 4 after entity
 *   updates. Vanilla-style synchronous chunk loading stalls the tick thread
 *   for disk IO, which on a microSD card can easily consume 10–40 ms per
 *   load event. Cinder eliminates this by separating chunk lifecycle into
 *   three distinct phases:
 *
 *   1. ASYNC LOAD    — chunk data is read from disk on an IO executor thread.
 *                      The tick thread is never blocked waiting for disk.
 *
 *   2. SYNC PROMOTE  — when an async load completes, the chunk is posted to
 *                      the sync queue (CinderScheduler.submitSync) and
 *                      promoted to LOADED state on the tick thread during
 *                      the next PRE-TICK phase.
 *
 *   3. ASYNC SAVE    — dirty chunks are serialized and written to disk on
 *                      the IO executor. Saves do not stall ticks.
 *
 * Chunk states:
 *   UNLOADED  → LOADING  → LOADED  → DIRTY  → SAVING  → LOADED
 *                                           ↘ UNLOADING → UNLOADED
 *
 * Cache:
 *   Loaded chunks are held in a LinkedHashMap-backed LRU cache. When the
 *   cache exceeds its configured capacity, the least-recently-used chunks
 *   that have no active holders are evicted and scheduled for async save/unload.
 *
 * Holder system:
 *   Any system that needs a chunk to remain loaded (e.g., an entity in that
 *   chunk, a player's view distance) increments the chunk's holder count.
 *   A chunk with holders > 0 is never evicted. This replaces ticket-based
 *   systems with a simpler reference-count model suited for single-world Pi
 *   deployments.
 *
 * Pi 4 / ARM64 notes:
 *   - microSD IO is single-threaded and slow (~20–60 MB/s sequential).
 *     The IO executor is sized to 1 thread by default to avoid amplifying
 *     contention on the SD card controller. When running from NVMe/USB SSD,
 *     this can be raised via config.
 *   - Chunk serialization uses a compact binary format to minimize write
 *     amplification and reduce SD card wear.
 *   - The LRU cache default of 256 chunks balances RAM usage (~256 MB at
 *     ~1 MB/chunk NBT) against reload frequency. On 8 GB Pi 4, 512 is safe.
 *
 * Thread safety:
 *   - chunkCache, loadingSet, and dirtySet are accessed only on the tick thread
 *     (or via submitSync callbacks), except where noted.
 *   - ChunkPosition is an immutable value type; safe to share across threads.
 *   - Async callbacks post results to the tick thread via CinderScheduler.
 */
public final class ChunkLifecycleManager {

    private static final Logger LOG = Logger.getLogger("cinder.chunk");

    /** Default LRU cache capacity in chunks. */
    public static final int DEFAULT_CACHE_CAPACITY = 256;

    /**
     * Number of chunk loads attempted per tick.
     * Spread across ticks to smooth out IO-induced MSPT spikes.
     * At 20 TPS, this allows up to 40 async load submissions per second.
     */
    private static final int LOADS_PER_TICK = 2;

    /**
     * Number of dirty chunks saved per tick sweep.
     * Keeps save pressure from accumulating while staying off the hot path.
     */
    private static final int SAVES_PER_TICK = 4;

    // ── Chunk cache (tick thread only) ────────────────────────────────────

    /**
     * LRU cache of loaded chunks.
     * Key: ChunkPosition. Value: CinderChunk.
     * Backed by a LinkedHashMap in access-order mode.
     */
    private final LinkedHashMap<ChunkPosition, CinderChunk> chunkCache;
    private final int cacheCapacity;

    // ── Queues and sets (tick thread only unless noted) ───────────────────

    /** Chunks requested for loading, in order of request. */
    private final ArrayDeque<ChunkPosition> loadQueue = new ArrayDeque<>(64);

    /** Chunks currently being loaded on the IO executor. */
    private final Set<ChunkPosition> loadingSet = new HashSet<>(64);

    /** Chunks that have been modified and need saving. */
    private final Set<ChunkPosition> dirtySet = new LinkedHashSet<>(64);

    /** Chunks currently being saved on the IO executor. */
    private final Set<ChunkPosition> savingSet = new HashSet<>(32);

    // ── IO executor ───────────────────────────────────────────────────────

    /**
     * Single-threaded executor for all disk IO.
     * One thread avoids SD card controller contention.
     * Upgrade to 2–4 threads when running from USB SSD.
     */
    private final ExecutorService ioExecutor;

    // ── Dependencies ──────────────────────────────────────────────────────

    private final CinderScheduler scheduler;
    private final ChunkStorage     storage;

    // ── Statistics ────────────────────────────────────────────────────────

    private long totalLoads    = 0L;
    private long totalSaves    = 0L;
    private long totalEvictions= 0L;
    private long cacheHits     = 0L;
    private long cacheMisses   = 0L;

    // ── Constructor ───────────────────────────────────────────────────────

    public ChunkLifecycleManager(
            CinderScheduler scheduler,
            ChunkStorage storage,
            int cacheCapacity,
            int ioThreads
    ) {
        this.scheduler     = Objects.requireNonNull(scheduler, "scheduler");
        this.storage       = Objects.requireNonNull(storage, "storage");
        this.cacheCapacity = cacheCapacity;

        // LinkedHashMap in access-order mode acts as an LRU cache.
        // removeEldestEntry is overridden to trigger eviction when over capacity.
        this.chunkCache = new LinkedHashMap<>(cacheCapacity, 0.75f, /*accessOrder=*/ true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkPosition, CinderChunk> eldest) {
                if (size() > cacheCapacity) {
                    CinderChunk chunk = eldest.getValue();
                    if (chunk.getHolderCount() == 0) {
                        scheduleEviction(eldest.getKey(), chunk);
                        totalEvictions++;
                        return true;
                    }
                }
                return false;
            }
        };

        this.ioExecutor = Executors.newFixedThreadPool(
            ioThreads,
            r -> {
                Thread t = new Thread(r, "cinder-chunk-io");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 2);  // below entity async workers
                return t;
            }
        );

        LOG.info(String.format(
            "[ChunkManager] Initialized — cache=%d chunks, ioThreads=%d", cacheCapacity, ioThreads));
    }

    /** Convenience constructor with defaults suited for Pi 4 + microSD. */
    public static ChunkLifecycleManager createDefault(CinderScheduler scheduler, ChunkStorage storage) {
        return new ChunkLifecycleManager(scheduler, storage, DEFAULT_CACHE_CAPACITY, /*ioThreads=*/ 1);
    }

    // ── Tick entry point ─────────────────────────────────────────────────

    /**
     * Executes chunk lifecycle work for one tick.
     * Called by CinderTickLoop during the CHUNK phase.
     *
     * Per-tick work:
     *   1. Advance up to LOADS_PER_TICK async loads from the load queue
     *   2. Schedule dirty chunk saves (up to SAVES_PER_TICK)
     */
    public void tick(long tickNumber) {
        processLoadQueue();
        processDirtyQueue();
    }

    // ── Public API (tick thread) ──────────────────────────────────────────

    /**
     * Returns the chunk at the given position if it is currently loaded.
     * Records a cache hit/miss for diagnostics.
     *
     * This is the primary hot-path accessor. No IO, no blocking.
     *
     * @return The loaded CinderChunk, or null if not currently loaded.
     */
    public CinderChunk getChunkIfLoaded(ChunkPosition pos) {
        CinderChunk chunk = chunkCache.get(pos);
        if (chunk != null) {
            cacheHits++;
        } else {
            cacheMisses++;
        }
        return chunk;
    }

    /**
     * Requests that a chunk be loaded. If the chunk is already loaded or
     * loading, this is a no-op. Otherwise, it is enqueued for async IO.
     *
     * The chunk will not be available until a future tick when the async
     * load completes and is promoted. Callers that need the chunk should
     * either poll getChunkIfLoaded() in subsequent ticks, or register a
     * callback via requestChunkWithCallback().
     */
    public void requestLoad(ChunkPosition pos) {
        if (chunkCache.containsKey(pos) || loadingSet.contains(pos)) {
            return;  // already loaded or in-flight
        }
        if (!loadQueue.contains(pos)) {
            loadQueue.add(pos);
        }
    }

    /**
     * Requests a chunk load and invokes a callback on the tick thread when
     * the chunk becomes available. If already loaded, the callback fires
     * immediately (next pre-tick).
     *
     * @param pos       The chunk position to load
     * @param onLoad    Callback receiving the loaded CinderChunk, runs on tick thread
     */
    public void requestChunkWithCallback(ChunkPosition pos, java.util.function.Consumer<CinderChunk> onLoad) {
        CinderChunk existing = chunkCache.get(pos);
        if (existing != null) {
            // Already loaded — fire callback on next tick via scheduler
            scheduler.submitSync("chunk-callback:" + pos, () -> onLoad.accept(existing));
            return;
        }
        // Enqueue load; callback will be posted via submitSync upon completion
        loadQueue.add(pos);
        loadingSet.add(pos);
        submitAsyncLoad(pos, onLoad);
    }

    /**
     * Marks a chunk as dirty (modified). It will be saved during the next
     * save sweep. Safe to call any number of times; redundant marks are no-ops.
     */
    public void markDirty(ChunkPosition pos) {
        if (chunkCache.containsKey(pos)) {
            dirtySet.add(pos);
        }
    }

    /**
     * Increments the holder count for a chunk, preventing LRU eviction.
     * Call when an entity, player, or system takes a reference to a chunk.
     */
    public void addHolder(ChunkPosition pos) {
        CinderChunk chunk = chunkCache.get(pos);
        if (chunk != null) {
            chunk.incrementHolders();
        }
    }

    /**
     * Decrements the holder count for a chunk.
     * When holders reach 0, the chunk becomes eligible for LRU eviction.
     */
    public void removeHolder(ChunkPosition pos) {
        CinderChunk chunk = chunkCache.get(pos);
        if (chunk != null) {
            chunk.decrementHolders();
        }
    }

    /**
     * Forces an immediate synchronous unload of a chunk.
     * If the chunk is dirty, it is saved before removal.
     * Should only be used during server shutdown or world transitions.
     * Must be called on the tick thread.
     */
    public void forceUnload(ChunkPosition pos) {
        CinderChunk chunk = chunkCache.get(pos);
        if (chunk == null) return;

        if (dirtySet.contains(pos)) {
            try {
                storage.saveSync(pos, chunk);
                dirtySet.remove(pos);
                totalSaves++;
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "[ChunkManager] Force-save failed for " + pos, e);
            }
        }

        chunk.onUnload();
        chunkCache.remove(pos);
        LOG.fine("[ChunkManager] Force-unloaded " + pos);
    }

    // ── Internal load/save pipeline ───────────────────────────────────────

    /** Advances the load queue, submitting up to LOADS_PER_TICK async loads. */
    private void processLoadQueue() {
        int submitted = 0;
        while (!loadQueue.isEmpty() && submitted < LOADS_PER_TICK) {
            ChunkPosition pos = loadQueue.poll();
            if (chunkCache.containsKey(pos) || loadingSet.contains(pos)) {
                continue;  // race: already loaded or being loaded
            }
            loadingSet.add(pos);
            submitAsyncLoad(pos, null);
            submitted++;
        }
    }

    /** Submits up to SAVES_PER_TICK dirty chunks for async saving. */
    private void processDirtyQueue() {
        int submitted = 0;
        Iterator<ChunkPosition> it = dirtySet.iterator();
        while (it.hasNext() && submitted < SAVES_PER_TICK) {
            ChunkPosition pos = it.next();
            if (savingSet.contains(pos)) continue;  // save already in-flight

            CinderChunk chunk = chunkCache.get(pos);
            if (chunk == null) {
                it.remove();
                continue;
            }

            // Take a snapshot of the chunk for async serialization.
            // The snapshot is immutable; the live chunk can continue being mutated.
            byte[] snapshot = chunk.takeSnapshot();
            it.remove();
            savingSet.add(pos);

            ioExecutor.submit(() -> {
                try {
                    storage.saveAsync(pos, snapshot);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "[ChunkManager] Async save failed for " + pos, e);
                } finally {
                    scheduler.submitSync("chunk-save-complete:" + pos, () -> {
                        savingSet.remove(pos);
                        totalSaves++;
                    });
                }
            });
            submitted++;
        }
    }

    /**
     * Submits an async chunk load. On completion, the loaded chunk is promoted
     * to the chunk cache on the tick thread via submitSync.
     *
     * @param pos       Chunk to load
     * @param onLoad    Optional callback to invoke after promotion (nullable)
     */
    private void submitAsyncLoad(ChunkPosition pos, java.util.function.Consumer<CinderChunk> onLoad) {
        ioExecutor.submit(() -> {
            CinderChunk chunk;
            try {
                chunk = storage.load(pos);
                if (chunk == null) {
                    // Chunk does not exist on disk — generate a new one.
                    chunk = CinderChunk.generate(pos);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[ChunkManager] Async load failed for " + pos, e);
                scheduler.submitSync("chunk-load-failed:" + pos, () -> {
                    loadingSet.remove(pos);
                });
                return;
            }

            final CinderChunk finalChunk = chunk;

            // Promote to tick thread: insert into cache, fire callback.
            scheduler.submitSync("chunk-promote:" + pos, () -> {
                loadingSet.remove(pos);
                chunkCache.put(pos, finalChunk);
                finalChunk.onLoad();
                totalLoads++;

                LOG.fine("[ChunkManager] Promoted chunk " + pos
                       + " (cache size: " + chunkCache.size() + ")");

                if (onLoad != null) {
                    try {
                        onLoad.accept(finalChunk);
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "[ChunkManager] onLoad callback threw for " + pos, e);
                    }
                }
            });
        });
    }

    /**
     * Called by the LRU cache's removeEldestEntry when a chunk is being evicted.
     * If the chunk is dirty, it is saved before removal.
     */
    private void scheduleEviction(ChunkPosition pos, CinderChunk chunk) {
        chunk.onUnload();

        if (dirtySet.contains(pos)) {
            // Save before evicting to avoid data loss.
            byte[] snapshot = chunk.takeSnapshot();
            dirtySet.remove(pos);
            savingSet.add(pos);

            ioExecutor.submit(() -> {
                try {
                    storage.saveAsync(pos, snapshot);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "[ChunkManager] Eviction save failed for " + pos, e);
                } finally {
                    scheduler.submitSync("chunk-evict-save-complete:" + pos, () -> {
                        savingSet.remove(pos);
                        totalSaves++;
                    });
                }
            });
        }

        LOG.fine("[ChunkManager] Evicted chunk " + pos);
    }

    // ── Shutdown ──────────────────────────────────────────────────────────

    /**
     * Saves all dirty chunks and shuts down the IO executor.
     * Must be called on the tick thread during server shutdown.
     */
    public void shutdown() {
        LOG.info("[ChunkManager] Shutdown — saving " + dirtySet.size() + " dirty chunks...");

        for (ChunkPosition pos : new ArrayList<>(dirtySet)) {
            forceUnload(pos);
        }

        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.warning("[ChunkManager] IO executor did not terminate in 30s.");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOG.info(String.format(
            "[ChunkManager] Shutdown complete. loads=%d saves=%d evictions=%d hitRate=%.1f%%",
            totalLoads, totalSaves, totalEvictions, getCacheHitRate() * 100));
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    public double getCacheHitRate() {
        long total = cacheHits + cacheMisses;
        return total == 0 ? 1.0 : (double) cacheHits / total;
    }

    public ChunkManagerStats getStats() {
        return new ChunkManagerStats(
            chunkCache.size(), cacheCapacity,
            loadQueue.size(), loadingSet.size(),
            dirtySet.size(), savingSet.size(),
            totalLoads, totalSaves, totalEvictions,
            getCacheHitRate()
        );
    }

    public record ChunkManagerStats(
        int loadedChunks,
        int cacheCapacity,
        int pendingLoads,
        int inFlightLoads,
        int dirtyChunks,
        int inFlightSaves,
        long totalLoads,
        long totalSaves,
        long totalEvictions,
        double cacheHitRate
    ) {
        @Override
        public String toString() {
            return String.format(
                "ChunkManager{loaded=%d/%d, pendingLoad=%d, inFlightLoad=%d, " +
                "dirty=%d, inFlightSave=%d, hitRate=%.1f%%}",
                loadedChunks, cacheCapacity, pendingLoads, inFlightLoads,
                dirtyChunks, inFlightSaves, cacheHitRate * 100);
        }
    }

    // ── Supporting interfaces ─────────────────────────────────────────────

    /**
     * ChunkStorage — abstraction over the chunk persistence layer.
     *
     * Implementations may target:
     *   - RegionFile format (Anvil / .mca files)
     *   - A flat binary format optimized for Pi SD card sequential access
     *   - An in-memory store for benchmarking (no disk IO)
     *
     * All methods except saveSync may be called from the IO executor thread.
     * saveSync is called on the tick thread during forceUnload / shutdown only.
     */
    public interface ChunkStorage {
        /**
         * Loads chunk data from disk. Returns null if the chunk does not exist.
         * Called on the IO executor thread.
         */
        CinderChunk load(ChunkPosition pos) throws Exception;

        /**
         * Saves a pre-serialized chunk snapshot asynchronously.
         * Called on the IO executor thread.
         */
        void saveAsync(ChunkPosition pos, byte[] snapshot) throws Exception;

        /**
         * Saves a chunk synchronously (used only during forced unload/shutdown).
         * Called on the tick thread — must be fast.
         */
        void saveSync(ChunkPosition pos, CinderChunk chunk) throws Exception;
    }
}
