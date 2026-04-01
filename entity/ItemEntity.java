package dev.cinder.entity;

import dev.cinder.entity.EntityUpdatePipeline.EntityTier;

/**
 * Dropped item lifecycle entity with despawn timer.
 */
public final class ItemEntity extends CinderEntity {

    private static final long DEFAULT_DESPAWN_TICKS = 6000L;
    private long ageTicks = 0L;
    private final long despawnTicks;

    public ItemEntity(double x, double y, double z) {
        this(x, y, z, DEFAULT_DESPAWN_TICKS);
    }

    public ItemEntity(double x, double y, double z, long despawnTicks) {
        super(x, y, z);
        this.despawnTicks = Math.max(1L, despawnTicks);
    }

    @Override
    protected void onTick(long tickNumber) {
        ageTicks++;
        if (ageTicks >= despawnTicks) {
            kill();
        }
    }

    public boolean canBePickedUp() {
        return isAlive();
    }

    @Override
    protected EntityTier defaultTier() {
        return EntityTier.STANDARD;
    }
}
