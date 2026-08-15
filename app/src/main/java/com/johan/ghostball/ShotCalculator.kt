package com.johan.ghostball

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure-Kotlin billiard geometry. **No Android dependencies**, no Context, no View.
 * Tested directly with JUnit on the JVM.
 *
 * Method:
 *  - Ghost-ball: aim cue at the point where a ball with radius [BALL_RADIUS] placed behind
 *    the object ball would travel toward the pocket.
 *  - Bank shots: reflect the pocket across one rail and use the reflected target as a
 *    pseudo-pocket; the real rail hit point is the intersection of O→T' with that rail.
 *    Only banks where the impact point lies *between* object and reflected pocket AND
 *    the object→impact vector points generally toward the real pocket are kept.
 *  - Scoring: angle of cut in degrees, plus a [BANK_PENALTY] for bank shots so direct
 *    shots are preferred when angles are within the penalty margin.
 *
 * **Units.** Everything is in the same unit as the screen coordinates fed in
 * (pixels, cm, or arbitrary — `BALL_RADIUS` is the same unit). Coordinates are
 * continuous; the caller decides how to map physical cm ↔ pixels.
 */
object ShotCalculator {

    /** Reference ball radius. Caller can override via [compute]. */
    const val DEFAULT_BALL_RADIUS: Float = 2.85f

    /** Penalty added to bank-shot score so direct shots are preferred when tied within ~20°. */
    const val BANK_PENALTY: Float = 20f

    /** Epsilon for geometric comparisons. */
    private const val EPS: Float = 1e-6f

    data class Point(val x: Float, val y: Float) {
        fun distanceTo(other: Point): Float = hypot(other.x - x, other.y - y)
    }

    data class Pocket(val x: Float, val y: Float, val name: String) {
        fun toPoint(): Point = Point(x, y)
    }

    /** Result of one candidate shot — direct or bank. */
    sealed class Shot {
        abstract val pocket: Pocket
        abstract val ghostBall: Point
        abstract val cutAngleDeg: Float
        abstract val score: Float

        data class Direct(
            override val pocket: Pocket,
            override val ghostBall: Point,
            override val cutAngleDeg: Float,
            val cueToObject: Float,
            val objectToPocket: Float,
            override val score: Float
        ) : Shot()

        data class Bank(
            override val pocket: Pocket,
            override val ghostBall: Point,
            override val cutAngleDeg: Float,
            val cueToObject: Float,
            val objectToImpact: Float,
            val rail: Rail,
            val impactPoint: Point,
            override val score: Float
        ) : Shot()
    }

    enum class Rail { TOP, BOTTOM, LEFT, RIGHT }

    /**
     * Mirror a pocket across a rail to get a pseudo-target for the bank calculation.
     * The caller still validates that the resulting hit point lies on the rail and
     * inside the table bounds.
     */
    internal fun reflect(p: Point, rail: Rail, table: Rect): Point = when (rail) {
        Rail.TOP    -> Point(p.x, 2f * table.top    - p.y)
        Rail.BOTTOM -> Point(p.x, 2f * table.bottom - p.y)
        Rail.LEFT   -> Point(2f * table.left   - p.x, p.y)
        Rail.RIGHT  -> Point(2f * table.right  - p.x, p.y)
    }

    /**
     * Compute one ghost-ball + cut-angle pair.
     * Returns null if any of the inputs are degenerate (cue == obj, obj == pocket, etc.).
     */
    private fun angleAndGhost(
        cue: Point,
        obj: Point,
        pocket: Point,
        ballRadius: Float
    ): DirectGeometry? {
        val dx = pocket.x - obj.x
        val dy = pocket.y - obj.y
        val dObjPocket = sqrt(dx * dx + dy * dy)
        if (dObjPocket < EPS) return null

        // Ghost ball sits one diameter behind the object ball, along the line obj→pocket.
        val ghostX = obj.x - 2f * ballRadius * (dx / dObjPocket)
        val ghostY = obj.y - 2f * ballRadius * (dy / dObjPocket)

        // Cut angle between cue→obj and obj→pocket.
        val v1x = obj.x - cue.x
        val v1y = obj.y - cue.y
        val v2x = pocket.x - obj.x
        val v2y = pocket.y - obj.y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 < EPS || mag2 < EPS) return null

        val dot = v1x * v2x + v1y * v2y
        val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)
        val angleDeg = acos(cosTheta) * 180f / PI.toFloat()

        return DirectGeometry(
            ghost = Point(ghostX, ghostY),
            cutAngleDeg = angleDeg,
            cueToObject = mag1,
            objectToTarget = mag2
        )
    }

    private data class DirectGeometry(
        val ghost: Point,
        val cutAngleDeg: Float,
        val cueToObject: Float,
        val objectToTarget: Float
    )

    /**
     * Compute the 6 pockets of a pool table from two opposite corners
     * ([topLeft], [bottomRight]). The two mid-rail pockets are placed on the
     * **long** side of the table:
     *  - horizontal (width >= height) → midpoints of the top/bottom rails.
     *  - vertical (height > width)    → midpoints of the left/right rails.
     *
     * Corner order: top-left, top-right, bottom-left, bottom-right, then the
     * two mid-rail pockets. Angles are normalized regardless of corner input
     * order (swapped corners produce the same list).
     */
    fun pocketsFromRect(topLeft: Point, bottomRight: Point): List<Pocket> {
        val left = minOf(topLeft.x, bottomRight.x)
        val right = maxOf(topLeft.x, bottomRight.x)
        val top = minOf(topLeft.y, bottomRight.y)
        val bottom = maxOf(topLeft.y, bottomRight.y)
        val midX = (left + right) / 2f
        val midY = (top + bottom) / 2f

        val corners = listOf(
            Pocket(left, top, "Esquina superior izquierda"),
            Pocket(right, top, "Esquina superior derecha"),
            Pocket(left, bottom, "Esquina inferior izquierda"),
            Pocket(right, bottom, "Esquina inferior derecha"),
        )

        val mids = if (right - left >= bottom - top) {
            listOf(
                Pocket(midX, top, "Media superior"),
                Pocket(midX, bottom, "Media inferior"),
            )
        } else {
            listOf(
                Pocket(left, midY, "Media izquierda"),
                Pocket(right, midY, "Media derecha"),
            )
        }

        return corners + mids
    }

    /** A rectangular table area; ball/pocket coordinates are inside this. */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val center: Point get() = Point((left + right) / 2f, (top + bottom) / 2f)
        fun contains(p: Point): Boolean =
            p.x in left..right && p.y in top..bottom
    }

    /**
     * Compute all candidate shots, sorted by score (lower = better).
     * Direct shots come first when within [BANK_PENALTY]° of a bank.
     *
     * @param cue          cue ball position.
     * @param obj          object ball position.
     * @param pockets      pocket list. Empty list is valid (means "screen borders only"
     *                     — caller should pre-fill with screen-corner pockets if needed).
     * @param table        rectangle that defines the play area; bank hit points
     *                     outside this rectangle are discarded.
     * @param ballRadius   in the same unit as the points.
     * @param maxBounces   number of rails to consider per pocket (1 = single bank).
     */
    fun compute(
        cue: Point,
        obj: Point,
        pockets: List<Pocket>,
        table: Rect,
        ballRadius: Float = DEFAULT_BALL_RADIUS,
        maxBounces: Int = 1
    ): List<Shot> {
        if (cue.distanceTo(obj) < EPS) return emptyList()

        val results = mutableListOf<Shot>()

        // 1) Direct shots.
        pockets.forEach { pocket ->
            val direct = angleAndGhost(cue, obj, Point(pocket.x, pocket.y), ballRadius)
                ?: return@forEach
            results += Shot.Direct(
                pocket = pocket,
                ghostBall = direct.ghost,
                cutAngleDeg = direct.cutAngleDeg,
                cueToObject = direct.cueToObject,
                objectToPocket = direct.objectToTarget,
                score = direct.cutAngleDeg
            )
        }

        // 2) Bank shots — reflect every pocket across every rail, find the rail
        //    intersection, keep it only if it lies inside the table AND the impact
        //    point is in the general direction from object toward the real pocket.
        val rails = if (maxBounces >= 1) Rail.values().toList() else emptyList()
        pockets.forEach { pocket ->
            rails.forEach { rail ->
                val mirror = reflect(Point(pocket.x, pocket.y), rail, table)
                val geo = angleAndGhost(cue, obj, mirror, ballRadius) ?: return@forEach

                // Find intersection of segment obj→mirror with the rail.
                val impact: Point? = when (rail) {
                    Rail.TOP, Rail.BOTTOM -> {
                        val railY = if (rail == Rail.TOP) table.top else table.bottom
                        if (mirror.y == obj.y) null
                        else {
                            val t = (railY - obj.y) / (mirror.y - obj.y)
                            // Impact must be strictly between object and mirror (0 < t < 1).
                            if (t <= EPS || t >= 1f - EPS) null
                            else Point(obj.x + t * (mirror.x - obj.x), railY)
                        }
                    }
                    Rail.LEFT, Rail.RIGHT -> {
                        val railX = if (rail == Rail.LEFT) table.left else table.right
                        if (mirror.x == obj.x) null
                        else {
                            val t = (railX - obj.x) / (mirror.x - obj.x)
                            if (t <= EPS || t >= 1f - EPS) null
                            else Point(railX, obj.y + t * (mirror.y - obj.y))
                        }
                    }
                }

                val hit = impact ?: return@forEach
                if (!table.contains(hit)) return@forEach

                // Direction filter: object→impact vector must have positive dot product
                // with object→real-pocket vector. Rejects banks that go "away" first.
                val toPocketX = pocket.x - obj.x
                val toPocketY = pocket.y - obj.y
                val toImpactX = hit.x - obj.x
                val toImpactY = hit.y - obj.y
                if (toPocketX * toImpactX + toPocketY * toImpactY <= 0f) return@forEach

                // Skip if bank point too close to a pocket (degenerate / basically direct).
                val nearPocket = pockets.any { pk -> pk.toPoint().distanceTo(hit) < 10f }
                if (nearPocket) return@forEach

                results += Shot.Bank(
                    pocket = pocket,
                    ghostBall = geo.ghost,
                    cutAngleDeg = geo.cutAngleDeg,
                    cueToObject = geo.cueToObject,
                    objectToImpact = geo.objectToTarget,
                    rail = rail,
                    impactPoint = hit,
                    score = geo.cutAngleDeg + BANK_PENALTY
                )
            }
        }

        return results.sortedBy { it.score }
    }

    /** Convenience: returns the single best shot, or null if no candidates were valid. */
    fun best(
        cue: Point,
        obj: Point,
        pockets: List<Pocket>,
        table: Rect,
        ballRadius: Float = DEFAULT_BALL_RADIUS
    ): Shot? = compute(cue, obj, pockets, table, ballRadius).firstOrNull()
}