package com.freedomfighter.swissbikehike

import android.content.Context
import java.io.File
import kotlin.math.cos

/**
 * The signposted cycling and mountain-bike routes as the map draws them: the lines of the tours
 * pack ([SwissTours]) written once as a GeoJSON file the map reads. They are on the phone with the
 * app, so the map asks no server for them and shows them without a network.
 */
object Routes {
    /** Thinning of a line for the map, in metres: far below what a line three pixels wide shows. */
    private const val TOL_M = 4.0

    /** The file's URL for the map style, made when it is missing (a new tours pack makes a new one). Not on the main thread. */
    fun url(ctx: Context): String? = runCatching {
        val pack = SwissTours.load(ctx)
        val stamp = (pack.stamp + pack.built).filter { it.isLetterOrDigit() }
        val f = File(ctx.filesDir, "routes-$stamp.geojson")
        if (!f.exists()) synchronized(this) {
            if (!f.exists()) {
                val all = (pack.file?.inputStream() ?: ctx.assets.open("tours.pack")).use { it.readBytes() }
                val tmp = File(ctx.filesDir, "routes.tmp")
                tmp.bufferedWriter().use { w -> write(w, pack, all) }
                ctx.filesDir.listFiles { x -> x.name.startsWith("routes-") && x.name.endsWith(".geojson") }?.forEach { it.delete() }
                tmp.renameTo(f)
            }
        }
        "file://" + f.absolutePath
    }.getOrNull()

    /** One line per stage, with `l` = bike or mtb. */
    fun write(w: Appendable, pack: SwissTours.Pack, all: ByteArray) {
        w.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (t in pack.tours) {
            if (t.land != SwissTours.BIKE && t.land != SwissTours.MTB) continue
            val from = (pack.linesAt + t.offset).toInt()
            val pts = thin(SwissTours.decode(all.copyOfRange(from, from + t.bytes)), TOL_M)
            if (pts.size < 2) continue
            if (!first) w.append(','); first = false
            w.append("{\"type\":\"Feature\",\"properties\":{\"l\":\"").append(t.land).append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            for ((i, p) in pts.withIndex()) {
                if (i > 0) w.append(',')
                w.append('[').append(five(p.lon)).append(',').append(five(p.lat)).append(']')
            }
            w.append("]}}")
        }
        w.append("]}")
    }

    /** Five decimals: a metre. */
    private fun five(v: Double): String {
        val n = Math.round(v * 100000)
        val a = Math.abs(n)
        return (if (n < 0) "-" else "") + (a / 100000) + "." + (a % 100000).toString().padStart(5, '0')
    }

    /** Douglas-Peucker: drops the points less than `tol` metres off the line through their kept neighbours. */
    fun thin(pts: List<TrackPoint>, tol: Double): List<TrackPoint> {
        if (pts.size < 3) return pts
        val kx = 111_320.0 * cos(Math.toRadians(pts[0].lat)); val ky = 110_540.0
        val keep = BooleanArray(pts.size); keep[0] = true; keep[pts.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to pts.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = pts[a].lon * kx; val ay = pts[a].lat * ky
            val dx = pts[b].lon * kx - ax; val dy = pts[b].lat * ky - ay
            val len2 = dx * dx + dy * dy
            var worst = -1.0; var at = -1
            for (i in a + 1 until b) {
                val px = pts[i].lon * kx - ax; val py = pts[i].lat * ky - ay
                val d2 = if (len2 == 0.0) px * px + py * py else {
                    val u = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                    val ex = px - u * dx; val ey = py - u * dy; ex * ex + ey * ey
                }
                if (d2 > worst) { worst = d2; at = i }
            }
            if (worst > tol * tol) { keep[at] = true; stack.addLast(a to at); stack.addLast(at to b) }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }
}
