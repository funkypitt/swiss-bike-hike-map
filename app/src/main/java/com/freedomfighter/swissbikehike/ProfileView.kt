package com.freedomfighter.swissbikehike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** The elevation profile of the trip: white line on black, the position as a red mark, the stops as ticks. */
class ProfileView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var trip: Trip? = null
    private var doneM = -1.0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density }
    private val fill = Paint().apply { color = Color.parseColor("#3A3A3A"); style = Paint.Style.FILL }
    private val grid = Paint().apply { color = Color.parseColor("#555555"); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E30613"); style = Paint.Style.FILL }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#BBBBBB"); textSize = 11f * resources.displayMetrics.scaledDensity; typeface = android.graphics.Typeface.MONOSPACE }

    fun set(t: Trip?) { trip = t; doneM = -1.0; invalidate() }
    fun progress(m: Double) { doneM = m; invalidate() }

    override fun onDraw(c: Canvas) {
        val t = trip ?: return
        if (!t.hasElevation || t.points.size < 2) return
        val w = width.toFloat(); val h = height.toFloat()
        val padL = text.measureText("8888 "); val padB = text.textSize + 10f; val padT = text.textSize * 0.7f
        val gw = w - padL - 8f; val gh = h - padT - padB
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (e in t.ele) if (!e.isNaN()) { if (e < lo) lo = e; if (e > hi) hi = e }
        if (hi - lo < 50) { val mid = (hi + lo) / 2; lo = mid - 25; hi = mid + 25 }
        val step = niceStep(hi - lo)
        lo = floor(lo / step) * step; hi = ceil(hi / step) * step
        val total = t.cum.last().coerceAtLeast(1.0)
        fun x(m: Double) = padL + (m / total * gw).toFloat()
        fun y(e: Double) = padT + ((hi - e) / (hi - lo) * gh).toFloat()

        // grid and labels
        var g = lo
        while (g <= hi + 0.1) {
            c.drawLine(padL, y(g), w - 8f, y(g), grid)
            c.drawText(String.format(Locale.US, "%4.0f", g), 0f, y(g) + text.textSize / 3, text)
            g += step
        }
        // area and line
        val p = Path(); var started = false; var lastEle = Double.NaN
        for (i in t.points.indices) {
            var e = t.ele[i]; if (e.isNaN()) e = lastEle; if (e.isNaN()) continue
            lastEle = e
            if (!started) { p.moveTo(x(t.cum[i]), y(e)); started = true } else p.lineTo(x(t.cum[i]), y(e))
        }
        if (!started) return
        val area = Path(p); area.lineTo(x(total), padT + gh); area.lineTo(x(0.0), padT + gh); area.close()
        c.drawPath(area, fill)
        c.drawPath(p, line)
        // stops
        for (s in 0 until t.stopIdx.size - 1) { val xx = x(t.cum[t.stopIdx[s]]); c.drawLine(xx, padT, xx, padT + gh, tick) }
        // distance labels
        c.drawText("0", padL, h - 2f, text)
        val km = String.format(Locale.US, "%.1f km", total / 1000)
        c.drawText(km, w - 8f - text.measureText(km), h - 2f, text)
        // position
        if (doneM >= 0) {
            val i = t.cum.indexOfFirst { it >= doneM }.let { if (it < 0) t.points.size - 1 else it }
            var e = t.ele[i]; var j = i; while (e.isNaN() && j > 0) { j--; e = t.ele[j] }
            if (!e.isNaN()) c.drawCircle(x(doneM), y(e), 6f * resources.displayMetrics.density, mark)
        }
    }

    private fun niceStep(range: Double): Double {
        val raw = range / 4
        val mag = Math.pow(10.0, floor(Math.log10(raw)))
        val n = raw / mag
        return mag * when { n <= 1 -> 1.0; n <= 2 -> 2.0; n <= 5 -> 5.0; else -> 10.0 }
    }
}
