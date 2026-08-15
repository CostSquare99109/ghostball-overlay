package com.johan.ghostball

/**
 * Pure-Kotlin multi-target shot recommendation. **No Android dependencies**,
 * no Context, no View — JVM-testable like [ShotCalculator].
 *
 * Given the cue ball and every detected object ball, computes the best shot
 * per target (via [ShotCalculator.best], which already applies the bank-shot
 * penalty) and then the global recommendation: the target whose best shot has
 * the lowest score. When scores tie, the target with the lower [TargetShot.colorIndex]
 * wins (detection order — predictable and stable).
 *
 * Units follow [ShotCalculator]: the same unit as the fed-in coordinates.
 */
object TargetRecommender {

    /** One object ball + its best shot (null when no valid shot exists, e.g. cue == target). */
    data class TargetShot(
        val target: ShotCalculator.Point,
        val best: ShotCalculator.Shot?,
        val colorIndex: Int
    )

    /** All targets ranked individually + the single global recommendation. */
    data class Recommendation(
        val perTarget: List<TargetShot>,
        val globalBest: TargetShot?
    )

    /**
     * @param cue        cue ball position.
     * @param targets    every detected object ball (the cue ball excluded).
     * @param pockets    pocket list; empty is valid (yields no shots).
     * @param table      table rectangle (bank validation).
     * @param ballRadius in the same unit as the points.
     */
    fun recommend(
        cue: ShotCalculator.Point,
        targets: List<ShotCalculator.Point>,
        pockets: List<ShotCalculator.Pocket>,
        table: ShotCalculator.Rect,
        ballRadius: Float = ShotCalculator.DEFAULT_BALL_RADIUS
    ): Recommendation {
        val perTarget = targets.mapIndexed { i, t ->
            TargetShot(
                target = t,
                best = ShotCalculator.best(cue, t, pockets, table, ballRadius),
                colorIndex = i
            )
        }
        val globalBest = perTarget
            .filter { it.best != null }
            .minWithOrNull(compareBy({ it.best!!.score }, { it.colorIndex }))
        return Recommendation(perTarget, globalBest)
    }
}