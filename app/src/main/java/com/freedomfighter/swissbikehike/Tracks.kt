package com.freedomfighter.swissbikehike

import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

/** A line read from a file: its name, what it is for when the file says so ("" otherwise), its points in order. */
class Imported(val name: String, val kind: String, val points: List<TrackPoint>)

/**
 * Reads the lines of a file, whatever its format: GPX (tracks and routes), KML and KMZ (lines and
 * gx:Track), GeoJSON (LineString, MultiLineString), TCX and FIT (Garmin). One file may hold several
 * lines. Unknown formats and files without a line give an empty list.
 */
object Tracks {
    /** Above this many points a line is thinned (2 m tolerance): a recording at one point a second is far denser than the map can use. */
    const val MAX_POINTS = 4000

    fun read(bytes: ByteArray, fileName: String = ""): List<Imported> {
        val fallback = fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim()
        val found = when {
            bytes.size > 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() && bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte() ->
                runCatching { fit(bytes) }.getOrDefault(emptyList())
            bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> zip(bytes)
            else -> text(bytes.toString(Charsets.UTF_8))
        }
        return found.map { clean(it) }.filter { it.points.size >= 2 }
            .mapIndexed { i, t -> if (t.name.isNotBlank()) t else Imported(if (found.size > 1 && fallback.isNotBlank()) "$fallback ${i + 1}" else fallback, t.kind, t.points) }
    }

    private fun zip(bytes: ByteArray): List<Imported> {
        val out = ArrayList<Imported>()
        ZipInputStream(bytes.inputStream()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name.lowercase(Locale.US)
                if (!e.isDirectory && (n.endsWith(".kml") || n.endsWith(".gpx") || n.endsWith(".geojson") || n.endsWith(".json") || n.endsWith(".tcx")))
                    out.addAll(text(z.readBytes().toString(Charsets.UTF_8)))
                e = z.nextEntry
            }
        }
        return out
    }

    private fun text(raw: String): List<Imported> {
        val t = raw.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val head = t.take(2000)
        return runCatching {
            when {
                t.startsWith("<") && head.contains("<gpx", true) -> gpx(t)
                t.startsWith("<") && head.contains("<kml", true) -> kml(t)
                t.startsWith("<") && head.contains("<TrainingCenterDatabase", true) -> tcx(t)
                t.startsWith("{") -> geojson(JSONObject(t))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Twice the same point in a row, or a point outside the world, is dropped; a very dense line is thinned. */
    fun clean(t: Imported): Imported {
        val pts = ArrayList<TrackPoint>(t.points.size)
        for (p in t.points) {
            if (p.lat.isNaN() || p.lon.isNaN() || p.lat < -90 || p.lat > 90 || p.lon < -180 || p.lon > 180) continue
            if (p.lat == 0.0 && p.lon == 0.0) continue
            val l = pts.lastOrNull()
            if (l != null && l.lat == p.lat && l.lon == p.lon) continue
            pts.add(p)
        }
        if (pts.size <= MAX_POINTS) return Imported(t.name.trim(), t.kind, pts)
        val keep = Trip.simplify(pts.map { LatLng(it.lat, it.lon) }, 2.0)
        return Imported(t.name.trim(), t.kind, keep.map { pts[it] })
    }

    // ---- GPX ----

    private val ptRe = Regex("<(trkpt|rtept)\\b([^>]*?)(?:/>|>(.*?)</\\1\\s*>)", RegexOption.DOT_MATCHES_ALL)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun gpx(t: String): List<Imported> {
        val out = ArrayList<Imported>()
        val fileName = blocks(t, "metadata").firstOrNull()?.let { tag(it, "name") } ?: ""
        for (tagName in listOf("trk", "rte")) for (b in blocks(t, tagName)) {
            val pts = ptRe.findAll(b).mapNotNull { m ->
                val lat = attr(m.groupValues[2], "lat") ?: return@mapNotNull null
                val lon = attr(m.groupValues[2], "lon") ?: return@mapNotNull null
                val body = m.groupValues[3]
                TrackPoint(lat, lon, tag(body, "ele")?.toDoubleOrNull() ?: Double.NaN, time(tag(body, "time")))
            }.toList()
            // the name of the track is the one before its first point, not a waypoint's inside it
            val headOfBlock = b.substringBefore("<trkseg").substringBefore("<rtept").substringBefore("<trkpt")
            out.add(Imported(tag(headOfBlock, "name") ?: fileName, kindOf(tag(headOfBlock, "type") ?: ""), pts))
        }
        return out
    }

    private fun time(s: String?): Long = if (s == null || s.length < 19) 0L else runCatching { iso.parse(s.take(19) + "Z")?.time ?: 0L }.getOrDefault(0L)

    /** What a file calls the activity, read as on foot, by bike or by mountain bike; "" when it does not say. */
    fun kindOf(type: String): String {
        val s = type.lowercase(Locale.US)
        return when {
            s.isBlank() -> ""
            "mtb" in s || "mountain" in s && "bik" in s || "vtt" in s -> Tours.MTB
            "cycl" in s || "bik" in s || "ride" in s || "velo" in s || "vélo" in s || "rad" in s -> Tours.BIKE
            "hik" in s || "walk" in s || "run" in s || "foot" in s || "wander" in s || "march" in s || "rando" in s -> Tours.WALK
            else -> ""
        }
    }

    // ---- KML ----

    private fun kml(t: String): List<Imported> {
        val out = ArrayList<Imported>()
        for (b in blocks(t, "Placemark")) {
            val pts = ArrayList<TrackPoint>()
            for (ls in blocks(b, "LineString")) tag(ls, "coordinates", raw = true)?.let { pts.addAll(kmlCoords(it)) }
            for (tr in blocks(b, "gx:Track") + blocks(b, "Track"))
                Regex("<(?:gx:)?coord>\\s*([-\\d.eE]+)\\s+([-\\d.eE]+)(?:\\s+([-\\d.eE]+))?").findAll(tr).forEach { m ->
                    val lon = m.groupValues[1].toDoubleOrNull(); val lat = m.groupValues[2].toDoubleOrNull()
                    if (lat != null && lon != null) pts.add(TrackPoint(lat, lon, m.groupValues[3].toDoubleOrNull() ?: Double.NaN, 0L))
                }
            if (pts.size >= 2) out.add(Imported(tag(b.substringBefore("<LineString").substringBefore("<MultiGeometry").substringBefore("<gx:"), "name") ?: "", "", pts))
        }
        return out
    }

    private fun kmlCoords(s: String): List<TrackPoint> = s.trim().split(Regex("\\s+")).mapNotNull { tuple ->
        val c = tuple.split(',')
        val lon = c.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val lat = c.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        // an altitude of exactly 0 everywhere is KML's way of saying "none": kept as it is, the profile then stays flat
        TrackPoint(lat, lon, c.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN, 0L)
    }

    // ---- TCX ----

    private fun tcx(t: String): List<Imported> {
        val pts = ArrayList<TrackPoint>()
        for (b in blocks(t, "Trackpoint")) {
            val lat = tag(b, "LatitudeDegrees")?.toDoubleOrNull() ?: continue
            val lon = tag(b, "LongitudeDegrees")?.toDoubleOrNull() ?: continue
            pts.add(TrackPoint(lat, lon, tag(b, "AltitudeMeters")?.toDoubleOrNull() ?: Double.NaN, time(tag(b, "Time"))))
        }
        val course = blocks(t, "Course").firstOrNull()?.let { tag(it.substringBefore("<Lap").substringBefore("<Track"), "Name") }
        val sport = Regex("<Activity\\s+Sport=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
        return listOf(Imported(course ?: "", kindOf(sport), pts))
    }

    // ---- GeoJSON ----

    private fun geojson(root: JSONObject): List<Imported> {
        val out = ArrayList<Imported>()
        fun line(a: JSONArray): List<TrackPoint> = (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONArray(i) ?: return@mapNotNull null
            if (c.length() < 2) return@mapNotNull null
            TrackPoint(c.getDouble(1), c.getDouble(0), if (c.length() > 2 && !c.isNull(2)) c.optDouble(2, Double.NaN) else Double.NaN, 0L)
        }
        fun geometry(g: JSONObject?, name: String) {
            g ?: return
            when (g.optString("type")) {
                "LineString" -> out.add(Imported(name, "", line(g.getJSONArray("coordinates"))))
                "MultiLineString" -> { val a = g.getJSONArray("coordinates"); out.add(Imported(name, "", (0 until a.length()).flatMap { line(a.getJSONArray(it)) })) }
                "GeometryCollection" -> { val a = g.getJSONArray("geometries"); for (i in 0 until a.length()) geometry(a.getJSONObject(i), name) }
            }
        }
        fun feature(f: JSONObject) {
            val p = f.optJSONObject("properties") ?: JSONObject()
            val name = listOf("name", "title", "Name", "label").firstNotNullOfOrNull { k -> p.optString(k).takeIf { it.isNotBlank() } } ?: ""
            geometry(f.optJSONObject("geometry"), name)
        }
        when (root.optString("type")) {
            "FeatureCollection" -> { val a = root.getJSONArray("features"); for (i in 0 until a.length()) feature(a.getJSONObject(i)) }
            "Feature" -> feature(root)
            else -> geometry(root, "")
        }
        return out
    }

    // ---- FIT (Garmin's binary format): the record messages carry the positions ----

    private class FitField(val num: Int, val size: Int)
    private class FitDef(val little: Boolean, val global: Int, val fields: List<FitField>, val devBytes: Int)

    private fun fit(bytes: ByteArray): List<Imported> {
        val headerSize = bytes[0].toInt() and 0xFF
        val dataSize = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val end = minOf(bytes.size, headerSize + dataSize)
        val defs = HashMap<Int, FitDef>()
        val pts = ArrayList<TrackPoint>()
        var sport = -1
        var pos = headerSize
        while (pos < end) {
            val h = bytes[pos].toInt() and 0xFF; pos++
            val compressed = h and 0x80 != 0
            val local = if (compressed) (h shr 5) and 0x3 else h and 0x0F
            if (!compressed && h and 0x40 != 0) {
                val little = bytes[pos + 1].toInt() == 0
                val global = ByteBuffer.wrap(bytes, pos + 2, 2).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                val n = bytes[pos + 4].toInt() and 0xFF
                pos += 5
                val fields = (0 until n).map { FitField(bytes[pos + it * 3].toInt() and 0xFF, bytes[pos + it * 3 + 1].toInt() and 0xFF) }
                pos += n * 3
                var dev = 0
                if (h and 0x20 != 0) {
                    val dn = bytes[pos].toInt() and 0xFF; pos++
                    for (i in 0 until dn) dev += bytes[pos + i * 3 + 1].toInt() and 0xFF
                    pos += dn * 3
                }
                defs[local] = FitDef(little, global, fields, dev)
                continue
            }
            val d = defs[local] ?: break
            var lat = Double.NaN; var lon = Double.NaN; var ele = Double.NaN; var time = 0L
            for (f in d.fields) {
                val bb = ByteBuffer.wrap(bytes, pos, f.size).order(if (d.little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                if (d.global == 20) when {
                    f.num == 0 && f.size == 4 -> bb.int.let { if (it != 0x7FFFFFFF) lat = it * (180.0 / 2147483648.0) }
                    f.num == 1 && f.size == 4 -> bb.int.let { if (it != 0x7FFFFFFF) lon = it * (180.0 / 2147483648.0) }
                    f.num == 2 && f.size == 2 -> (bb.short.toInt() and 0xFFFF).let { if (it != 0xFFFF && ele.isNaN()) ele = it / 5.0 - 500.0 }
                    f.num == 78 && f.size == 4 -> (bb.int.toLong() and 0xFFFFFFFFL).let { if (it != 0xFFFFFFFFL) ele = it / 5.0 - 500.0 }
                    // seconds since the last day of 1989, UTC
                    f.num == 253 && f.size == 4 -> (bb.int.toLong() and 0xFFFFFFFFL).let { if (it != 0xFFFFFFFFL) time = (it + 631065600L) * 1000L }
                } else if ((d.global == 12 || d.global == 18) && f.num == (if (d.global == 12) 0 else 5) && f.size == 1 && sport < 0) sport = bb.get().toInt() and 0xFF
                pos += f.size
            }
            pos += d.devBytes
            if (d.global == 20 && !lat.isNaN() && !lon.isNaN()) pts.add(TrackPoint(lat, lon, ele, time))
        }
        // FIT's sport numbers: 1 running, 2 cycling, 11 walking, 17 hiking
        val kind = when (sport) { 2 -> Tours.BIKE; 1, 11, 17 -> Tours.WALK; else -> "" }
        return listOf(Imported("", kind, pts))
    }

    // ---- XML by hand: the files are simple and this reads the same on the phone and in the tests ----

    /** The inside of every <name …>…</name> element, in order. */
    private fun blocks(t: String, name: String): List<String> {
        val out = ArrayList<String>()
        val close = "</$name"
        var i = 0
        while (true) {
            val s = t.indexOf("<$name", i); if (s < 0) break
            val after = t.getOrNull(s + name.length + 1)
            if (after != null && after != '>' && after != ' ' && after != '\n' && after != '\r' && after != '\t' && after != '/') { i = s + 1; continue }
            val open = t.indexOf('>', s); if (open < 0) break
            if (t[open - 1] == '/') { i = open + 1; continue }
            // "</trk" also begins "</trkseg>": the closing tag is the one that ends right after the name
            var e = t.indexOf(close, open)
            while (e >= 0 && t.getOrNull(e + close.length).let { it != '>' && it != ' ' && it != '\n' && it != '\r' && it != '\t' }) e = t.indexOf(close, e + 1)
            if (e < 0) break
            out.add(t.substring(open + 1, e))
            i = e + close.length
        }
        return out
    }

    private fun tag(body: String, name: String, raw: Boolean = false): String? {
        val inner = blocks(body, name).firstOrNull() ?: return null
        if (raw) return inner
        return inner.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
            .trim().takeIf { it.isNotBlank() }
    }

    private fun attr(attrs: String, name: String): Double? =
        Regex("\\b$name\\s*=\\s*[\"']\\s*([-+\\d.eE]+)\\s*[\"']").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
}
