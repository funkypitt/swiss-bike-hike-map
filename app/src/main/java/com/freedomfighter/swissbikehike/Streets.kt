package com.freedomfighter.swissbikehike

import android.content.Context
import org.maplibre.android.geometry.LatLng
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The street names of the places pack (section written by tools/make-places.py after the places,
 * found through the trailer "SBHS"): every named way of OpenStreetMap as a line, by cells of
 * 0.01°. BRouter's data carries no names, so a route's steps are named here, by laying the route
 * over these lines. Packs built before the section existed simply have no streets.
 */
class Streets private constructor(private val b: ByteBuffer, private val sec: Int) {
    companion object {
        private const val MAGIC = 0x53484253 // "SBHS"
        private const val CELL = 0.01
        private const val LAT0 = 45.0; private const val LON0 = 5.0
        /** How far a route point may be from a street to take its name. */
        private const val NEAR_M = 14.0
        /** The largest angle between the route and the street, in degrees. */
        private const val ALONG_DEG = 35.0
        private const val SAMPLE_M = 15.0

        /** The streets section of a pack's buffer, or null when the pack predates it. */
        fun of(b: ByteBuffer): Streets? {
            val n = b.capacity(); if (n < 64) return null
            if (b.getInt(n - 4) != MAGIC) return null
            val sec = b.getInt(n - 8)
            return if (sec in 32 until n - 8) Streets(b, sec) else null
        }

        fun open(ctx: Context): Streets? = PlacesPack.open(ctx)?.streets

        /**
         * One name per step of a route: [0] = from the start to the first turn, [k + 1] = after turn k.
         * Blank where no street of the pack runs along the route (paths without a name, fields…).
         */
        fun names(ctx: Context, points: List<LatLng>, turns: List<Router.Turn>): List<String> {
            val s = open(ctx) ?: return emptyList()
            return runCatching { s.stepNames(points, turns) }.getOrDefault(emptyList())
        }
    }

    private val nWays = b.getInt(sec)
    private val nSteps = b.getInt(sec + 4)
    private val nIndex = b.getInt(sec + 8)
    private val secWays = sec + 16
    private val secSteps = secWays + 20 * nWays
    private val secIndex = secSteps + 4 * nSteps
    private val secRefs = secIndex + 8 * nIndex
    /** Decoded ways, kept while a route is named (a few hundred at most). */
    private val cache = HashMap<Int, IntArray>()
    private var strings: ((Int) -> String)? = null

    internal fun bind(string: (Int) -> String) { strings = string }

    val size: Int get() = nWays

    private fun nameOf(w: Int): String = strings?.invoke(b.getInt(secWays + 20 * w)) ?: ""

    /** The way's points as lat, lon pairs in 1e-6°. */
    private fun points(w: Int): IntArray = cache.getOrPut(w) {
        val o = secWays + 20 * w
        val first = b.getInt(o + 4)
        var lat = b.getInt(o + 8); var lon = b.getInt(o + 12)
        val n = b.getShort(o + 16).toInt() and 0xFFFF
        val out = IntArray(2 * n)
        out[0] = lat; out[1] = lon
        for (k in 1 until n) {
            val s = secSteps + 4 * (first + k - 1)
            lat += b.getShort(s); lon += b.getShort(s + 2)
            out[2 * k] = lat; out[2 * k + 1] = lon
        }
        out
    }

    private fun cellWays(key: Int): IntRange? {
        var lo = 0; var hi = nIndex - 2
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = b.getInt(secIndex + 8 * mid)
            when {
                k < key -> lo = mid + 1
                k > key -> hi = mid - 1
                else -> return b.getInt(secIndex + 8 * mid + 4) until b.getInt(secIndex + 8 * (mid + 1) + 4)
            }
        }
        return null
    }

    /**
     * The street a short piece of route (a at p, heading towards q) runs along: nearest within
     * NEAR_M whose direction matches within ALONG_DEG. -1 when none.
     */
    private fun along(lat: Double, lon: Double, dLat: Double, dLon: Double): Int {
        val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 110_540.0
        val rx = dLon * kx; val ry = dLat * ky
        val rl = sqrt(rx * rx + ry * ry); if (rl < 0.5) return -1
        val cy = floor((lat - LAT0) / CELL).toInt(); val cx = floor((lon - LON0) / CELL).toInt()
        var best = -1; var bestD = NEAR_M
        val cosMax = cos(Math.toRadians(ALONG_DEG))
        val seen = HashSet<Int>()
        for (dy in -1..1) for (dx in -1..1) {
            val range = cellWays((cy + dy) * 2048 + (cx + dx)) ?: continue
            for (r in range) {
                val w = b.getInt(secRefs + 4 * r)
                if (!seen.add(w)) continue
                val p = points(w)
                for (k in 0 until p.size / 2 - 1) {
                    val ax = (p[2 * k + 1] / 1e6 - lon) * kx; val ay = (p[2 * k] / 1e6 - lat) * ky
                    val bx = (p[2 * k + 3] / 1e6 - lon) * kx; val by = (p[2 * k + 2] / 1e6 - lat) * ky
                    val vx = bx - ax; val vy = by - ay
                    val len2 = vx * vx + vy * vy; if (len2 < 0.01) continue
                    val t = (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
                    val px = ax + t * vx; val py = ay + t * vy
                    val d = sqrt(px * px + py * py)
                    if (d >= bestD) continue
                    // the street's direction either way
                    if (abs((vx * rx + vy * ry) / (sqrt(len2) * rl)) < cosMax) continue
                    bestD = d; best = w
                }
            }
        }
        return best
    }

    /** See [Streets.names]. */
    fun stepNames(points: List<LatLng>, turns: List<Router.Turn>): List<String> {
        if (points.size < 2) return emptyList()
        cache.clear()
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + points[i - 1].distanceTo(points[i])
        val bounds = ArrayList<Int>(); bounds.add(0); for (t in turns) bounds.add(t.index); if (bounds.last() != points.size - 1) bounds.add(points.size - 1)
        val out = ArrayList<String>()
        for (s in 0 until bounds.size - 1) {
            val i0 = bounds[s]; val i1 = bounds[s + 1]
            if (i1 <= i0) { out.add(""); continue }
            // samples along the step, a little away from both turns (where the crossing street is near)
            val from = cum[i0] + 8.0; val to = cum[i1] - 8.0
            val votes = HashMap<String, Double>(); var total = 0.0
            var d = from; var i = i0
            val step = if (to - from > 60 * SAMPLE_M) (to - from) / 60 else SAMPLE_M
            while (d <= to || (total == 0.0 && d <= cum[i1])) {
                while (i < i1 - 1 && cum[i + 1] < d) i++
                val a = points[i]; val c = points[i + 1]
                val seg = cum[i + 1] - cum[i]
                val f = if (seg < 0.1) 0.0 else ((d - cum[i]) / seg).coerceIn(0.0, 1.0)
                val lat = a.latitude + f * (c.latitude - a.latitude); val lon = a.longitude + f * (c.longitude - a.longitude)
                val w = along(lat, lon, c.latitude - a.latitude, c.longitude - a.longitude)
                total += step
                if (w >= 0) { val n = nameOf(w); if (n.isNotBlank()) votes[n] = (votes[n] ?: 0.0) + step }
                d += step
                if (to - from <= 0 && total > 0) break
            }
            val top = votes.maxByOrNull { it.value }
            out.add(if (top != null && top.value >= 0.4 * total) top.key else "")
        }
        cache.clear()
        while (out.size < turns.size + 1) out.add("")
        return out
    }
}
