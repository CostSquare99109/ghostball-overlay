package com.johan.ghostball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [BallDetector] using synthetic ARGB buffers.
 * No android.* classes — runs in plain JUnit on CI without Robolectric.
 */
class BallDetectorTest {

    private val FELT = 0xFF2E7D32.toInt()

    private fun makeImage(width: Int, height: Int, fill: (Int, Int) -> Int): BallImage {
        val px = IntArray(width * height) { i -> fill(i % width, i / width) }
        return BallImage(width, height, px)
    }

    private fun withBall(image: BallImage, cx: Float, cy: Float, r: Int, color: Int): BallImage {
        val px = image.argb.copyOf()
        val w = image.width
        val h = image.height
        val r2 = r * r
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= r2) px[y * w + x] = color
            }
        }
        return BallImage(w, h, px)
    }

    private fun detect(image: BallImage, radius: Float, felt: Int? = FELT): List<DetectedBall> =
        BallDetector(ballRadiusPx = radius, feltColor = felt).detect(image)

    @Test
    fun whiteBallOnly_isDetectedAndClassifiedAsCue() {
        val img = withBall(makeImage(400, 300) { _, _ -> FELT }, 200f, 150f, 20, 0xFFFFFFFF.toInt())
        val balls = detect(img, 20f)

        assertEquals(1, balls.size)
        assertTrue(balls[0].isCueBall)
        assertEquals(200f, balls[0].x, 2f)
        assertEquals(150f, balls[0].y, 2f)
        assertEquals(0xFFFFFFFF.toInt(), balls[0].avgColor)
    }

    @Test
    fun whitePlusColored_twoBalls_exactlyOneCue() {
        var img = makeImage(400, 300) { _, _ -> FELT }
        img = withBall(img, 150f, 150f, 20, 0xFFFFFFFF.toInt())
        img = withBall(img, 250f, 150f, 20, 0xFFE53935.toInt())

        val balls = detect(img, 20f)
        assertEquals(2, balls.size)

        val cue = balls.filter { it.isCueBall }
        assertEquals(1, cue.size)
        assertEquals(150f, cue[0].x, 2f) // white one is the cue

        val obj = balls.first { !it.isCueBall }
        assertEquals(250f, obj.x, 2f)
    }

    @Test
    fun noBalls_returnsEmptyList() {
        val img = makeImage(400, 300) { _, _ -> FELT }
        assertTrue(detect(img, 20f).isEmpty())
    }

    @Test
    fun feltColorAutoSampledFromCenter_whenNotSupplied() {
        // Ball off-center; detector must infer felt from the central region.
        val img = withBall(makeImage(400, 300) { _, _ -> FELT }, 80f, 80f, 20, 0xFFFFFFFF.toInt())
        val balls = BallDetector(ballRadiusPx = 20f, feltColor = null).detect(img)

        assertEquals(1, balls.size)
        assertTrue(balls[0].isCueBall)
    }

    @Test
    fun rectangularBlob_rejectedByShape() {
        val px = IntArray(400 * 300) { FELT }
        for (y in 130..170) {
            for (x in 100..220) px[y * 400 + x] = 0xFFFFFFFF.toInt()
        }
        assertTrue(detect(BallImage(400, 300, px), 20f).isEmpty())
    }

    @Test
    fun tinyNoiseBlob_rejectedByArea() {
        val px = IntArray(400 * 300) { FELT }
        px[10 * 400 + 10] = 0xFFFFFFFF.toInt()
        px[10 * 400 + 11] = 0xFFFFFFFF.toInt()
        assertTrue(detect(BallImage(400, 300, px), 20f).isEmpty())
    }

    @Test
    fun oversizedBlob_rejectedByArea() {
        val px = IntArray(400 * 300) { FELT }
        val r = 45 // radius way above the 20px calibration
        for (y in 0 until 300) {
            for (x in 0 until 400) {
                val dx = x - 200f
                val dy = y - 150f
                if (dx * dx + dy * dy <= r * r) px[y * 400 + x] = 0xFFFFFFFF.toInt()
            }
        }
        assertTrue(detect(BallImage(400, 300, px), 20f).isEmpty())
    }

    @Test
    fun detect_downscalesLargerThanMaxAnalysisDim() {
        var img = makeImage(800, 600) { _, _ -> FELT }
        img = withBall(img, 400f, 300f, 40, 0xFFFFFFFF.toInt())
        // Default maxAnalysisDim = 480 → 800x600 becomes 480x360; radius 40 → 24.
        val balls = detect(img, 40f)

        assertEquals(1, balls.size)
        // Center must map back to the ORIGINAL coordinate space.
        assertEquals(400f, balls[0].x, 4f)
        assertEquals(300f, balls[0].y, 4f)
        assertTrue(balls[0].isCueBall)
    }

    @Test
    fun downscale_returnsSameInstance_whenAlreadySmall() {
        val img = makeImage(200, 150) { _, _ -> FELT }
        val down = BallDetector.downscale(img, 480)
        assertTrue(down === img)
    }

    @Test
    fun twoColoredBalls_noCueClassified() {
        var img = makeImage(400, 300) { _, _ -> FELT }
        img = withBall(img, 150f, 150f, 20, 0xFFE53935.toInt()) // red
        img = withBall(img, 250f, 150f, 20, 0xFF1E88E5.toInt()) // blue

        val balls = detect(img, 20f)
        assertEquals(2, balls.size)
        assertTrue(balls.none { it.isCueBall })
    }
}