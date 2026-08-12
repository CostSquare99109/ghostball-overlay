package com.johan.ghostball

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ShotCalculator].
 * Runs on the JVM (no Android runtime) — `./gradlew testDebugUnitTest`.
 *
 * Three required cases:
 *  1. Direct shot (straight pocket).
 *  2. Bank shot (single rail).
 *  3. Extreme cut angle (> 78°) still produces a valid result.
 */
class ShotCalculatorTest {

    private val EPS = 0.5f // degrees tolerance for angles

    /** Point helper for readability. */
    private fun p(x: Float, y: Float) = ShotCalculator.Point(x, y)

    /** Pocket helper. */
    private fun pk(x: Float, y: Float, name: String = "Test") = ShotCalculator.Pocket(x, y, name)

    private fun table(w: Float, h: Float) = ShotCalculator.Rect(0f, 0f, w, h)

    @Test
    fun directShot_basicGeometry() {
        // Cue at (100, 100), object at (200, 100), pocket at (300, 100).
        // Perfect straight line → 0° cut.
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)
        val pocket = pk(300f, 100f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table(400f, 200f))
        assertFalse("Should have found at least one direct shot", shots.isEmpty())

        val best = shots[0]
        assertTrue("Best shot must be direct", best is ShotCalculator.Shot.Direct)
        val direct = best as ShotCalculator.Shot.Direct

        // Cut angle should be near 0°.
        assertEquals("Straight shot = 0° cut", 0f, direct.cutAngleDeg, 0.01f)

        // Ghost ball should be behind the object ball (one diameter back).
        val expectedGhostX = obj.x - 2f * ShotCalculator.DEFAULT_BALL_RADIUS
        assertEquals("Ghost ball one diameter behind object", expectedGhostX, direct.ghostBall.x, 0.01f)
    }

    @Test
    fun directShot_cutAngleCorrect() {
        // Cue at (100, 100), object at (200, 100), pocket at (200, 200).
        // cue→obj is along +X, obj→pocket is along +Y → 90° cut.
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)
        val pocket = pk(200f, 200f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table(400f, 400f))
        assertFalse(shots.isEmpty())

        val best = shots[0] as ShotCalculator.Shot.Direct
        // Vector from cue to obj: (100, 0). Vector from obj to pocket: (0, 100).
        // Dot = 0 → acos(0) = 90°.
        assertEquals("90° cut", 90f, best.cutAngleDeg, EPS)
    }

    @Test
    fun bankShot_singleRail() {
        // Cue at (100, 100), object at (200, 150).
        // Pocket at (350, 150) is blocked by cushion at top (y=0).
        // Bank off the top rail (y=0) → pocket.
        val cue = p(100f, 100f)
        val obj = p(200f, 150f)
        val pocket = pk(350f, 150f)

        // Table: 0..400 x 0..300. Top rail is y=0.
        val table = table(400f, 300f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)
        assertFalse(shots.isEmpty())

        // Should find a bank shot on TOP rail.
        val hasBank = shots.any { it is ShotCalculator.Shot.Bank && it.rail == ShotCalculator.Rail.TOP }
        assertTrue("Should find a bank shot off the top rail", hasBank)

        val bank = shots.first { it is ShotCalculator.Shot.Bank } as ShotCalculator.Shot.Bank
        assertEquals("Bank off top rail", ShotCalculator.Rail.TOP, bank.rail)

        // Impact point must be on the top rail (y ≈ 0).
        assertEquals("Bank hit on y=0", 0f, bank.impactPoint.y, 1f)
    }

    @Test
    fun bankShot_prefersDirectWhenWithinPenalty() {
        // Set up a shot where direct and bank have similar angles.
        // Direct cut = 20°, Bank cut = 15° + 10° penalty = 25° → direct should win.
        val cue = p(50f, 50f)
        val obj = p(100f, 100f)
        val pocket = pk(200f, 150f)

        val table = table(300f, 200f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)
        assertFalse(shots.isEmpty())

        val best = shots[0]
        // Direct should be ranked first because bank penalty (10°) pushes it above.
        assertTrue("Direct should beat bank when within penalty", best is ShotCalculator.Shot.Direct)
    }

    @Test
    fun extremeCutAngle_over78_degrees() {
        // Cue at (100, 100), object at (200, 100).
        // Pocket far to the side so cut > 78°.
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)
        // Pocket at (200, 300) → cue→obj = (100,0), obj→pocket = (0,200) → 90°.
        // Use (200, 250) → obj→pocket = (0,150) → angle = 90°.
        // To get ~80°: obj→pocket should have small x component.
        // cue→obj = (100, 0). obj→pocket = (x, y). tan(80°) = x/y → x = y * tan(80°) ≈ 5.67y.
        // For y = 10, x ≈ 57. Use pocket = (200+57, 100+10) = (257, 110) → ~80°.
        val pocket = pk(257f, 110f)

        val table = table(400f, 200f)
        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)
        assertFalse("Should still produce a shot even for extreme cut", shots.isEmpty())

        val best = shots[0] as ShotCalculator.Shot.Direct
        assertTrue("Cut angle should be > 78°", best.cutAngleDeg > 78f)
        assertEquals("Should be close to ~80°", 80f, best.cutAngleDeg, 2f)

        // Ghost ball still valid.
        assertTrue("Ghost ball x should be behind object", best.ghostBall.x < obj.x)
    }

    @Test
    fun multiplePockets_returnsSortedByScore() {
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)

        // Three pockets: one straight (0°), one 45°, one 90°.
        val pockets = listOf(
            pk(300f, 100f, "Straight"),   // 0°
            pk(300f, 200f, "FortyFive"),  // 45°
            pk(200f, 200f, "Ninety")      // 90°
        )

        val table = table(400f, 300f)
        val shots = ShotCalculator.compute(cue, obj, pockets, table)

        // First should be straight.
        assertEquals("First = straight", "Straight", shots[0].pocket.name)
        assertEquals("Second = 45°", "FortyFive", shots[1].pocket.name)
        assertEquals("Third = 90°", "Ninety", shots[2].pocket.name)
    }

    @Test
    fun bankImpactInsideTableBounds() {
        // Cue near left, object center, pocket top-right.
        // Bank off left rail should place impact inside the table.
        val cue = p(20f, 150f)
        val obj = p(150f, 150f)
        val pocket = pk(280f, 20f)

        val table = table(300f, 200f)
        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)

        val leftBank = shots.firstOrNull { it is ShotCalculator.Shot.Bank && it.rail == ShotCalculator.Rail.LEFT }
        assertNotNull("Should find left-rail bank", leftBank)

        val bank = leftBank as ShotCalculator.Shot.Bank
        assertTrue("Impact X on left rail ≈ 0", bank.impactPoint.x < 2f)
        assertTrue("Impact Y inside table", bank.impactPoint.y in 0f..table.height)
    }

    @Test
    fun noShotsWhenCueEqualsObject() {
        val cue = p(100f, 100f)
        val obj = p(100f, 100f)
        val pocket = pk(200f, 200f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table(300f, 300f))
        assertTrue("Degenerate cue=obj yields no shots", shots.isEmpty())
    }
}