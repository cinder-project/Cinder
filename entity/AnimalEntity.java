package dev.cinder.entity;

import dev.cinder.entity.ai.GoalEvaluator;

/**
 * Passive animal entity with simple wander behavior stub.
 */
public final class AnimalEntity extends MobEntity {

    public AnimalEntity(double x, double y, double z) {
        super(x, y, z, new WanderGoalEvaluator(0.05));
    }

    private static final class WanderGoalEvaluator implements GoalEvaluator {
        private final double amplitude;

        private WanderGoalEvaluator(double amplitude) {
            this.amplitude = amplitude;
        }

        @Override
        public void evaluate(CinderEntity entity, long tickNumber) {
            double nx = entity.getX() + (Math.sin(tickNumber * 0.05) * amplitude);
            double nz = entity.getZ() + (Math.cos(tickNumber * 0.05) * amplitude);
            entity.setPosition(nx, entity.getY(), nz);
        }
    }
}
