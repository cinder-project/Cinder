package dev.cinder.entity;

import dev.cinder.chunk.ChunkPosition;
import dev.cinder.entity.EntityUpdatePipeline.EntityTier;

import java.util.concurrent.atomic.AtomicLong;

public abstract class CinderEntity {

    private static final AtomicLong ID_COUNTER = new AtomicLong(1L);

    private final long entityId;

    private volatile EntityTier tier;
    private volatile boolean    alive = true;
    private volatile boolean    removed = false;

    private double x;
    private double y;
    private double z;
    private float  yaw;
    private float  pitch;

    private ChunkPosition chunkPosition;

    protected CinderEntity(double x, double y, double z) {
        this.entityId      = ID_COUNTER.getAndIncrement();
        this.x             = x;
        this.y             = y;
        this.z             = z;
        this.chunkPosition = ChunkPosition.fromBlockCoords(x, z);
        this.tier          = defaultTier();
    }

    protected CinderEntity() {
        this(0.0, 0.0, 0.0);
    }

    public final long getEntityId() {
        return entityId;
    }

    public final EntityTier getTier() {
        return tier;
    }

    public final void setTier(EntityTier tier) {
        if (tier == null) throw new IllegalArgumentException("tier must not be null");
        this.tier = tier;
    }

    public final boolean isAlive() {
        return alive && !removed;
    }

    public final void kill() {
        this.alive = false;
    }

    final void markRemoved() {
        this.removed = true;
    }

    public final double getX()    { return x;     }
    public final double getY()    { return y;     }
    public final double getZ()    { return z;     }
    public final float  getYaw()  { return yaw;   }
    public final float  getPitch(){ return pitch; }

    public final ChunkPosition getChunkPosition() {
        return chunkPosition;
    }

    protected final void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;

        ChunkPosition newChunk = ChunkPosition.fromBlockCoords(x, z);
        if (!newChunk.equals(this.chunkPosition)) {
            ChunkPosition oldChunk = this.chunkPosition;
            this.chunkPosition = newChunk;
            onChunkChanged(oldChunk, newChunk);
        }
    }

    protected final void setRotation(float yaw, float pitch) {
        this.yaw   = yaw;
        this.pitch = pitch;
    }

    public final void tick(long tickNumber) {
        if (!isAlive()) return;
        onTick(tickNumber);
    }

    public EntitySnapshot takeSnapshot() {
        return new EntitySnapshot(entityId, x, y, z, yaw, pitch, tier, alive);
    }

    protected abstract void onTick(long tickNumber);

    protected EntityTier defaultTier() {
        return EntityTier.STANDARD;
    }

    protected void onChunkChanged(ChunkPosition from, ChunkPosition to) {
    }

    protected void onSpawn() {
    }

    protected void onDespawn() {
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "{id=" + entityId
            + ", tier=" + tier
            + ", pos=(" + String.format("%.1f", x)
            + ", " + String.format("%.1f", y)
            + ", " + String.format("%.1f", z) + ")"
            + ", alive=" + alive + "}";
    }

    @Override
    public final boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CinderEntity other)) return false;
        return this.entityId == other.entityId;
    }

    @Override
    public final int hashCode() {
        return Long.hashCode(entityId);
    }

    public record EntitySnapshot(
        long       entityId,
        double     x,
        double     y,
        double     z,
        float      yaw,
        float      pitch,
        EntityTier tier,
        boolean    alive
    ) {}
}
