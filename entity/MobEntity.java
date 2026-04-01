package dev.cinder.entity;

import dev.cinder.entity.EntityUpdatePipeline.EntityTier;
import dev.cinder.entity.ai.GoalEvaluator;

/**
 * Base mob entity with pluggable goal evaluation.
 */
public class MobEntity extends CinderEntity {

    private GoalEvaluator goalEvaluator;

    public MobEntity(double x, double y, double z, GoalEvaluator goalEvaluator) {
        super(x, y, z);
        this.goalEvaluator = goalEvaluator;
        setTier(EntityTier.STANDARD);
    }

    public MobEntity(double x, double y, double z) {
        this(x, y, z, (entity, tick) -> {
        });
    }

    public void setGoalEvaluator(GoalEvaluator goalEvaluator) {
        this.goalEvaluator = goalEvaluator;
    }

    @Override
    protected void onTick(long tickNumber) {
        if (goalEvaluator != null) {
            goalEvaluator.evaluate(this, tickNumber);
        }
    }

    @Override
    protected EntityTier defaultTier() {
        return EntityTier.STANDARD;
    }
}
