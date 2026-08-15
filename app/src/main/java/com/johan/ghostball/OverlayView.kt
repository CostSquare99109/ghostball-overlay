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

/**
 * Overlay drawing surface. Hosts three interaction modes:
 *
 *  - [Mode.IDLE]:          not touchable; never receives events.
 *  - [Mode.PLACEMENT]:     captures taps inside the table rectangle to place
 *                          cue/object balls; allows corner-handle drag.
 *  - [Mode.DEFINE_TABLE]:  captures a single drag (down→move→up) that defines
 *                          the table rectangle.
 *
 * All coordinates stored on this view are **screen-absolute** (rawX/rawY). The
 * owning [OverlayService] sizes this view to either the table rectangle (in
 * PLACEMENT) or the full screen (in DEFINE_TABLE or while dragging a corner),
 * and positions it at (rect.left, rect.top). Drawing must therefore subtract
 * the view's screen origin from absolute coordinates to obtain view-local
 * canvas coordinates.
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

    enum class Mode { IDLE, PLACEMENT, DEFINE_TABLE }

    /** Callbacks fired to the owning service. */
    var onTableRectChanged: ((rect: TableConfig.TableRect, interacting: Boolean) -> Unit)? = null
    var onPlacementNeedsTable: (() -> Unit)? = null

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            if (value != Mode.DEFINE_TABLE) {
                defineStart = null
                defineCurrent = null
            }
            if (value == Mode.IDLE) dragCornerIdx = -1
            invalidate()
        }

    /** Eye toggle. When false, drawings (balls/lines/ghost) hide; table rect stays. */
    var referencesVisible: Boolean = true
        set(value) { field = value; invalidate() }

    /** Current defined table rectangle (absolute screen px). Loaded by service on startup. */
    var tableRect: TableConfig.TableRect? = null
        set(value) { field = value; invalidate() }

    // ---- ball state (absolute screen px) ----
    private var cue: PointF? = null
    private var obj: PointF? = null
    private var bestShot: ShotCalculator.Shot? = null

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
    private val impactFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.parseColor("#C9A227")
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

    private val ballRadius = dp(14f)
    private val handleHalf = dp(7f)
    private val pocketRadius = dp(7f)

    fun reset() {
        cue = null; obj = null; bestShot = null
        invalidate()
    }

    /** Drop any in-progress drag/define state without saving. */
    fun cancelGestures() {
        defineStart = null
        defineCurrent = null
        dragCornerIdx = -1
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (mode) {
            Mode.IDLE -> return false
            Mode.DEFINE_TABLE -> handleDefineTouch(event)
            Mode.PLACEMENT -> handlePlacementTouch(event)
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
                } else if (rect.contains(x, y)) {
                    placeBall(rect, x, y)
                }
                // Touches outside the rect that get this far (shouldn't, view is sized to rect)
                // are ignored — return true so we don't break the gesture stream.
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCornerIdx >= 0) {
                    val moved = moveCorner(rect, dragCornerIdx, x, y)
                    tableRect = moved
                    // Keep panel informed while dragging (so it can expand if not yet).
                    onTableRectChanged?.invoke(moved, true)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragCornerIdx >= 0) {
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

        // 4) References (balls, lines, ghost, angle label) — hidden when eye off.
        if (!referencesVisible) return

        val cueP = cue
        val objP = obj
        val shot = bestShot

        if (shot != null && cueP != null) {
            drawShot(canvas, shot, cueP, objP, offX, offY)
        }

        cueP?.let {
            val x = it.x - offX; val y = it.y - offY
            canvas.drawCircle(x, y, ballRadius, cueBallFill)
            canvas.drawCircle(x, y, ballRadius, ballStroke)
            canvas.drawText("B", x - dp(4f), y - ballRadius - dp(6f), labelText)
        }
        objP?.let {
            val x = it.x - offX; val y = it.y - offY
            canvas.drawCircle(x, y, ballRadius, objBallFill)
            canvas.drawCircle(x, y, ballRadius, ballStroke)
            canvas.drawText("O", x - dp(4f), y - ballRadius - dp(6f), labelText)
        }
    }

    private fun drawShot(
        canvas: Canvas,
        shot: ShotCalculator.Shot,
        cueP: PointF,
        objP: PointF?,
        offX: Float, offY: Float
    ) {
        val cueX = cueP.x - offX; val cueY = cueP.y - offY
        val ghostX: Float; val ghostY: Float
        when (shot) {
            is ShotCalculator.Shot.Direct -> {
                ghostX = shot.ghostBall.x - offX; ghostY = shot.ghostBall.y - offY
                canvas.drawLine(cueX, cueY, ghostX, ghostY, ghostStroke)
                if (objP != null) {
                    canvas.drawLine(
                        objP.x - offX, objP.y - offY,
                        shot.pocket.x - offX, shot.pocket.y - offY,
                        aimStroke
                    )
                }
            }
            is ShotCalculator.Shot.Bank -> {
                ghostX = shot.ghostBall.x - offX; ghostY = shot.ghostBall.y - offY
                canvas.drawLine(cueX, cueY, ghostX, ghostY, ghostStroke)
                if (objP != null) {
                    canvas.drawLine(
                        objP.x - offX, objP.y - offY,
                        shot.impactPoint.x - offX, shot.impactPoint.y - offY,
                        aimStroke
                    )
                    canvas.drawLine(
                        shot.impactPoint.x - offX, shot.impactPoint.y - offY,
                        shot.pocket.x - offX, shot.pocket.y - offY,
                        aimStroke
                    )
                    canvas.drawCircle(
                        shot.impactPoint.x - offX, shot.impactPoint.y - offY,
                        dp(3.5f), impactFill
                    )
                }
            }
        }

        canvas.drawCircle(ghostX, ghostY, ballRadius, ghostStroke)

        val typeLabel = when (shot) {
            is ShotCalculator.Shot.Direct -> "DIRECTO"
            is ShotCalculator.Shot.Bank -> "BANDA · ${railName(shot.rail)}"
        }
        val label = "$typeLabel · corte ${"%.1f".format(shot.cutAngleDeg)}°"
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

    /** Minimum table side to accept a define or handle-drag release. */
    private val minTablePx: Float get() = dp(120f)

    private val MIN_TABLE_PX: Float get() = minTablePx
    private val CORNER_HIT_PX: Float get() = cornerHitPx
}
