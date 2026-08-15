package com.johan.ghostball

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Abstraction of a pixel buffer that the detector can analyze. Kept free of
 * android.graphics on purpose: unit tests build [BallImage] instances from
 * plain [IntArray] in pure JVM (no Robolectric, no Bitmap stubs).
 *
 * Pixels are ARGB (alpha ignored; detector works on RGB).
 */
data class BallImage(val width: Int, val height: Int, val argb: IntArray)

/** Integer bounding box, in the pixel coordinates of the analyzed image. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * A ball found by [BallDetector]. Coordinates are in the pixel space of the
 * [BallImage] passed to [BallDetector.detect] — the caller scales them back to
 * screen coordinates.
 */
data class DetectedBall(
    val x: Float,
    val y: Float,
    val bbox: IntRect,
    val avgColor: Int,
    val isCueBall: Boolean
)

/**
 * All tunable thresholds of the detector. These are deliberately parameters —
 * ball detection on a screenshot is skin/theme-dependent, and the user should
 * be able to tighten them per game without touching the algorithm.
 *
 * User-approved note: `fillMin..fillMax` is wide (0.55..0.95) on purpose for
 * v1; if the game UI (scoreboard, buttons) produces false positives, this is
 * the first knob to tighten.
 */
data class DetectorConfig(
    val feltDistThreshold: Float = 60f,
    val minSaturation: Float = 0.20f,
    val minValue: Float = 0.15f,
    val aspectMin: Float = 0.85f,
    val aspectMax: Float = 1.15f,
    val fillMin: Float = 0.55f,
    val fillMax: Float = 0.95f,
    val areaMinFactor: Float = 0.7f,
    val areaMaxFactor: Float = 1.3f,
    val cueValueMin: Float = 0.55f,
    val cueSatMax: Float = 0.35f,
    val maxAnalysisDim: Int = 480
)

/**
 * Pure-Kotlin ball detector (no OpenCV, no NDK, no android.* imports).
 *
 * Pipeline:
 *  1. Downscale the image to [DetectorConfig.maxAnalysisDim] if larger.
 *  2. HSV mask: pixels far from the felt color (supplied or auto-sampled)
 *     become ball candidates.
 *  3. Connected-component labeling (8-connectivity) on the mask.
 *  4. Shape filter: bounding-box aspect, disk fill ratio and area must match
 *     a circle of [ballRadiusPx].
 *  5. Cue-ball classification: brightest + least saturated surviving blob.
 *
 * Conservative by design: false negatives are preferred over false positives,
 * and no cue ball is reported when none is confidently white.
 */
class BallDetector(
    private val ballRadiusPx: Float,
    private val feltColor: Int? = null,
    private val config: DetectorConfig = DetectorConfig()
) {

    fun detect(image: BallImage): List<DetectedBall> {
        require(ballRadiusPx > 0f) { "ballRadiusPx must be positive" }
        val working = BallDetector.downscale(image, config.maxAnalysisDim)
        val scale = working.width.toFloat() / image.width.toFloat()
        val workingRadius = ballRadiusPx * scale
        val felt = feltColor ?: BallDetector.sampleFeltColor(working)

        val blobs = connectedComponents(working, felt)
        val valid = blobs.filter { passesShape(it, workingRadius) }
        val cueIdx = classifyCueBall(valid)
        val inv = 1f / scale

        return valid.mapIndexed { i, b ->
            DetectedBall(
                x = (b.minX + b.maxX) / 2f * inv,
                y = (b.minY + b.maxY) / 2f * inv,
                bbox = IntRect(
                    (b.minX * inv).toInt(),
                    (b.minY * inv).toInt(),
                    (b.maxX * inv).toInt(),
                    (b.maxY * inv).toInt()
                ),
                avgColor = b.avgColor,
                isCueBall = i == cueIdx
            )
        }
    }

    // ---------------- segmentation / labeling ----------------

    private class Blob {
        var minX: Int = Int.MAX_VALUE
        var maxX: Int = Int.MIN_VALUE
        var minY: Int = Int.MAX_VALUE
        var maxY: Int = Int.MIN_VALUE
        var area: Int = 0
        var sumR: Long = 0
        var sumG: Long = 0
        var sumB: Long = 0
        var sumS: Float = 0f
        var sumV: Float = 0f

        val avgColor: Int
            get() {
                if (area == 0) return 0
                return 0xFF000000.toInt() or
                    ((sumR / area).toInt() shl 16) or
                    ((sumG / area).toInt() shl 8) or
                    (sumB / area).toInt()
            }

        val avgSaturation: Float get() = if (area > 0) sumS / area else 0f
        val avgValue: Float get() = if (area > 0) sumV / area else 0f
    }

    /** Binary mask + BFS labeling (8-connectivity). Returns all candidate blobs. */
    private fun connectedComponents(image: BallImage, felt: Int): List<Blob> {
        val w = image.width
        val h = image.height
        val n = w * h
        val mask = BooleanArray(n)

        val fR = (felt shr 16) and 0xFF
        val fG = (felt shr 8) and 0xFF
        val fB = felt and 0xFF

        for (y in 0 until h) {
            var base = y * w
            for (x in 0 until w) {
                val c = image.argb[base + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val dr = r - fR
                val dg = g - fG
                val db = b - fB
                val dist = sqrt((dr * dr + dg * dg + db * db).toFloat())
                val (_, s, v) = rgbToHsv(r, g, b)
                if (dist > config.feltDistThreshold &&
                    (s > config.minSaturation || v > config.minValue)
                ) {
                    mask[base + x] = true
                }
            }
        }

        val queue = IntArray(n)
        val blobs = ArrayList<Blob>()
        for (i in 0 until n) {
            if (!mask[i]) continue
            mask[i] = false
            var head = 0
            var tail = 0
            queue[tail++] = i
            val blob = Blob()

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                blob.minX = min(blob.minX, x)
                blob.maxX = max(blob.maxX, x)
                blob.minY = min(blob.minY, y)
                blob.maxY = max(blob.maxY, y)
                blob.area++
                val c = image.argb[idx]
                blob.sumR += (c shr 16) and 0xFFL
                blob.sumG += (c shr 8) and 0xFFL
                blob.sumB += (c and 0xFF).toLong()
                val (_, s, v) = rgbToHsv((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                blob.sumS += s
                blob.sumV += v

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    val yBase = ny * w
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val ni = yBase + nx
                        if (mask[ni]) {
                            mask[ni] = false
                            queue[tail++] = ni
                        }
                    }
                }
            }
            blobs.add(blob)
        }
        return blobs
    }

    // ---------------- shape filters ----------------

    private fun passesShape(b: Blob, r: Float): Boolean {
        val bw = (b.maxX - b.minX + 1).toFloat()
        val bh = (b.maxY - b.minY + 1).toFloat()
        if (bw <= 0f || bh <= 0f) return false

        val aspect = bw / bh
        if (aspect < config.aspectMin || aspect > config.aspectMax) return false

        val fill = b.area / (bw * bh)
        if (fill < config.fillMin || fill > config.fillMax) return false

        val expectedArea = PI.toFloat() * r * r
        val a = b.area.toFloat()
        return a >= expectedArea * config.areaMinFactor &&
            a <= expectedArea * config.areaMaxFactor
    }

    // ---------------- cue-ball classification ----------------

    /**
     * Returns the index into [blobs] of the cue ball, or -1 if none qualifies.
     * White = high value (brightness) + low saturation. If several blobs pass
     * the thresholds, the `value - 2*saturation` leader wins.
     */
    private fun classifyCueBall(blobs: List<Blob>): Int {
        var best = -1
        var bestScore = -1f
        for (i in blobs.indices) {
            val b = blobs[i]
            if (b.avgValue > config.cueValueMin && b.avgSaturation < config.cueSatMax) {
                val score = b.avgValue - 2f * b.avgSaturation
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
        }
        return best
    }

    // ---------------- color helpers ----------------

    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val d = max - min
        val hue = when {
            d == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / d) % 6f)
            max == gf -> 60f * ((bf - rf) / d + 2f)
            else -> 60f * ((rf - gf) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val sat = if (max == 0f) 0f else d / max
        return Triple(hue, sat, max)
    }

    companion object {

        /**
         * Nearest-neighbor downscale so analysis stays cheap on full-resolution
         * screenshots. Returns the input unchanged when already small enough.
         */
        fun downscale(image: BallImage, maxDim: Int): BallImage {
            val largest = max(image.width, image.height)
            if (largest <= maxDim) return image
            val scale = maxDim.toFloat() / largest
            val nw = max(1, (image.width * scale).toInt())
            val nh = max(1, (image.height * scale).toInt())
            val out = IntArray(nw * nh)
            val xStep = image.width.toDouble() / nw
            val yStep = image.height.toDouble() / nh
            for (y in 0 until nh) {
                val sy = (y * yStep).toInt().coerceAtMost(image.height - 1)
                val srcBase = sy * image.width
                val dstBase = y * nw
                for (x in 0 until nw) {
                    val sx = (x * xStep).toInt().coerceAtMost(image.width - 1)
                    out[dstBase + x] = image.argb[srcBase + sx]
                }
            }
            return BallImage(nw, nh, out)
        }

        /**
         * Average ARGB of the central ~25% of the image — used as the felt
         * color when the user/game hasn't supplied one. Balls near the center
         * are rare enough that this is a safe estimate.
         */
        fun sampleFeltColor(image: BallImage): Int {
            val w = image.width
            val h = image.height
            val x0 = (w * 0.375f).toInt()
            val x1 = (w * 0.625f).toInt().coerceAtLeast(x0 + 1)
            val y0 = (h * 0.375f).toInt()
            val y1 = (h * 0.625f).toInt().coerceAtLeast(y0 + 1)
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0L
            for (y in y0..y1) {
                val base = y * w
                for (x in x0..x1) {
                    val c = image.argb[base + x]
                    r += (c shr 16) and 0xFFL
                    g += (c shr 8) and 0xFFL
                    b += c and 0xFFL
                    count++
                }
            }
            if (count == 0L) return 0xFF000000.toInt()
            return 0xFF000000.toInt() or
                ((r / count).toInt() shl 16) or
                ((g / count).toInt() shl 8) or
                (b / count).toInt()
        }
    }
}