package com.freedomfighter.swissbikehike

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Night map: the swisstopo colours are remapped, not inverted. Dark text becomes light, light ground
 * becomes dark, and the white roads land on a mid grey so they still stand out, like a GPS box at night.
 * The signpost colours of the trails and the route are left alone.
 */
object Night {
    const val OFF = "off"; const val ON = "on"; const val AUTO = "auto"

    fun darken(style: JSONObject) {
        val layers = style.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val l = layers.getJSONObject(i)
            val id = l.optString("id")
            if (id.startsWith("trail-") || id.startsWith("route-") || id.startsWith("pt-") || id.startsWith("journey-") || id == "track-line" || id == "pin") continue
            val paint = l.optJSONObject("paint") ?: continue
            if (l.optString("type") == "raster") { paint.put("raster-brightness-max", 0.75); continue }
            for (k in paint.keys().asSequence().toList()) {
                if (!k.endsWith("-color")) continue
                paint.put(k, remap(paint.get(k)))
            }
            if (id.startsWith("hillshade")) paint.put("fill-opacity", 0.6)
        }
    }

    private fun remap(v: Any): Any = when (v) {
        is String -> parse(v)?.let { (r, g, b, a) -> dark(r, g, b, a) } ?: v
        is JSONArray -> JSONArray().also { out -> for (i in 0 until v.length()) out.put(remap(v.get(i))) }
        is JSONObject -> v.also { o -> o.optJSONArray("stops")?.let { st -> for (i in 0 until st.length()) { val pair = st.getJSONArray(i); pair.put(1, remap(pair.get(1))) } } }
        else -> v
    }

    private val rgb = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)")
    private val hex = Regex("#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?")
    private val hex3 = Regex("#([0-9a-fA-F]{3})$")

    fun parse(s: String): DoubleArray? {
        rgb.matchEntire(s.trim())?.let { m ->
            return doubleArrayOf(m.groupValues[1].toDouble() / 255, m.groupValues[2].toDouble() / 255, m.groupValues[3].toDouble() / 255,
                m.groupValues[4].toDoubleOrNull() ?: 1.0)
        }
        hex.matchEntire(s.trim())?.let { m ->
            val h = m.groupValues[1].toInt(16)
            return doubleArrayOf(((h shr 16) and 255) / 255.0, ((h shr 8) and 255) / 255.0, (h and 255) / 255.0,
                m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(16)?.div(255.0) ?: 1.0)
        }
        hex3.matchEntire(s.trim())?.let { m -> val h = m.groupValues[1]; return doubleArrayOf(h[0].digitToInt(16) * 17 / 255.0, h[1].digitToInt(16) * 17 / 255.0, h[2].digitToInt(16) * 17 / 255.0, 1.0) }
        return null
    }

    /** HSL remap: L in 0..0.5 mirrors to light, light tints go very dark, near-white (roads) lands on mid grey. */
    fun dark(r: Double, g: Double, b: Double, a: Double): String {
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2
        var h = 0.0; var s = 0.0
        if (max != min) {
            val d = max - min
            s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
            h = when (max) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } / 6
        }
        val l2 = when {
            l < 0.5 -> 1.0 - l * 0.4                   // text and dark marks → light
            l < 0.92 -> 0.5 - (l - 0.5) * 0.9          // tints → dark (0.92 → 0.12)
            else -> 0.12 + (l - 0.92) * 4.0            // near white (roads) → 0.12..0.44
        }.coerceIn(0.05, 0.95)
        val s2 = if (l >= 0.5) s * 0.5 else s * 0.7
        val (rr, gg, bb) = hslToRgb(h, s2, l2)
        return if (a >= 1.0) "rgb(${(rr * 255).toInt()},${(gg * 255).toInt()},${(bb * 255).toInt()})"
        else "rgba(${(rr * 255).toInt()},${(gg * 255).toInt()},${(bb * 255).toInt()},${String.format(java.util.Locale.US, "%.2f", a)})"
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Double, Double, Double> {
        if (s == 0.0) return Triple(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(tt: Double): Double { var t = tt; if (t < 0) t += 1; if (t > 1) t -= 1
            return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p } }
        return Triple(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3))
    }

    /** Is it night at this place now? Sun below the horizon by the NOAA approximation (minutes of accuracy). */
    fun isNight(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val hourUtc = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val gamma = 2 * Math.PI / 365 * (doy - 1 + (hourUtc - 12) / 24)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latR = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosHa >= 1) return true     // polar night
        if (cosHa <= -1) return false   // midnight sun
        val ha = Math.toDegrees(acos(cosHa))
        val sunrise = 720 - 4 * (lon + ha) - eqTime   // minutes UTC
        val sunset = 720 - 4 * (lon - ha) - eqTime
        val nowMin = hourUtc * 60
        return nowMin < sunrise || nowMin > sunset
    }
}
