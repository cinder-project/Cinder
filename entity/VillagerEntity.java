package dev.cinder.entity;

/**
 * Villager entity stub with low-motion idle behavior.
 */
public final class VillagerEntity extends MobEntity {

    public VillagerEntity(double x, double y, double z) {
        super(x, y, z, (entity, tick) -> {
            if (tick % 200 == 0) {
                entity.setRotation((float) ((tick % 360) * 1.0), entity.getPitch());
            }
        });
    }
}
