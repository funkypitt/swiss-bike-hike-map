package com.freedomfighter.swissbikehike

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A tour kept on the phone: a line with a name, what it is for, and its figures. Its points are in a GPX file beside the list. */
class Tour(
    val file: String, val name: String, val kind: String, val distanceM: Int, val ascendM: Int, val descendM: Int,
    val addedAt: Long, val source: String, val lat: Double, val lon: Double, val hash: String,
) {
    fun with(name: String = this.name, kind: String = this.kind) = Tour(file, name, kind, distanceM, ascendM, descendM, addedAt, source, lat, lon, hash)
}

/**
 * The tours of the person: lines imported from files, and recorded tracks. Private storage:
 * files/tours/index.json and one GPX per tour.
 */
object Tours {
    const val WALK = "walk"; const val BIKE = "bike"; const val MTB = "mtb"
    const val IMPORTED = "import"; const val RECORDED = "rec"
    val KINDS = listOf(WALK, BIKE, MTB)

    fun kindOf(mode: Mode) = if (mode == Mode.BIKE) BIKE else WALK
    fun kindLabel(kind: String) = when (kind) { BIKE -> R.string.tour_bike; MTB -> R.string.tour_mtb; else -> R.string.tour_walk }

    private fun dir(ctx: Context) = File(ctx.filesDir, "tours").apply { mkdirs() }
    private fun index(ctx: Context) = File(dir(ctx), "index.json")
    fun file(ctx: Context, r: Tour) = File(dir(ctx), r.file)

    @Synchronized fun list(ctx: Context): List<Tour> {
        val f = index(ctx); if (!f.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { i -> val o = a.getJSONObject(i)
                Tour(o.getString("file"), o.optString("name"), o.optString("kind", WALK), o.optInt("dist"), o.optInt("asc"), o.optInt("desc"),
                    o.optLong("at"), o.optString("src", IMPORTED), o.optDouble("lat", 0.0), o.optDouble("lon", 0.0), o.optString("hash")) }
        }.getOrDefault(emptyList()).sortedByDescending { it.addedAt }
    }

    private fun write(ctx: Context, all: List<Tour>) {
        val a = JSONArray()
        for (r in all) a.put(JSONObject().put("file", r.file).put("name", r.name).put("kind", r.kind).put("dist", r.distanceM).put("asc", r.ascendM)
            .put("desc", r.descendM).put("at", r.addedAt).put("src", r.source).put("lat", r.lat).put("lon", r.lon).put("hash", r.hash))
        val tmp = File(dir(ctx), "index.json.tmp"); tmp.writeText(a.toString()); tmp.renameTo(index(ctx))
    }

    /** The same line twice is the same tour: recognised by its points, whatever the file was called. */
    fun hash(pts: List<TrackPoint>): String {
        val md = MessageDigest.getInstance("SHA-1")
        for (p in pts) md.update(String.format(Locale.US, "%.5f,%.5f;", p.lat, p.lon).toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    class Figures(val distanceM: Int, val ascendM: Int, val descendM: Int)

    fun figures(pts: List<TrackPoint>): Figures {
        var d = 0.0; val r = FloatArray(1)
        for (i in 1 until pts.size) { android.location.Location.distanceBetween(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon, r); d += r[0] }
        val (asc, desc) = Trip.climb(DoubleArray(pts.size) { pts[it].ele })
        return Figures(d.toInt(), asc, desc)
    }

    /** Keeps a line; the one already kept comes back when the same line is added again (second = false). */
    @Synchronized fun add(ctx: Context, t: Imported, kind: String, source: String, at: Long = System.currentTimeMillis()): Pair<Tour, Boolean> {
        val all = list(ctx)
        val h = hash(t.points)
        all.firstOrNull { it.hash == h }?.let { return Pair(it, false) }
        var n = at
        while (File(dir(ctx), "$n.gpx").exists()) n++
        val name = t.name.ifBlank { ctx.getString(R.string.tour_unnamed) }
        File(dir(ctx), "$n.gpx").writeText(Gpx.write(name, t.points))
        val f = figures(t.points)
        val r = Tour("$n.gpx", name, t.kind.ifBlank { kind }, f.distanceM, f.ascendM, f.descendM, at, source, t.points[0].lat, t.points[0].lon, h)
        write(ctx, listOf(r) + all)
        return Pair(r, true)
    }

    @Synchronized fun update(ctx: Context, r: Tour) { write(ctx, list(ctx).map { if (it.file == r.file) r else it }) }

    @Synchronized fun remove(ctx: Context, r: Tour) { file(ctx, r).delete(); write(ctx, list(ctx).filterNot { it.file == r.file }) }

    fun track(ctx: Context, r: Tour): Track? =
        runCatching { file(ctx, r).inputStream().use { Gpx.readTrack(it) } }.getOrNull()?.takeIf { it.points.size >= 2 }?.let { Track(r.name, it.points) }

    /** What an import did: the tours added, those that were already there, and whether the file held a line at all. */
    class Result(val added: List<Tour>, val known: List<Tour>) {
        val all: List<Tour> get() = added + known
    }

    /** Reads a file handed by the file picker or another app and keeps its lines. Null when it cannot be read. */
    fun import(ctx: Context, uri: Uri, kind: String): Result? {
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
        val found = Tracks.read(bytes, displayName(ctx, uri))
        val added = ArrayList<Tour>(); val known = ArrayList<Tour>()
        for (t in found) { val (r, new) = add(ctx, t, kind, IMPORTED); if (new) added.add(r) else known.add(r) }
        return Result(added, known)
    }

    fun displayName(ctx: Context, uri: Uri): String {
        val fromProvider = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /** A file name any system accepts, from the tour's name. */
    fun fileName(r: Tour): String =
        r.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "tour" } + ".gpx"
}
