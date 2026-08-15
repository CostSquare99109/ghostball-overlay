package com.johan.ghostball

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [TargetRecommender] (v6 multi-target global recommendation).
 * Runs on the JVM (no Android runtime) — `./gradlew testDebugUnitTest`.
 */
class TargetRecommenderTest {

    private fun p(x: Float, y: Float) = ShotCalculator.Point(x, y)
    private fun pk(x: Float, y: Float, name: String = "T") = ShotCalculator.Pocket(x, y, name)
    private fun table(w: Float, h: Float) = ShotCalculator.Rect(0f, 0f, w, h)

    @Test
    fun singleTarget_isGlobalBest() {
        val cue = p(100f, 100f)
        val t = p(200f, 100f)
        val pocket = pk(300f, 100f)

        val rec = TargetRecommender.recommend(cue, listOf(t), listOf(pocket), table(400f, 200f))
        assertEquals("One target → one entry", 1, rec.perTarget.size)
        assertNotNull("Target must have a best shot", rec.perTarget[0].best)
        assertNotNull("Single target is the global best", rec.globalBest)
        assertEquals(t, rec.globalBest!!.target)
        assertEquals(0, rec.globalBest!!.colorIndex)
    }

    @Test
    fun threeTargetsKnownAngles_globalBestIsLowestScore() {
        // Cue (100,100), single pocket (300,100). Targets on the cue→pocket line:
        //  tC (200,100) → 0° cut; tB (200,110) → ~11.4°; tA (200,140) → ~43.6°.
        val cue = p(100f, 100f)
        val pocket = pk(300f, 100f)
        val tA = p(200f, 140f)
        val tB = p(200f, 110f)
        val tC = p(200f, 100f)

        val rec = TargetRecommender.recommend(
            cue, listOf(tA, tB, tC), listOf(pocket), table(400f, 200f)
        )
        assertEquals(3, rec.perTarget.size)
        assertNotNull(rec.globalBest)
        assertEquals("Lowest cut angle must win", tC, rec.globalBest!!.target)
        assertEquals(2, rec.globalBest!!.colorIndex)

        // Scores must be strictly increasing along the expected ranking.
        val scores = rec.perTarget.map { it.best!!.score }
        val winnerScore = rec.globalBest!!.best!!.score
        assertTrue("Winner is the minimum score", scores.all { it >= winnerScore })
    }

    @Test
    fun equalScores_tieBrokenByLowerColorIndex() {
        val cue = p(100f, 100f)
        val pocket = pk(380f, 100f)
        val t1 = p(200f, 100f) // straight, 0°
        val t2 = p(300f, 100f) // straight, 0°

        val rec = TargetRecommender.recommend(cue, listOf(t1, t2), listOf(pocket), table(400f, 200f))
        assertNotNull(rec.globalBest)
        assertEquals("Tie → first in detection order", t1, rec.globalBest!!.target)
        assertEquals(0, rec.globalBest!!.colorIndex)
    }

    @Test
    fun noTargets_returnsEmptyWithoutCrash() {
        val rec = TargetRecommender.recommend(
            p(100f, 100f), emptyList(), listOf(pk(200f, 200f)), table(300f, 300f)
        )
        assertTrue(rec.perTarget.isEmpty())
        assertNull("No targets → no global best", rec.globalBest)
    }

    @Test
    fun cueEqualsTarget_skippedButOthersWork() {
        val cue = p(100f, 100f)
        val pocket = pk(300f, 100f)
        val good = p(200f, 100f)

        val rec = TargetRecommender.recommend(
            cue, listOf(cue, good), listOf(pocket), table(400f, 200f)
        )
        assertEquals(2, rec.perTarget.size)
        assertNull("cue == target → degenerate, no shot", rec.perTarget[0].best)
        assertNotNull("Other target still computed", rec.perTarget[1].best)
        assertEquals("Global best skips the degenerate one", good, rec.globalBest!!.target)
    }

    @Test
    fun allDegenerate_returnsNullGlobalBest() {
        val cue = p(100f, 100f)
        val rec = TargetRecommender.recommend(
            cue, listOf(cue, cue), listOf(pk(200f, 200f)), table(300f, 300f)
        )
        assertEquals(2, rec.perTarget.size)
        assertTrue(rec.perTarget.all { it.best == null })
        assertNull(rec.globalBest)
    }

    @Test
    fun colorIndex_assignedInTargetOrder() {
        val cue = p(100f, 100f)
        val targets = listOf(p(200f, 100f), p(220f, 120f), p(180f, 130f))

        val rec = TargetRecommender.recommend(
            cue, targets, listOf(pk(300f, 100f)), table(400f, 200f)
        )
        assertEquals(listOf(0, 1, 2), rec.perTarget.map { it.colorIndex })
    }

    @Test
    fun emptyPockets_returnsNullGlobalBest() {
        val rec = TargetRecommender.recommend(
            p(100f, 100f), listOf(p(200f, 100f)), emptyList(), table(400f, 200f)
        )
        assertEquals(1, rec.perTarget.size)
        assertNull("No pockets → no valid shot", rec.perTarget[0].best)
        assertNull(rec.globalBest)
    }

    @Test
    fun bankPenalty_prefersDirectWithinPenalty() {
        val cue = p(50f, 50f)
        val t = p(100f, 100f)
        val pocket = pk(200f, 130f)

        val rec = TargetRecommender.recommend(
            cue, listOf(t), listOf(pocket), table(300f, 200f)
        )
        val best = rec.perTarget[0].best
        assertNotNull(best)
        assertTrue("Direct shot beats bank within the penalty margin", best is ShotCalculator.Shot.Direct)
    }

    @Test
    fun palette_cyclesBeyondSize() {
        val n = TargetPalette.colors.size
        assertTrue("Palette must have 10+ colors", n >= 10)
        assertEquals("Cycles back to the first color", TargetPalette.colors[0], TargetPalette.color(n))
        assertEquals(TargetPalette.colors[1], TargetPalette.color(n + 1))
        assertEquals(TargetPalette.colors[n - 1], TargetPalette.color(2 * n - 1))
    }
}