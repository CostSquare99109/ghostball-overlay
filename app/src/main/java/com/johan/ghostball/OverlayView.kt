package com.johan.ghostball

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Overlay drawing surface. Hosts four interaction modes:
 *
 *  - [Mode.IDLE]:          not touchable; never receives events.
 *  - [Mode.PLACEMENT]:     captures taps inside the table rectangle to place
 *                          cue/object balls; allows corner-handle drag; also
 *                          hosts detected balls (tap = isolate one target,
 *                          drag = correct position, v6: all non-cue balls are
 *                          simultaneous targets computed automatically).
 *  - [Mode.DEFINE_TABLE]:  captures a single drag (down→move→up) that defines
 *                          the table rectangle.
 *  - [Mode.CALIBRATE]:     captures two taps (ball center, then ball edge) to
 *                          compute the ball radius in px used by detection.
 *
 * All coordinates stored on this view are **screen-absolute** (rawX/rawY). The
 * owning [OverlayService] sizes this view to either the table rectangle (in
 * PLACEMENT/CALIBRATE) or the full screen (in DEFINE_TABLE or while dragging a
 * corner or detected ball), and positions it at (rect.left, rect.top). Drawing
 * must therefore subtract the view's screen origin from absolute coordinates
 * to obtain view-local canvas coordinates.
 *
 * [referencesVisible] is the eye-toggle: when false, balls/lines/ghost are not
 * painted (but kept in memory), while the table rectangle outline and handles
 * remain visible. This flag does NOT affect touchability — only [mode] does.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, PLACEMENT, DEFINE_TABLE, CALIBRATE }

    /**
     * A detected ball in screen-absolute coordinates.
     *
     * [colorIndex] is the palette slot assigned sequentially to every non-cue
     * ball (in detection order) so targets are visually distinguishable;
     * `-1` for the cue ball (white is fixed).
     */
    data class DetectedBallPos(
        val point: PointF,
        val isCueBall: Boolean,
        val colorIndex: Int = -1
    )

    /** Callbacks fired to the owning service. */
    var onTableRectChanged: ((rect: TableConfig.TableRect, interacting: Boolean) -> Unit)? = null
    var onPlacementNeedsTable: (() -> Unit)? = null
    var onBallRadiusCalibrated: ((radiusPx: Float) -> Unit)? = null

    /** Fired when a target becomes isolated (true) or all targets are shown again (false). */
    var onIsolationChanged: ((isolated: Boolean) -> Unit)? = null

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            if (value != Mode.DEFINE_TABLE) {
                defineStart = null
                defineCurrent = null
            }
            if (value != Mode.CALIBRATE) {
                calibCenter = null
                calibCurrent = null
            }
            if (value == Mode.IDLE) {
                dragCornerIdx = -1
                dragBallIdx = -1
            }
            invalidate()
        }

    /** Eye toggle. When false, drawings (balls/lines/ghost) hide; table rect stays. */
    var referencesVisible: Boolean = true
        set(value) { field = value; invalidate() }

    /** Current defined table rectangle (absolute screen px). Loaded by service on startup. */
    var tableRect: TableConfig.TableRect? = null
        set(value) { field = value; invalidate() }

    // ---- ball state (absolute screen px) ----
    // Manual mode (no detection): cue + single objective, kept as fallback.
    private var cue: PointF? = null
    private var obj: PointF? = null
    private var bestShot: ShotCalculator.Shot? = null

    // ---- detected balls (v4/v6) ----
    private val detectedBalls = mutableListOf<DetectedBallPos>()
    private var dragBallIdx: Int = -1
    private var dragBallMoved: Boolean = false

    // ---- v6: multi-target recommendation state ----
    private var recommendation: TargetRecommender.Recommendation? = null

    /**
     * While >= 0, only that detected ball's line is drawn (tap-to-isolate).
     * -1 = all alternatives visible. Setter notifies the service (menu "✶").
     */
    var isolatedBallIdx: Int = -1
        set(value) {
            field = value
            invalidate()
            onIsolationChanged?.invoke(value >= 0)
        }

    /** Calibrated ball radius in px (v4). 0 = not calibrated → manual drawing size. */
    var ballRadiusPx: Float = 0f
        set(value) { field = value; invalidate() }

    // ---- calibrate-ball gesture (v4) ----
    private var calibCenter: PointF? = null
    private var calibCurrent: PointF? = null

    // ---- define-table drag (absolute) ----
    private var defineStart: PointF? = null
    private var defineCurrent: PointF? = null

    // ---- handle-drag during PLACEMENT ----
    private var dragCornerIdx: Int = -1

    private val config = TableConfig(context)

    // ---- paints ----
    private val cueBallFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }
    private val ballStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(220, 0, 0, 0); strokeWidth = dp(1.4f)
    }
    private val objBallFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#FFCF3F")
    }
    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#F2E9C9")
        strokeWidth = dp(2f); pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(3f)), 0f)
    }
    private val aimStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#FF8C42")
        strokeWidth = dp(2f); pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(3f)), 0f)
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAE3D3")
        textSize = dp(12f)
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(dp(2f), 0f, 0f, Color.BLACK)
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#CC14100c")
    }
    private val pocketFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#AA050505")
    }
    private val tableOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(200, 201, 162, 39)
        strokeWidth = dp(1.6f)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(4f)), 0f)
    }
    private val defineRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(220, 255, 140, 66)
        strokeWidth = dp(2.5f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#EAE3D3")
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#14100c"); strokeWidth = dp(1.2f)
    }
    private val calibMarkerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#EAE3D3")
    }
    private val calibMarkerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.parseColor("#C9A227")
        strokeWidth = dp(1.6f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
    }

    private val ballRadius = dp(14f)
    private val handleHalf = dp(7f)
    private val pocketRadius = dp(7f)

    /** Radius used to draw balls: calibrated px when available, else the fixed dp default. */
    private val displayBallRadius: Float
        get() = if (ballRadiusPx > 0f) ballRadiusPx else ballRadius

    fun reset() {
        cue = null; obj = null; bestShot = null
        detectedBalls.clear()
        recommendation = null
        isolatedBallIdx = -1
        dragBallIdx = -1
        invalidate()
    }

    /**
     * Replaces the internal detected-ball set. v6: every non-cue ball becomes a
     * simultaneous target; the best shot per target plus the global
     * recommendation are computed immediately (no user interaction). If the
     * detector did not classify the cue ball, the user marks it manually by
     * tapping a detected ball.
     */
    fun setDetectedBalls(balls: List<DetectedBallPos>) {
        detectedBalls.clear()
        detectedBalls.addAll(balls)
        cue = balls.firstOrNull { it.isCueBall }?.point
        obj = null
        bestShot = null
        recommendation = null
        isolatedBallIdx = -1
        dragBallIdx = -1
        if (detectedBalls.any { !it.isCueBall } && cue != null && tableRect != null) {
            recomputeAll()
        }
        invalidate()
    }

    /** Drop any in-progress drag/define state without saving. */
    fun cancelGestures() {
        defineStart = null
        defineCurrent = null
        dragCornerIdx = -1
        dragBallIdx = -1
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mode) {
            Mode.IDLE -> return false
            Mode.DEFINE_TABLE -> handleDefineTouch(event)
            Mode.PLACEMENT -> handlePlacementTouch(event)
            Mode.CALIBRATE -> handleCalibrateTouch(event)
        }
        return true
    }

    // ---------------- DEFINE_TABLE ----------------

    /**
     * **Single** coordinate mapping for the whole define gesture. Used for:
     *  - the live preview rectangles on every [MotionEvent.ACTION_MOVE], and
     *  - saving the final rect + pockets on [MotionEvent.ACTION_UP].
     *
     * No other conversion (rawX-delta, px↔dp, etc.) may be applied anywhere in
     * the flow — the value drawn while dragging must be the exact value persisted.
     */
    private fun screenPointFromEvent(event: MotionEvent): PointF =
        PointF(event.rawX, event.rawY)

    private fun handleDefineTouch(event: MotionEvent) {
        val p = screenPointFromEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                defineStart = p
                defineCurrent = p
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                defineCurrent = p
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val s = defineStart
                val c = defineCurrent
                defineStart = null
                defineCurrent = null
                if (s != null && c != null) {
                    val left = minOf(s.x, c.x)
                    val top = minOf(s.y, c.y)
                    val right = maxOf(s.x, c.x)
                    val bottom = maxOf(s.y, c.y)
                    val w = resources.displayMetrics.widthPixels
                    val h = resources.displayMetrics.heightPixels
                    if (right - left >= MIN_TABLE_PX && bottom - top >= MIN_TABLE_PX) {
                        val rect = TableConfig.TableRect(
                            left = left, top = top, right = right, bottom = bottom,
                            screenWidth = w, screenHeight = h
                        )
                        config.saveTableRect(rect)
                        config.savePockets(
                            ShotCalculator.pocketsFromRect(
                                ShotCalculator.Point(left, top),
                                ShotCalculator.Point(right, bottom)
                            )
                        )
                        // Redefining invalidates any previously placed balls.
                        cue = null; obj = null; bestShot = null
                        tableRect = rect
                        // Service transitions to PLACEMENT (geometry synced) on this call.
                        onTableRectChanged?.invoke(rect, false)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                defineStart = null
                defineCurrent = null
                invalidate()
            }
        }
    }

    // ---------------- PLACEMENT ----------------

    private fun handlePlacementTouch(event: MotionEvent) {
        val rect = tableRect
        if (rect == null) {
            onPlacementNeedsTable?.invoke()
            return
        }
        val x = event.rawX
        val y = event.rawY
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val corner = hitCorner(rect, x, y)
                if (corner >= 0) {
                    dragCornerIdx = corner
                    // Signal expansion to full-screen so outward drag keeps receiving events.
                    onTableRectChanged?.invoke(rect, true)
                    invalidate()
                } else if (detectedBalls.isNotEmpty()) {
                    val ballIdx = hitDetectedBall(x, y)
                    if (ballIdx >= 0) {
                        // Touch starts on a detected ball: drag (or tap-to-select on UP).
                        dragBallIdx = ballIdx
                        dragBallMoved = false
                        onTableRectChanged?.invoke(rect, true)
                        invalidate()
                    }
                    // With detection active, empty-area taps do not re-place balls
                    // manually; drag a detected ball or reset (⟲) to go back to manual.
                } else if (rect.contains(x, y)) {
                    placeBall(rect, x, y)
                }
                // Touches outside the rect that get this far (shouldn't, view is sized to rect)
                // are ignored — return true so we don't break the gesture stream.
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragBallIdx >= 0) {
                    val ball = detectedBalls.getOrNull(dragBallIdx) ?: return
                    dragBallMoved = true
                    val (cx, cy) = rect.clampPoint(x, y)
                    ball.point.set(cx, cy)
                    if (ball.isCueBall) {
                        cue = ball.point
                    }
                    if (cue != null && detectedBalls.any { !it.isCueBall }) recomputeAll(rect)
                    invalidate()
                } else if (dragCornerIdx >= 0) {
                    val moved = moveCorner(rect, dragCornerIdx, x, y)
                    tableRect = moved
                    // Keep panel informed while dragging (so it can expand if not yet).
                    onTableRectChanged?.invoke(moved, true)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragBallIdx >= 0) {
                    val ball = detectedBalls.getOrNull(dragBallIdx)
                    if (event.action == MotionEvent.ACTION_UP && !dragBallMoved && ball != null) {
                        when {
                            // Fallback: detector could not classify the white ball —
                            // first tap on any detected ball marks it as the cue.
                            cue == null && !ball.isCueBall -> {
                                detectedBalls[dragBallIdx] = ball.copy(isCueBall = true)
                                cue = ball.point
                                if (detectedBalls.any { !it.isCueBall }) recomputeAll()
                                isolatedBallIdx = -1
                            }
                            // Tap on a target = isolate its line; tap again = all.
                            cue != null && !ball.isCueBall -> {
                                isolatedBallIdx =
                                    if (isolatedBallIdx == dragBallIdx) -1 else dragBallIdx
                            }
                            // Tap on the marked cue ball does nothing.
                        }
                    }
                    dragBallIdx = -1
                    dragBallMoved = false
                    onTableRectChanged?.invoke(tableRect ?: rect, false)
                    invalidate()
                } else if (dragCornerIdx >= 0) {
                    val moved = tableRect
                    if (moved != null) config.saveTableRect(moved)
                    dragCornerIdx = -1
                    // Signal service to shrink view back to the (new) table rect.
                    onTableRectChanged?.invoke(moved ?: rect, false)
                    invalidate()
                }
            }
        }
    }

    // ---------------- CALIBRATE ----------------

    /**
     * Two taps inside the table: first = ball center, second = ball edge.
     * The distance between them is the ball radius in px, clamped to a sane
     * range, then handed to the service via [onBallRadiusCalibrated].
     */
    private fun handleCalibrateTouch(event: MotionEvent) {
        val rect = tableRect
        if (rect == null) {
            onPlacementNeedsTable?.invoke()
            return
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.rawX
                val y = event.rawY
                if (!rect.contains(x, y)) return
                val c = calibCenter
                if (c == null) {
                    calibCenter = PointF(x, y)
                } else {
                    val p = rect.clampPoint(x, y)
                    val radius = hypot(p.first - c.x, p.second - c.y)
                    val clamped = radius.coerceIn(calibMinPx, calibMaxPx)
                    calibCenter = null
                    calibCurrent = null
                    onBallRadiusCalibrated?.invoke(clamped)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (calibCenter != null) {
                    calibCurrent = PointF(event.rawX, event.rawY)
                    invalidate()
                }
            }
            else -> calibCurrent = null
        }
    }

    /** Index of the detected ball within touch reach of (x,y), else -1. */
    private fun hitDetectedBall(x: Float, y: Float): Int {
        val hitR2 = hitRadiusPx * hitRadiusPx
        for (i in detectedBalls.indices) {
            val b = detectedBalls[i]
            val dx = x - b.point.x
            val dy = y - b.point.y
            if (dx * dx + dy * dy <= hitR2) return i
        }
        return -1
    }

    private fun placeBall(rect: TableConfig.TableRect, x: Float, y: Float) {
        val (cx, cy) = rect.clampPoint(x, y)
        val p = PointF(cx, cy)
        if (cue == null) {
            cue = p; obj = null; bestShot = null
        } else if (obj == null) {
            obj = p
            recompute(rect)
        } else {
            // Re-place cue first; next tap will pick the object.
            cue = p; obj = null; bestShot = null
        }
        invalidate()
    }

    /** Returns corner index [0..3] if (x,y) is within CORNER_HIT_PX of one, else -1. */
    private fun hitCorner(rect: TableConfig.TableRect, x: Float, y: Float): Int {
        val r = CORNER_HIT_PX
        val r2 = r * r
        val corners = arrayOf(
            floatArrayOf(rect.left, rect.top),
            floatArrayOf(rect.right, rect.top),
            floatArrayOf(rect.left, rect.bottom),
            floatArrayOf(rect.right, rect.bottom)
        )
        for (i in corners.indices) {
            val dx = x - corners[i][0]
            val dy = y - corners[i][1]
            if (dx * dx + dy * dy <= r2) return i
        }
        return -1
    }

    private fun moveCorner(
        rect: TableConfig.TableRect, idx: Int, x: Float, y: Float
    ): TableConfig.TableRect {
        var left = rect.left; var right = rect.right
        var top = rect.top; var bottom = rect.bottom
        when (idx) {
            0 -> { left = x; top = y }
            1 -> { right = x; top = y }
            2 -> { left = x; bottom = y }
            3 -> { right = x; bottom = y }
        }
        // If the user drags a corner past the opposite edge, swap to keep rect valid.
        if (left > right) { val t = left; left = right; right = t }
        if (top > bottom) { val t = top; top = bottom; bottom = t }
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return TableConfig.TableRect(
            left = left.coerceIn(0f, w.toFloat()),
            top = top.coerceIn(0f, h.toFloat()),
            right = right.coerceIn(0f, w.toFloat()),
            bottom = bottom.coerceIn(0f, h.toFloat()),
            screenWidth = w, screenHeight = h
        )
    }

    // ---------------- DRAW ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = tableRect
        val offX = if (mode == Mode.DEFINE_TABLE || dragCornerIdx >= 0) 0f else (rect?.left ?: 0f)
        val offY = if (mode == Mode.DEFINE_TABLE || dragCornerIdx >= 0) 0f else (rect?.top ?: 0f)

        // 1) In-progress define rectangle (drawn in screen coords, view is full-screen here).
        val definingDrag = mode == Mode.DEFINE_TABLE && defineStart != null && defineCurrent != null
        if (definingDrag) {
            val l = minOf(defineStart!!.x, defineCurrent!!.x)
            val t = minOf(defineStart!!.y, defineCurrent!!.y)
            val r = maxOf(defineStart!!.x, defineCurrent!!.x)
            val b = maxOf(defineStart!!.y, defineCurrent!!.y)
            canvas.drawRect(l, t, r, b, defineRectPaint)
            drawDefineHint(canvas)
        }

        // 2) Defined table outline + 4 handles. Always drawn when a rect exists,
        //    except while a new define drag is in progress (old rect must not
        //    linger next to the live preview).
        if (rect != null && !definingDrag) {
            val vl = rect.left - offX
            val vt = rect.top - offY
            val vr = rect.right - offX
            val vb = rect.bottom - offY
            canvas.drawRect(vl, vt, vr, vb, tableOutlinePaint)
            drawHandle(canvas, vl, vt)
            drawHandle(canvas, vr, vt)
            drawHandle(canvas, vl, vb)
            drawHandle(canvas, vr, vb)
        }

        // 2.5) Pockets of the active rect — always visible while defining or
        //      placing (even with the eye toggle off), so the user can see
        //      where they were computed.
        if (mode != Mode.IDLE) {
            activePockets()?.forEach { pk ->
                canvas.drawCircle(pk.x - offX, pk.y - offY, pocketRadius, pocketFill)
            }
        }

        // 3) Hint for placement mode without a defined table.
        if (mode == Mode.PLACEMENT && rect == null) {
            drawPlacementNeedsTableHint(canvas)
        }

        // 3.5) Calibration markers + hint — interaction feedback, always visible.
        if (mode == Mode.CALIBRATE) {
            drawCenteredHint(canvas, "Toca el centro de una bola · luego su borde", dp(20f))
            calibCenter?.let { c ->
                val cx = c.x - offX
                val cy = c.y - offY
                canvas.drawCircle(cx, cy, dp(4f), calibMarkerFill)
                val preview = calibCurrent?.let {
                    val dx = it.x - c.x
                    val dy = it.y - c.y
                    hypot(dx, dy)
                }
                canvas.drawCircle(
                    cx, cy,
                    (preview ?: dp(24f)).coerceIn(calibMinPx, calibMaxPx),
                    calibMarkerStroke
                )
            }
        }

        // 4) References (balls, lines, ghost, angle label) — hidden when eye off.
        if (!referencesVisible) return

        val cueP = cue
        val rad = displayBallRadius

        // Manual mode (no detection) draws the single objective "O".
        if (detectedBalls.isEmpty()) {
            val objP = obj
            val shot = bestShot
            if (shot != null && cueP != null) {
                drawShot(
                    canvas, shot, cueP, objP, offX, offY,
                    aim = aimStroke, ghost = ghostStroke,
                    label = shotLabel(shot)
                )
            }
            objP?.let {
                val x = it.x - offX; val y = it.y - offY
                canvas.drawCircle(x, y, rad, objBallFill)
                canvas.drawCircle(x, y, rad, ballStroke)
                canvas.drawText("O", x - dp(4f), y - rad - dp(6f), labelText)
            }
        } else {
            drawMultiTarget(canvas, cueP, offX, offY, rad)
        }

        cueP?.let {
            val x = it.x - offX; val y = it.y - offY
            canvas.drawCircle(x, y, rad, cueBallFill)
            canvas.drawCircle(x, y, rad, ballStroke)
            canvas.drawText("B", x - dp(4f), y - rad - dp(6f), labelText)
        }
    }

    /**
     * v6 draw: every non-cue detected ball gets its palette color; the best
     * shot lines are drawn per target (thin, semi-transparent), and the global
     * recommendation as a thick, fully opaque line labeled "MEJOR OPCIÓN".
     * When a target is isolated, only that one's line remains.
     */
    private fun drawMultiTarget(
        canvas: Canvas,
        cueP: PointF?,
        offX: Float, offY: Float,
        rad: Float
    ) {
        val isolated = isolatedBallIdx

        detectedBalls.forEachIndexed { i, b ->
            if (b.isCueBall) return@forEachIndexed
            val outline = ballOutlinePaint(
                TargetPalette.color(b.colorIndex),
                strokePx = if (isolated == i) dp(3.2f) else dp(2.2f),
                alpha = if (isolated >= 0 && isolated != i) 110 else 255
            )
            canvas.drawCircle(b.point.x - offX, b.point.y - offY, rad, outline)
        }

        if (cueP == null) {
            drawCenteredHint(canvas, "Toca la bola blanca para fijarla manualmente", dp(20f))
            return
        }
        if (!detectedBalls.any { !it.isCueBall }) {
            drawCenteredHint(canvas, "No se detectaron bolas objetivo — usa el modo manual", dp(20f))
            return
        }
        val rec = recommendation ?: return

        rec.perTarget.forEach { ts ->
            val best = ts.best ?: return@forEach
            val ball = detectedBalls.firstOrNull { !it.isCueBall && it.colorIndex == ts.colorIndex }
                ?: return@forEach
            val i = detectedBalls.indexOf(ball)
            if (isolated >= 0 && isolated != i) return@forEach
            val color = TargetPalette.color(ts.colorIndex)
            if (ts == rec.globalBest) {
                drawShot(
                    canvas, best, cueP, ball.point, offX, offY,
                    aim = mainLinePaint(color), ghost = mainLinePaint(color),
                    label = "MEJOR OPCIÓN · ${shotLabel(best)}"
                )
            } else {
                drawShot(
                    canvas, best, cueP, ball.point, offX, offY,
                    aim = altLinePaint(color), ghost = ghostStroke,
                    label = null, drawGhostCircle = false
                )
            }
        }
    }

    private fun ballOutlinePaint(color: Int, strokePx: Float, alpha: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeWidth = strokePx
            this.alpha = alpha
        }

    private fun mainLinePaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            alpha = 255
            strokeWidth = dp(3.5f)
        }

    private fun altLinePaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            alpha = 130
            strokeWidth = dp(1.4f)
            pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(3f)), 0f)
        }

    private fun shotLabel(shot: ShotCalculator.Shot): String {
        val type = when (shot) {
            is ShotCalculator.Shot.Direct -> "DIRECTO"
            is ShotCalculator.Shot.Bank -> "BANDA · ${railName(shot.rail)}"
        }
        return "$type · corte ${"%.1f".format(shot.cutAngleDeg)}°"
    }

    private fun drawShot(
        canvas: Canvas,
        shot: ShotCalculator.Shot,
        cueP: PointF,
        objP: PointF?,
        offX: Float, offY: Float,
        aim: Paint,
        ghost: Paint,
        label: String? = null,
        drawGhostCircle: Boolean = true
    ) {
        val cueX = cueP.x - offX; val cueY = cueP.y - offY
        val ghostX: Float; val ghostY: Float
        when (shot) {
            is ShotCalculator.Shot.Direct -> {
                ghostX = shot.ghostBall.x - offX; ghostY = shot.ghostBall.y - offY
                canvas.drawLine(cueX, cueY, ghostX, ghostY, ghost)
                if (objP != null) {
                    canvas.drawLine(
                        objP.x - offX, objP.y - offY,
                        shot.pocket.x - offX, shot.pocket.y - offY,
                        aim
                    )
                }
            }
            is ShotCalculator.Shot.Bank -> {
                ghostX = shot.ghostBall.x - offX; ghostY = shot.ghostBall.y - offY
                canvas.drawLine(cueX, cueY, ghostX, ghostY, ghost)
                if (objP != null) {
                    canvas.drawLine(
                        objP.x - offX, objP.y - offY,
                        shot.impactPoint.x - offX, shot.impactPoint.y - offY,
                        aim
                    )
                    canvas.drawLine(
                        shot.impactPoint.x - offX, shot.impactPoint.y - offY,
                        shot.pocket.x - offX, shot.pocket.y - offY,
                        aim
                    )
                    val impactP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = aim.color
                        alpha = maxOf(60, aim.alpha * 3 / 4)
                    }
                    canvas.drawCircle(
                        shot.impactPoint.x - offX, shot.impactPoint.y - offY,
                        dp(3.5f), impactP
                    )
                }
            }
        }

        if (drawGhostCircle) {
            canvas.drawCircle(ghostX, ghostY, displayBallRadius, ghostStroke)
        }

        if (label != null && objP != null) {
            val textWidth = labelText.measureText(label)
            val pad = dp(8f)
            val bgRect = RectF(
                ghostX - textWidth / 2 - pad,
                ghostY - ballRadius - dp(36f),
                ghostX + textWidth / 2 + pad,
                ghostY - ballRadius - dp(12f)
            )
            canvas.drawRoundRect(bgRect, dp(4f), dp(4f), hintBg)
            canvas.drawText(label, ghostX - textWidth / 2, ghostY - ballRadius - dp(18f), labelText)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleHalf, handleFill)
        canvas.drawCircle(x, y, handleHalf, handleStroke)
    }

    private fun drawDefineHint(canvas: Canvas) {
        val hint = "Arrastra para marcar la mesa · suelta para confirmar"
        drawCenteredHint(canvas, hint, dp(20f))
    }

    private fun drawPlacementNeedsTableHint(canvas: Canvas) {
        val hint = "Primero define el área de la mesa (botón M)"
        drawCenteredHint(canvas, hint, dp(20f))
    }

    private fun drawCenteredHint(canvas: Canvas, text: String, topY: Float) {
        val textWidth = labelText.measureText(text)
        val pad = dp(10f)
        val cx = width / 2f
        val bgRect = RectF(
            cx - textWidth / 2 - pad,
            topY,
            cx + textWidth / 2 + pad,
            topY + labelText.textSize + pad
        )
        canvas.drawRoundRect(bgRect, dp(6f), dp(6f), hintBg)
        canvas.drawText(text, cx - textWidth / 2, topY + labelText.textSize - dp(2f), labelText)
    }

    private fun railName(rail: ShotCalculator.Rail) = when (rail) {
        ShotCalculator.Rail.TOP -> "sup."
        ShotCalculator.Rail.BOTTOM -> "inf."
        ShotCalculator.Rail.LEFT -> "izq."
        ShotCalculator.Rail.RIGHT -> "der."
    }

    private fun recompute(rect: TableConfig.TableRect) {
        val cueP = cue ?: return
        val objP = obj ?: return
        val table = ShotCalculator.Rect(rect.left, rect.top, rect.right, rect.bottom)
        val pockets = config.loadPockets() ?: pocketsFrom(rect)
        val shots = ShotCalculator.compute(
            cue = ShotCalculator.Point(cueP.x, cueP.y),
            obj = ShotCalculator.Point(objP.x, objP.y),
            pockets = pockets,
            table = table
        )
        bestShot = shots.firstOrNull()
    }

    /**
     * v6: recompute the per-target best shots + global recommendation for every
     * detected non-cue ball. Safe with zero targets (recommendation is empty).
     */
    private fun recomputeAll(rect: TableConfig.TableRect? = null) {
        val r = rect ?: tableRect ?: return
        val cueP = cue ?: return
        val targets = detectedBalls.filter { !it.isCueBall }.map { it.point }
        if (targets.isEmpty()) {
            recommendation = TargetRecommender.Recommendation(emptyList(), null)
            return
        }
        recommendation = TargetRecommender.recommend(
            cue = ShotCalculator.Point(cueP.x, cueP.y),
            targets = targets.map { ShotCalculator.Point(it.x, it.y) },
            pockets = config.loadPockets() ?: pocketsFrom(r),
            table = ShotCalculator.Rect(r.left, r.top, r.right, r.bottom)
        )
    }

    /** 6 pockets derived from a saved table rect (long-side mids). */
    private fun pocketsFrom(rect: TableConfig.TableRect): List<ShotCalculator.Pocket> =
        ShotCalculator.pocketsFromRect(
            ShotCalculator.Point(rect.left, rect.top),
            ShotCalculator.Point(rect.right, rect.bottom)
        )

    /**
     * Pockets of the rect currently on screen: the live preview during a define
     * drag, or the saved rect (auto-computed, persisted) in PLACEMENT.
     */
    private fun activePockets(): List<ShotCalculator.Pocket>? {
        if (mode == Mode.DEFINE_TABLE && defineStart != null && defineCurrent != null) {
            val s = defineStart!!
            val c = defineCurrent!!
            return ShotCalculator.pocketsFromRect(
                ShotCalculator.Point(minOf(s.x, c.x), minOf(s.y, c.y)),
                ShotCalculator.Point(maxOf(s.x, c.x), maxOf(s.y, c.y))
            )
        }
        val rect = tableRect ?: return null
        return config.loadPockets() ?: pocketsFrom(rect)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /** Hit radius for a corner handle, expressed in **dp** (converted at touch time). */
    private val cornerHitPx: Float get() = dp(28f)

    /** Hit radius for touching/dragging a detected ball (comfortable, ≥ ball size). */
    private val hitRadiusPx: Float get() = maxOf(ballRadiusPx, dp(28f))

    /** Clamp bounds for the calibrated ball radius. */
    private val calibMinPx: Float get() = dp(8f)
    private val calibMaxPx: Float get() = dp(40f)

    /** Minimum table side to accept a define or handle-drag release. */
    private val minTablePx: Float get() = dp(120f)

    private val MIN_TABLE_PX: Float get() = minTablePx
    private val CORNER_HIT_PX: Float get() = cornerHitPx
}
