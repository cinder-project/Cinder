package dev.cinder.entity.ai;

import dev.cinder.entity.CinderEntity;

/**
 * Evaluates and applies AI goals for a mob entity.
 */
@FunctionalInterface
public interface GoalEvaluator {
    void evaluate(CinderEntity entity, long tickNumber);
}
