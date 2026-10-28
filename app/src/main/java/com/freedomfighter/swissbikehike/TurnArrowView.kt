package com.freedomfighter.swissbikehike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import btools.router.TurnHints
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * One big turn arrow, drawn by hand so it reads from a metre away: a bent shaft with a head,
 * like the old GPS boxes. Also a roundabout with its exit number and the arrival target.
 */
class TurnArrowView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var cmd = TurnHints.C
    private var exit = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#777777"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    fun set(cmd: Int, exit: Int = 0) { this.cmd = cmd; this.exit = exit; invalidate() }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0) return
        c.save()
        c.translate((width - s) / 2, (height - s) / 2)
        c.scale(s / 100f, s / 100f)
        paint.strokeWidth = 13f; ghost.strokeWidth = 9f; text.textSize = 34f
        when (cmd) {
            TurnHints.END -> { paint.strokeWidth = 8f; c.drawCircle(50f, 50f, 36f, paint); c.drawCircle(50f, 50f, 16f, fill) }
            TurnHints.RNDB, TurnHints.RNLB -> roundabout(c)
            TurnHints.TLU, TurnHints.TRU, TurnHints.TU -> uturn(c, cmd != TurnHints.TLU)
            TurnHints.KL, TurnHints.KR, TurnHints.EL, TurnHints.ER -> keep(c, cmd == TurnHints.KR || cmd == TurnHints.ER)
            TurnHints.TL -> bent(c, 50f, 88f, 50f, 40f, 8f, 40f)
            TurnHints.TR -> bent(c, 50f, 88f, 50f, 40f, 92f, 40f)
            TurnHints.TSLL -> bent(c, 50f, 88f, 50f, 50f, 16f, 12f)
            TurnHints.TSLR -> bent(c, 50f, 88f, 50f, 50f, 84f, 12f)
            TurnHints.TSHL -> bent(c, 62f, 88f, 62f, 30f, 8f, 84f)
            TurnHints.TSHR -> bent(c, 38f, 88f, 38f, 30f, 92f, 84f)
            else -> arrow(c, 50f, 90f, 50f, 14f)
        }
        c.restore()
    }

    // Head geometry, in the 100-unit box: the tip is the end point, the base is HEAD_LEN behind it and
    // 2 × HEAD_HALF wide; the shaft stops SHAFT_GAP before the tip, inside the base, so its round end
    // never shows past the triangle's sides.
    private val headLen = 30f; private val headHalf = 17f; private val shaftGap = 14f

    /** Straight shaft with a head at the end. */
    private fun arrow(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val (sx, sy) = back(x0, y0, x1, y1, shaftGap)
        c.drawLine(x0, y0, sx, sy, paint)
        head(c, x0, y0, x1, y1)
    }

    /** Shaft up to the corner, then to the end, head at the end. */
    private fun bent(c: Canvas, x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float) {
        val (sx, sy) = back(cx, cy, x1, y1, shaftGap)
        val p = Path(); p.moveTo(x0, y0); p.lineTo(cx, cy); p.lineTo(sx, sy)
        c.drawPath(p, paint)
        head(c, cx, cy, x1, y1)
    }

    /** The point `d` units before (x, y) on the way from (fromX, fromY). */
    private fun back(fromX: Float, fromY: Float, x: Float, y: Float, d: Float): Pair<Float, Float> {
        val a = atan2((y - fromY).toDouble(), (x - fromX).toDouble())
        return Pair(x - d * cos(a).toFloat(), y - d * sin(a).toFloat())
    }

    private fun head(c: Canvas, fromX: Float, fromY: Float, x: Float, y: Float) {
        val a = atan2((y - fromY).toDouble(), (x - fromX).toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val bx = x - headLen * ca; val by = y - headLen * sa
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(bx - headHalf * sa, by + headHalf * ca)
        p.lineTo(bx + headHalf * sa, by - headHalf * ca)
        p.close()
        c.drawPath(p, fill)
    }

    private fun keep(c: Canvas, right: Boolean) {
        val dx = if (right) 1f else -1f
        c.drawLine(50f, 90f, 50f, 60f, paint)
        c.drawLine(50f, 60f, 50f - 18f * dx, 22f, ghost)
        arrow(c, 50f, 60f, 50f + 24f * dx, 10f)
    }

    private fun uturn(c: Canvas, right: Boolean) {
        val dx = if (right) 1f else -1f
        val p = Path()
        p.moveTo(50f - 20f * dx, 90f); p.lineTo(50f - 20f * dx, 45f)
        p.arcTo(30f, 20f, 70f, 70f, 180f, 180f * dx, false)
        p.lineTo(50f + 20f * dx, 82f - shaftGap)
        c.drawPath(p, paint)
        head(c, 50f + 20f * dx, 50f, 50f + 20f * dx, 82f)
    }

    private fun roundabout(c: Canvas) {
        paint.strokeWidth = 10f
        c.drawCircle(50f, 58f, 24f, paint)
        c.drawLine(50f, 96f, 50f, 84f, paint)
        arrow(c, 50f, 34f, 50f, 8f)
        if (exit > 0) c.drawText(exit.toString(), 50f, 70f, text)
    }
}
