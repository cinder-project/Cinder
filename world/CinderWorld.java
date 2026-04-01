package dev.cinder.world;

import dev.cinder.chunk.ChunkLifecycleManager;
import dev.cinder.chunk.ChunkPosition;
import dev.cinder.chunk.CinderChunk;
import dev.cinder.entity.CinderEntity;
import dev.cinder.entity.EntityUpdatePipeline;
import dev.cinder.entity.EntityUpdatePipeline.EntityTier;
import dev.cinder.server.CinderScheduler;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * CinderWorld — world state container for Cinder Core.
 *
 * Owns:
 *   - World time (day/night cycle, absolute tick counter)
 *   - Weather state machine (clear, rain, thunder)
 *   - The entity registry (source of truth for entity existence)
 *   - Read-only access to the loaded chunk map via ChunkLifecycleManager
 *
 * Threading:
 *   All mutation must occur on the tick thread. Read-only accessors
 *   (getTime, getWeather, getEntityById) are safe from any thread on
 *   their volatile fields, but the entity map itself must only be
 *   structurally modified on the tick thread.
 *
 * Relationship to other systems:
 *   - CinderTickLoop calls tickWorld(tick) during the WORLD phase.
 *   - CinderServer wires CinderWorld into the tick loop on startup.
 *   - EntityUpdatePipeline and ChunkLifecycleManager are injected
 *     as dependencies; CinderWorld coordinates but does not own them.
 */
public final class CinderWorld {

    private static final Logger LOG = Logger.getLogger("cinder.world");

    // ── Time ──────────────────────────────────────────────────────────────

    /** Full day/night cycle in ticks. Matches vanilla Minecraft. */
    public static final long DAY_TICKS = 24_000L;

    /** Tick at which daytime begins. */
    public static final long DAWN_TICK = 0L;

    /** Tick at which night begins. */
    public static final long DUSK_TICK = 13_000L;

    /**
     * World time in ticks, modulo DAY_TICKS.
     * 0 = dawn, 6000 = noon, 13000 = dusk, 18000 = midnight.
     */
    private volatile long worldTime = 0L;

    /** Absolute tick count since world creation. Never wraps. */
    private final AtomicLong absoluteTick = new AtomicLong(0L);

    private boolean doDaylightCycle = true;

    /** Runtime simulation radius (chunks). Used by adaptive tuning controls. */
    private volatile int simulationDistance = 6;

    // ── Weather ───────────────────────────────────────────────────────────

    public enum Weather { CLEAR, RAIN, THUNDER }

    private volatile Weather weather = Weather.CLEAR;

    /** Remaining ticks for the current weather state. */
    private long weatherDuration = 0L;

    /** Remaining ticks until next possible rain. */
    private long clearWeatherDuration = 6000L;

    private boolean doWeatherCycle = true;

    // ── Entity registry (tick thread only for writes) ─────────────────────

    /**
     * All entities currently tracked in this world.
     * ConcurrentHashMap allows safe reads from monitoring threads.
     * Structural modifications (add/remove) must only happen on tick thread.
     */
    private final ConcurrentHashMap<Long, CinderEntity> entities = new ConcurrentHashMap<>(256);

    // ── Dependencies ──────────────────────────────────────────────────────

    private final ChunkLifecycleManager chunkManager;
    private final EntityUpdatePipeline  entityPipeline;
    private final CinderScheduler       scheduler;
    private final FlatWorldGenerator    worldGenerator;
    private final BlockTickScheduler    blockTickScheduler;
    private final FluidUpdateSystem     fluidUpdateSystem;
    private final SpawnController       spawnController;

    // ── Identity ──────────────────────────────────────────────────────────

    private final String worldName;

    // ── Constructor ───────────────────────────────────────────────────────

    public CinderWorld(
            String worldName,
            ChunkLifecycleManager chunkManager,
            EntityUpdatePipeline entityPipeline,
            CinderScheduler scheduler
    ) {
        this.worldName     = Objects.requireNonNull(worldName, "worldName");
        this.chunkManager  = Objects.requireNonNull(chunkManager, "chunkManager");
        this.entityPipeline = Objects.requireNonNull(entityPipeline, "entityPipeline");
        this.scheduler     = Objects.requireNonNull(scheduler, "scheduler");
        this.worldGenerator = new FlatWorldGenerator();
        this.blockTickScheduler = new BlockTickScheduler();
        this.fluidUpdateSystem = new FluidUpdateSystem();
        this.spawnController = new SpawnController();

        LOG.info("[CinderWorld] Initialised world: " + worldName);
    }

    // ── Tick entry point (tick thread only) ───────────────────────────────

    /**
     * Advances world state by one tick. Called by CinderTickLoop during
     * the WORLD phase, before entity and chunk phases.
     */
    public void tick(long tickNumber) {
        absoluteTick.set(tickNumber);

        if (doDaylightCycle) {
            worldTime = (worldTime + 1L) % DAY_TICKS;
        }

        if (doWeatherCycle) {
            tickWeather();
        }

        blockTickScheduler.tick(tickNumber);
        fluidUpdateSystem.tick(this);
    }

    // ── Weather state machine ─────────────────────────────────────────────

    private void tickWeather() {
        if (weather == Weather.CLEAR) {
            if (clearWeatherDuration > 0) {
                clearWeatherDuration--;
            } else {
                setWeather(Weather.RAIN, randomWeatherDuration());
            }
        } else {
            if (weatherDuration > 0) {
                weatherDuration--;
            } else {
                if (weather == Weather.RAIN && Math.random() < 0.01) {
                    setWeather(Weather.THUNDER, randomThunderDuration());
                } else {
                    setWeather(Weather.CLEAR, randomClearDuration());
                }
            }
        }
    }

    public void setWeather(Weather newWeather, long durationTicks) {
        Weather old = this.weather;
        this.weather = newWeather;

        if (newWeather == Weather.CLEAR) {
            this.clearWeatherDuration = durationTicks;
            this.weatherDuration = 0L;
        } else {
            this.weatherDuration = durationTicks;
            this.clearWeatherDuration = 0L;
        }

        if (old != newWeather) {
            LOG.info(String.format("[CinderWorld] Weather changed: %s → %s (duration: %d ticks)",
                old, newWeather, durationTicks));
        }
    }

    private static long randomWeatherDuration() {
        return 6000L + (long) (Math.random() * 12000L);
    }

    private static long randomThunderDuration() {
        return 3600L + (long) (Math.random() * 7200L);
    }

    private static long randomClearDuration() {
        return 12000L + (long) (Math.random() * 12000L);
    }

    // ── Entity management (tick thread only for add/remove) ───────────────

    /**
     * Spawns an entity into the world at the given tier.
     * Registers it with both the entity registry and the update pipeline.
     * Must be called on the tick thread.
     */
    public void spawnEntity(CinderEntity entity, EntityTier tier) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(tier, "tier");

        if (!spawnController.canSpawn(entity.getChunkPosition())) {
            LOG.fine("[CinderWorld] Spawn denied by cap for entity " + entity.getEntityId()
                + " at " + entity.getChunkPosition());
            return;
        }

        entities.put(entity.getEntityId(), entity);
        entityPipeline.register(entity, tier);
        spawnController.onSpawn(entity.getChunkPosition());

        ChunkPosition chunkPos = entity.getChunkPosition();
        chunkManager.addHolder(chunkPos);

        LOG.fine(String.format("[CinderWorld] Spawned entity %d (%s) at chunk %s tier=%s",
            entity.getEntityId(), entity.getClass().getSimpleName(), chunkPos, tier));
    }

    /**
     * Removes an entity from the world.
     * Queues removal from the pipeline and releases the chunk holder.
     * Safe to call on the tick thread at any point in the tick.
     */
    public void despawnEntity(CinderEntity entity) {
        Objects.requireNonNull(entity, "entity");

        if (entities.remove(entity.getEntityId()) != null) {
            entityPipeline.remove(entity);
            chunkManager.removeHolder(entity.getChunkPosition());
            spawnController.onDespawn(entity.getChunkPosition());
            entity.kill();

            LOG.fine("[CinderWorld] Despawned entity " + entity.getEntityId());
        }
    }

    /**
     * Returns the entity with the given ID, or null if not present.
     * Safe to call from any thread (ConcurrentHashMap read).
     */
    public CinderEntity getEntityById(long entityId) {
        return entities.get(entityId);
    }

    /**
     * Returns an unmodifiable view of all entities.
     * Safe to read from any thread; structural consistency only guaranteed
     * on the tick thread.
     */
    public Collection<CinderEntity> getEntities() {
        return Collections.unmodifiableCollection(entities.values());
    }

    public int getEntityCount() {
        return entities.size();
    }

    // ── Chunk access ──────────────────────────────────────────────────────

    /**
     * Returns the chunk at the given position if currently loaded, null otherwise.
     * Delegates directly to ChunkLifecycleManager — no additional caching.
     */
    public CinderChunk getChunkIfLoaded(ChunkPosition pos) {
        return chunkManager.getChunkIfLoaded(pos);
    }

    /**
     * Requests that a chunk be loaded asynchronously and calls the callback
     * on the tick thread when it becomes available.
     */
    public void loadChunk(ChunkPosition pos, java.util.function.Consumer<CinderChunk> onLoad) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(onLoad, "onLoad");

        chunkManager.requestChunkWithCallback(pos, chunk -> {
            if (chunk != null) {
                onLoad.accept(chunk);
                return;
            }

            // Defensive fallback: never propagate a null chunk to world callers.
            onLoad.accept(worldGenerator.generate(pos));
        });
    }

    /**
     * Returns the block ID at the given world coordinates, or 0 (air) if
     * the chunk is not loaded.
     */
    public short getBlock(int x, int y, int z) {
        CinderChunk chunk = chunkManager.getChunkIfLoaded(
            ChunkPosition.fromBlockCoords(x, z));
        return chunk != null ? chunk.getBlock(x, y, z) : 0;
    }

    /**
     * Sets the block at the given world coordinates if the chunk is loaded.
     * Marks the chunk dirty. No-op if the chunk is not loaded.
     * Must be called on the tick thread.
     */
    public void setBlock(int x, int y, int z, short blockId) {
        ChunkPosition pos = ChunkPosition.fromBlockCoords(x, z);
        CinderChunk chunk = chunkManager.getChunkIfLoaded(pos);
        if (chunk != null) {
            chunk.setBlock(x, y, z, blockId);
            chunkManager.markDirty(pos);
        }
    }

    // ── Time accessors ────────────────────────────────────────────────────

    public long getWorldTime()    { return worldTime; }
    public long getAbsoluteTick() { return absoluteTick.get(); }
    public boolean isDay()        { return worldTime < DUSK_TICK; }
    public boolean isNight()      { return !isDay(); }

    public void setWorldTime(long time) {
        this.worldTime = time % DAY_TICKS;
    }

    public void setDoDaylightCycle(boolean enabled) {
        this.doDaylightCycle = enabled;
    }

    public int getSimulationDistance() {
        return simulationDistance;
    }

    public void setSimulationDistance(int simulationDistance) {
        this.simulationDistance = Math.max(2, Math.min(simulationDistance, 32));
    }

    // ── Weather accessors ─────────────────────────────────────────────────

    public Weather getWeather()      { return weather; }
    public boolean isRaining()       { return weather == Weather.RAIN || weather == Weather.THUNDER; }
    public boolean isThundering()    { return weather == Weather.THUNDER; }

    public void setDoWeatherCycle(boolean enabled) {
        this.doWeatherCycle = enabled;
    }

    // ── Identity ──────────────────────────────────────────────────────────

    public String getWorldName() { return worldName; }

    // ── Diagnostics ───────────────────────────────────────────────────────

    public WorldStats getStats() {
        return new WorldStats(
            worldName,
            worldTime,
            absoluteTick.get(),
            weather,
            entities.size(),
            chunkManager.getStats()
        );
    }

    public record WorldStats(
        String worldName,
        long worldTime,
        long absoluteTick,
        Weather weather,
        int entityCount,
        ChunkLifecycleManager.ChunkManagerStats chunkStats
    ) {
        public boolean isDay() { return worldTime < DUSK_TICK; }

        @Override
        public String toString() {
            return String.format(
                "WorldStats{name=%s, time=%d (%s), weather=%s, entities=%d, %s}",
                worldName, worldTime, isDay() ? "day" : "night",
                weather, entityCount, chunkStats);
        }
    }

    @Override
    public String toString() {
        return String.format("CinderWorld{name=%s, time=%d, weather=%s, entities=%d}",
            worldName, worldTime, weather, entities.size());
    }
}
