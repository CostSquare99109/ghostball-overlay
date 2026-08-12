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
 * Full-screen transparent overlay. Captures taps to place cue and object balls
 * and renders the best shot suggested by [ShotCalculator].
 *
 * All drawing uses native `Canvas` — no SVG, no OpenGL. Colors and stroke widths
 * are tuned for legibility over arbitrary app content.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Caller-supplied callbacks. */
    var onRequestStop: (() -> Unit)? = null
    var onRequestReset: (() -> Unit)? = null

    private var cue: PointF? = null
    private var obj: PointF? = null
    private var bestShot: ShotCalculator.Shot? = null

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

    private val ballRadius = dp(14f)

    fun reset() {
        cue = null; obj = null; bestShot = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val p = PointF(event.x, event.y)

        // Top-left reserved for reset (and stop in the corner).
        if (p.x < dp(96f) && p.y < dp(96f)) {
            reset()
            return true
        }

        if (cue == null) {
            cue = p
            obj = null
            bestShot = null
        } else if (obj == null) {
            obj = p
            recompute()
        } else {
            // Re-place cue first, then on the next tap the object.
            cue = p
            obj = null
            bestShot = null
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Persistent hint at top-center.
        drawHint(canvas)

        // Draw pockets (visual reference) if user defined them.
        config.loadPockets()?.forEach { pk ->
            canvas.drawCircle(pk.x, pk.y, dp(7f), pocketFill)
        }

        val cueP = cue
        val objP = obj
        val shot = bestShot

        // Trajectory lines first (under balls).
        if (shot != null && cueP != null) {
            val ghostX: Float; val ghostY: Float
            when (shot) {
                is ShotCalculator.Shot.Direct -> {
                    ghostX = shot.ghostBall.x; ghostY = shot.ghostBall.y
                    canvas.drawLine(cueP.x, cueP.y, ghostX, ghostY, ghostStroke)
                    canvas.drawLine(
                        objP!!.x, objP.y,
                        shot.pocket.x, shot.pocket.y,
                        aimStroke
                    )
                }
                is ShotCalculator.Shot.Bank -> {
                    ghostX = shot.ghostBall.x; ghostY = shot.ghostBall.y
                    canvas.drawLine(cueP.x, cueP.y, ghostX, ghostY, ghostStroke)
                    canvas.drawLine(
                        objP!!.x, objP.y,
                        shot.impactPoint.x, shot.impactPoint.y,
                        aimStroke
                    )
                    canvas.drawLine(
                        shot.impactPoint.x, shot.impactPoint.y,
                        shot.pocket.x, shot.pocket.y,
                        aimStroke
                    )
                    canvas.drawCircle(
                        shot.impactPoint.x, shot.impactPoint.y,
                        dp(3.5f), impactFill
                    )
                }
            }

            // Ghost ball (dashed outline only, no fill).
            canvas.drawCircle(ghostX, ghostY, ballRadius, ghostStroke)

            // Floating label with the angle + type.
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
            canvas.drawText(
                label,
                ghostX - textWidth / 2,
                ghostY - ballRadius - dp(18f),
                labelText
            )
        }

        // Balls on top.
        cueP?.let {
            canvas.drawCircle(it.x, it.y, ballRadius, cueBallFill)
            canvas.drawCircle(it.x, it.y, ballRadius, ballStroke)
            canvas.drawText("B", it.x - dp(4f), it.y - ballRadius - dp(6f), labelText)
        }
        objP?.let {
            canvas.drawCircle(it.x, it.y, ballRadius, objBallFill)
            canvas.drawCircle(it.x, it.y, ballRadius, ballStroke)
            canvas.drawText("O", it.x - dp(4f), it.y - ballRadius - dp(6f), labelText)
        }
    }

    private fun drawHint(canvas: Canvas) {
        val hint = when {
            cue == null -> "Toca para colocar la BOLA BLANCA · esquina sup-izq = reset"
            obj == null -> "Toca para colocar la BOLA OBJETIVO"
            else -> "Toca para re-colocar la blanca"
        }
        val textWidth = labelText.measureText(hint)
        val pad = dp(10f)
        val cx = width / 2f
        val bgRect = RectF(
            cx - textWidth / 2 - pad,
            dp(20f),
            cx + textWidth / 2 + pad,
            dp(20f) + labelText.textSize + pad
        )
        canvas.drawRoundRect(bgRect, dp(6f), dp(6f), hintBg)
        canvas.drawText(hint, cx - textWidth / 2, dp(20f) + labelText.textSize - dp(2f), labelText)
    }

    private fun railName(rail: ShotCalculator.Rail) = when (rail) {
        ShotCalculator.Rail.TOP -> "sup."
        ShotCalculator.Rail.BOTTOM -> "inf."
        ShotCalculator.Rail.LEFT -> "izq."
        ShotCalculator.Rail.RIGHT -> "der."
    }

    private fun recompute() {
        val cueP = cue ?: return
        val objP = obj ?: return

        val table = ShotCalculator.Rect(0f, 0f, width.toFloat(), height.toFloat())

        val pockets = config.loadPockets()
            ?: defaultScreenPockets(table)

        val shots = ShotCalculator.compute(
            cue = ShotCalculator.Point(cueP.x, cueP.y),
            obj = ShotCalculator.Point(objP.x, objP.y),
            pockets = pockets,
            table = table
        )
        bestShot = shots.firstOrNull()
    }

    /** 6 pockets mapped to the screen corners + mid-points when user hasn't defined real ones. */
    private fun defaultScreenPockets(table: ShotCalculator.Rect): List<ShotCalculator.Pocket> {
        val w = table.width; val h = table.height
        return listOf(
            ShotCalculator.Pocket(0f,    0f,    "Esquina sup-izq"),
            ShotCalculator.Pocket(w,     0f,    "Esquina sup-der"),
            ShotCalculator.Pocket(0f,    h,     "Esquina inf-izq"),
            ShotCalculator.Pocket(w,     h,     "Esquina inf-der"),
            ShotCalculator.Pocket(w / 2, 0f,    "Centro superior"),
            ShotCalculator.Pocket(w / 2, h,     "Centro inferior"),
        )
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
