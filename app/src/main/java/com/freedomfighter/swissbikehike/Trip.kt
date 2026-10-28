package com.freedomfighter.swissbikehike

import btools.router.TurnHints
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt



/** A stop of the trip: where, and what to call it. The last one is the destination. */
class Stop(val at: LatLng, val name: String, val kind: String = Favourites.COORDS, val detail: String = "")

/**
 * What the map follows: a computed route through the stops, or a GPX track. Distances along the
 * line are precomputed; the elevations feed the profile; the turns feed the navigation panel.
 */
class Trip(
    val points: List<LatLng>,
    val ele: DoubleArray,            // NaN where unknown
    val turns: List<Router.Turn>,
    val distanceM: Int,
    val ascendM: Int,
    val descendM: Int,
    val seconds: Int,                // 0 when unknown (GPX)
    val stops: List<Stop>,
    val fromGpx: Boolean,
    val name: String,
) {
    val cum: DoubleArray = DoubleArray(points.size).also { c -> for (i in 1 until points.size) c[i] = c[i - 1] + points[i - 1].distanceTo(points[i]) }
    /** Route point index nearest to each stop. */
    val stopIdx: IntArray = IntArray(stops.size) { s -> nearest(stops[s].at) }
    val hasElevation: Boolean get() = ele.any { !it.isNaN() }

    fun nearest(p: LatLng): Int {
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in points.indices) { val d = p.distanceTo(points[i]); if (d < bestD) { bestD = d; best = i } }
        return best
    }

    companion object {
        fun fromRoute(r: Router.Result, stops: List<Stop>) =
            Trip(r.points, r.elevations, r.turns, r.distanceM, r.ascendM, r.descendM, r.seconds, stops, false, stops.last().name)

        /** A GPX track becomes a trip: its end is the only stop; the turns come from its shape. */
        fun fromTrack(t: Track, name: String): Trip? {
            if (t.points.size < 2) return null
            val pts = t.latLngs
            val ele = DoubleArray(pts.size) { t.points[it].ele }
            var dist = 0.0
            for (i in 1 until pts.size) dist += pts[i - 1].distanceTo(pts[i])
            val (asc, desc) = climb(ele)
            val end = pts.last()
            return Trip(pts, ele, turnsFromShape(pts), dist.toInt(), asc, desc, 0,
                listOf(Stop(end, name.ifBlank { Favourites.coordsName(end.latitude, end.longitude) })), true, name)
        }

        /** Climb and descent with a 10 m hysteresis, as for routes. */
        fun climb(ele: DoubleArray): Pair<Int, Int> {
            var asc = 0.0; var desc = 0.0; var lo = Double.NaN; var hi = Double.NaN
            for (e in ele) {
                if (e.isNaN()) continue
                if (lo.isNaN()) { lo = e; hi = e; continue }
                if (e > hi) hi = e
                if (e < lo) lo = e
                if (e - lo >= 10.0) { asc += e - lo; lo = e; hi = e }
                if (hi - e >= 10.0) { desc += hi - e; hi = e; lo = e }
            }
            return Pair(asc.toInt(), desc.toInt())
        }

        /** Turns read off the geometry: simplify to 6 m, then every heading change of 30° or more. */
        fun turnsFromShape(pts: List<LatLng>): List<Router.Turn> {
            val keep = simplify(pts, 6.0)
            val out = ArrayList<Router.Turn>()
            var lastIdx = -1
            for (k in 1 until keep.size - 1) {
                val a = pts[keep[k - 1]]; val b = pts[keep[k]]; val c = pts[keep[k + 1]]
                var d = heading(b, c) - heading(a, b)
                if (d > 180) d -= 360; if (d < -180) d += 360
                if (abs(d) < 30) continue
                if (lastIdx >= 0 && pts[lastIdx].distanceTo(b) < 15) continue
                val cmd = when {
                    d <= -135 -> TurnHints.TSHL; d <= -60 -> TurnHints.TL; d < 0 -> TurnHints.TSLL
                    d >= 135 -> TurnHints.TSHR; d >= 60 -> TurnHints.TR; else -> TurnHints.TSLR
                }
                out.add(Router.Turn(keep[k], cmd, 0)); lastIdx = keep[k]
            }
            out.add(Router.Turn(pts.size - 1, TurnHints.END, 0))
            return out
        }

        fun heading(a: LatLng, b: LatLng): Double {
            val la1 = Math.toRadians(a.latitude); val la2 = Math.toRadians(b.latitude); val dl = Math.toRadians(b.longitude - a.longitude)
            val y = sin(dl) * cos(la2); val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        /** Douglas-Peucker on indices, tolerance in metres. */
        fun simplify(pts: List<LatLng>, tol: Double): List<Int> {
            val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>(); stack.add(Pair(0, pts.size - 1))
            while (stack.isNotEmpty()) {
                val (s, e) = stack.removeLast()
                if (e - s < 2) continue
                var worst = -1; var worstD = tol
                for (i in s + 1 until e) { val d = segDist(pts[i], pts[s], pts[e]); if (d > worstD) { worstD = d; worst = i } }
                if (worst >= 0) { keep[worst] = true; stack.add(Pair(s, worst)); stack.add(Pair(worst, e)) }
            }
            return pts.indices.filter { keep[it] }
        }

        /** Distance from p to the segment ab, in metres, on a local flat approximation. */
        fun segDist(p: LatLng, a: LatLng, b: LatLng): Double {
            val k = cos(Math.toRadians(a.latitude)) * 111_320.0; val ky = 111_320.0
            val vx = (b.longitude - a.longitude) * k; val vy = (b.latitude - a.latitude) * ky
            val wx = (p.longitude - a.longitude) * k; val wy = (p.latitude - a.latitude) * ky
            val len2 = vx * vx + vy * vy
            val t = if (len2 < 1e-6) 0.0 else ((wx * vx + wy * vy) / len2).coerceIn(0.0, 1.0)
            val dx = wx - t * vx; val dy = wy - t * vy
            return sqrt(dx * dx + dy * dy)
        }
    }
}
