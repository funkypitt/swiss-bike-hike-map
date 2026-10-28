package com.freedomfighter.swissbikehike

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One navigated trip, kept on the phone: when, which mode, where to, how far, how long, and its GPX. */
class TripRecord(val startedAt: Long, val mode: Mode, val dest: String, val distanceM: Int, val seconds: Int, val file: String) {
    fun label(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(startedAt)) + "  " + mode.name +
        "\n" + dest + "  ·  " + Fmt.km(distanceM.toDouble()) + " km  " + Fmt.hm(seconds)
}

/** The local history of navigated trips: a JSON list plus one GPX per trip in the app's private storage. */
object Trips {
    private const val KEEP = 200
    private fun dir(ctx: Context) = File(ctx.filesDir, "trips").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "history.json")

    fun list(ctx: Context): List<TripRecord> {
        val f = index(ctx); if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i -> val o = arr.getJSONObject(i)
                TripRecord(o.getLong("at"), Mode.of(o.optString("mode")), o.optString("dest"), o.optInt("dist"), o.optInt("sec"), o.optString("file")) }
        }.getOrDefault(emptyList()).sortedByDescending { it.startedAt }
    }

    fun add(ctx: Context, startedAt: Long, mode: Mode, dest: String, pts: List<TrackPoint>): TripRecord? {
        if (pts.size < 2) return null
        var dist = 0.0
        for (i in 1 until pts.size) dist += distance(pts[i - 1], pts[i])
        if (dist < 100) return null
        val name = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(startedAt)) + "_" + mode.name.lowercase() + ".gpx"
        File(dir(ctx), name).writeText(Gpx.write(dest, pts))
        val rec = TripRecord(startedAt, mode, dest, dist.toInt(), ((pts.last().time - pts.first().time) / 1000).toInt().coerceAtLeast(0), name)
        val all = (listOf(rec) + list(ctx)).take(KEEP)
        for (old in list(ctx).drop(KEEP - 1)) File(dir(ctx), old.file).delete()
        index(ctx).writeText(JSONArray().also { arr -> for (r in all) arr.put(JSONObject().put("at", r.startedAt).put("mode", r.mode.name)
            .put("dest", r.dest).put("dist", r.distanceM).put("sec", r.seconds).put("file", r.file)) }.toString())
        return rec
    }

    fun file(ctx: Context, r: TripRecord) = File(dir(ctx), r.file)

    fun remove(ctx: Context, r: TripRecord) {
        file(ctx, r).delete()
        val rest = list(ctx).filterNot { it.file == r.file }
        index(ctx).writeText(JSONArray().also { arr -> for (x in rest) arr.put(JSONObject().put("at", x.startedAt).put("mode", x.mode.name)
            .put("dest", x.dest).put("dist", x.distanceM).put("sec", x.seconds).put("file", x.file)) }.toString())
    }

    private fun distance(a: TrackPoint, b: TrackPoint): Double {
        val r = FloatArray(1); android.location.Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, r); return r[0].toDouble()
    }
}
