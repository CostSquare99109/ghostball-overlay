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
        // cue→obj is along +X (100, 0), obj→pocket is along +Y (0, 100) → 90° cut.
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)
        val pocket = pk(200f, 200f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table(400f, 400f))
        assertFalse("Should find at least one shot", shots.isEmpty())

        // Filter to direct shots only - banks have 20° penalty.
        val directShots = shots.filter { it is ShotCalculator.Shot.Direct }
        assertFalse("Should have direct shots", directShots.isEmpty())

        val best = directShots[0] as ShotCalculator.Shot.Direct
        assertEquals("90° cut", 90f, best.cutAngleDeg, EPS)
    }

    @Test
    fun bankShot_singleRail_topRail() {
        // Cue at (50, 150), object at (150, 150) (center horizontal).
        // Pocket at (250, 50) - top-right corner.
        // Bank off TOP rail (y=0): mirror pocket across y=0 → (250, -50).
        // Line obj(150,150) → mirror(250,-50) crosses y=0 at some x between 150 and 250.
        // Vector obj→pocket = (100, -100). obj→impact must have positive dot with this.
        val cue = p(50f, 150f)
        val obj = p(150f, 150f)
        val pocket = pk(250f, 50f)

        // Table: 0..300 x 0..200. Top rail is y=0.
        val table = table(300f, 200f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)
        assertFalse("Should find shots", shots.isEmpty())

        // Should find a bank shot on TOP rail.
        val topBank = shots.firstOrNull { it is ShotCalculator.Shot.Bank && it.rail == ShotCalculator.Rail.TOP }
        assertNotNull("Should find a bank shot off the top rail", topBank)

        val bank = topBank as ShotCalculator.Shot.Bank
        assertEquals("Bank off top rail", ShotCalculator.Rail.TOP, bank.rail)

        // Impact point must be on the top rail (y ≈ 0).
        assertEquals("Bank hit on y=0", 0f, bank.impactPoint.y, 1f)
        // Impact x should be between obj.x (150) and pocket.x (250) roughly.
        assertTrue("Impact X between object and pocket", bank.impactPoint.x in 150f..250f)
    }

    @Test
    fun bankShot_prefersDirectWhenWithinPenalty() {
        // Direct cut = 20°, Bank cut = 15° + 20° penalty = 35° → direct should win.
        val cue = p(50f, 50f)
        val obj = p(100f, 100f)
        val pocket = pk(200f, 130f) // Direct ~20° cut.

        val table = table(300f, 200f)

        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)
        assertFalse(shots.isEmpty())

        val best = shots[0]
        // Direct should be ranked first because bank penalty (20°) pushes it above.
        assertTrue("Direct should beat bank when within penalty", best is ShotCalculator.Shot.Direct)
    }

    @Test
    fun extremeCutAngle_over78_degrees() {
        // Cue at (100, 100), object at (200, 100).
        // Need pocket such that cue→obj = (100, 0) and obj→pocket has very small x component.
        // To get ~80°: cos(80°) = 0.1736. x/sqrt(x²+y²) = 0.1736 → y ≈ 5.67*x
        // Pick x = 10, y = 57 → pocket = (210, 157).
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)
        val pocket = pk(210f, 157f)

        val table = table(400f, 200f)
        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)
        assertFalse("Should still produce a shot even for extreme cut", shots.isEmpty())

        // With bank penalty of 20°, direct ~80° should win over any bank (>= 20° penalty + bank angle).
        val best = shots[0]
        assertTrue("Best shot should be direct for this geometry", best is ShotCalculator.Shot.Direct)
        val direct = best as ShotCalculator.Shot.Direct

        assertTrue("Cut angle should be > 78°", direct.cutAngleDeg > 78f)
        assertEquals("Should be close to ~80°", 80f, direct.cutAngleDeg, 2f)

        // Ghost ball still valid (ghost.x < obj.x since pocket is to the right/up).
        assertTrue("Ghost ball x should be behind object", direct.ghostBall.x < obj.x + 1f)
    }

    @Test
    fun multiplePockets_returnsSortedByScore() {
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)

        // Three pockets:
        // 1. (300, 100) → straight, 0°
        // 2. (300, 200) → obj→pocket = (100, 100), cue→obj = (100, 0) → 45°
        // 3. (200, 200) → obj→pocket = (0, 100), cue→obj = (100, 0) → 90°
        val pockets = listOf(
            pk(300f, 100f, "Straight"),
            pk(300f, 200f, "FortyFive"),
            pk(200f, 200f, "Ninety")
        )

        val table = table(400f, 300f)
        val shots = ShotCalculator.compute(cue, obj, pockets, table)

        // Filter to direct shots only (banks have 20° penalty).
        val directShots = shots.filter { it is ShotCalculator.Shot.Direct }
        assertEquals("Should have 3 direct shots", 3, directShots.size)

        // First should be straight (0°).
        assertEquals("First = straight (0°)", "Straight", directShots[0].pocket.name)
        // Second should be 45°.
        assertEquals("Second = 45°", "FortyFive", directShots[1].pocket.name)
        // Third should be 90°.
        assertEquals("Third = 90°", "Ninety", directShots[2].pocket.name)
    }

    @Test
    fun bankImpactInsideTableBounds() {
        // Cue near left (50, 100), object center (150, 100), pocket top-right (280, 20).
        // Bank off LEFT rail (x=0): mirror pocket across x=0 → (-280, 20).
        // Line obj(150,100) → mirror(-280,20) crosses x=0 at some y.
        val cue = p(50f, 100f)
        val obj = p(150f, 100f)
        val pocket = pk(280f, 20f)

        val table = table(300f, 200f)
        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)

        val leftBank = shots.firstOrNull { it is ShotCalculator.Shot.Bank && it.rail == ShotCalculator.Rail.LEFT }
        assertNotNull("Should find left-rail bank", leftBank)

        val bank = leftBank as ShotCalculator.Shot.Bank
        assertTrue("Impact X on left rail ≈ 0", bank.impactPoint.x < 1f)
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

    @Test
    fun bankShot_rejectsWrongDirection() {
        // If mirror causes impact to go "away" from real pocket, it should be rejected.
        // Cue (100, 100), Obj (200, 100), Pocket (250, 100) — pocket is to the right.
        // Bank off LEFT rail: mirror pocket across x=0 → (-250, 100).
        // Line obj(200,100) → mirror(-250,100) is horizontal left, crosses x=0 at y=100.
        // obj→impact = (-200, 0). obj→pocket = (50, 0). Dot = -10000 < 0 → rejected.
        val cue = p(100f, 100f)
        val obj = p(200f, 100f)
        val pocket = pk(250f, 100f)

        val table = table(300f, 200f)
        val shots = ShotCalculator.compute(cue, obj, listOf(pocket), table)

        // Should only have direct shot (0° cut), no left-rail bank.
        val leftBank = shots.firstOrNull { it is ShotCalculator.Shot.Bank && it.rail == ShotCalculator.Rail.LEFT }
        assertNull("Left-rail bank should be rejected (wrong direction)", leftBank)

        val direct = shots.firstOrNull { it is ShotCalculator.Shot.Direct }
        assertNotNull("Direct shot should exist", direct)
        assertEquals("Straight shot = 0°", 0f, (direct as ShotCalculator.Shot.Direct).cutAngleDeg, 0.01f)
    }
}