package com.freedomfighter.swissbikehike

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** A saved place: an address, a person, a firm or bare coordinates. */
class Fav(val name: String, val lat: Double, val lon: Double, val kind: String = Favourites.PLACE, val detail: String = "") {
    fun near(lat: Double, lon: Double) = abs(this.lat - lat) < 2e-5 && abs(this.lon - lon) < 3e-5
}

/**
 * The favourites: a small JSON file in the app's private storage, plus pure readers for lists of
 * places in the usual formats (GPX waypoints, KML/KMZ, GeoJSON, CSV, or plain lines with coordinates).
 */
object Favourites {
    const val PLACE = "place"; const val PERSON = "person"; const val FIRM = "firm"; const val COORDS = "coords"; const val STOP = "stop"

    private fun file(ctx: Context) = File(ctx.filesDir, "favourites.json")

    fun list(ctx: Context): List<Fav> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching { fromJson(f.readText()) }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, favs: List<Fav>) = file(ctx).writeText(toJson(favs))

    /** Adds or replaces (same position = same place). Returns the new list. */
    fun add(ctx: Context, fav: Fav): List<Fav> {
        val l = list(ctx).filterNot { it.near(fav.lat, fav.lon) } + fav
        save(ctx, l); return l
    }

    fun addAll(ctx: Context, favs: List<Fav>): Int {
        var l = list(ctx); var n = 0
        for (f in favs) if (l.none { it.near(f.lat, f.lon) }) { l = l + f; n++ }
        save(ctx, l); return n
    }

    fun remove(ctx: Context, fav: Fav): List<Fav> {
        val l = list(ctx).filterNot { it.near(fav.lat, fav.lon) }
        save(ctx, l); return l
    }

    fun find(ctx: Context, lat: Double, lon: Double): Fav? = list(ctx).firstOrNull { it.near(lat, lon) }

    // ---- JSON store ----

    fun toJson(favs: List<Fav>): String {
        val arr = JSONArray()
        for (f in favs) arr.put(JSONObject().put("name", f.name).put("lat", f.lat).put("lon", f.lon).put("kind", f.kind).put("detail", f.detail))
        return arr.toString()
    }

    fun fromJson(text: String): List<Fav> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Fav(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"), o.optString("kind", PLACE), o.optString("detail", ""))
        }
    }

    // ---- import ----

    /** Reads a file of places, whatever its format; KMZ is unzipped first. Unknown formats give an empty list. */
    fun read(input: InputStream): List<Fav> {
        val bytes = input.readBytes()
        if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name.endsWith(".kml", true) || e.name.endsWith(".gpx", true)) return parse(z.readBytes().toString(Charsets.UTF_8))
                    e = z.nextEntry
                }
            }
            return emptyList()
        }
        return parse(bytes.toString(Charsets.UTF_8))
    }

    fun parse(text: String): List<Fav> {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("<") && t.contains("<gpx", true) -> gpx(t)
            t.startsWith("<") && t.contains("<kml", true) -> kml(t)
            t.startsWith("{") || t.startsWith("[") -> runCatching { geojson(t) }.getOrDefault(emptyList())
            else -> lines(t)
        }
    }

    private fun gpx(t: String): List<Fav> =
        Regex("<wpt\\s+([^>]*)>(.*?)</wpt>", RegexOption.DOT_MATCHES_ALL).findAll(t).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val lat = Regex("lat=\"([-\\d.]+)\"").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = Regex("lon=\"([-\\d.]+)\"").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val body = m.groupValues[2]
            val name = xmlTag(body, "name") ?: coordsName(lat, lon)
            Fav(name, lat, lon, xmlTag(body, "type")?.takeIf { it in setOf(PLACE, PERSON, FIRM, COORDS) } ?: PLACE, xmlTag(body, "desc") ?: "")
        }.toList()

    private fun kml(t: String): List<Fav> =
        Regex("<Placemark[^>]*>(.*?)</Placemark>", RegexOption.DOT_MATCHES_ALL).findAll(t).mapNotNull { m ->
            val body = m.groupValues[1]
            val point = Regex("<Point>.*?<coordinates>\\s*([-\\d.]+)\\s*,\\s*([-\\d.]+)", RegexOption.DOT_MATCHES_ALL).find(body) ?: return@mapNotNull null
            val lon = point.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val lat = point.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            Fav(xmlTag(body, "name") ?: coordsName(lat, lon), lat, lon, PLACE, xmlTag(body, "description") ?: "")
        }.toList()

    private fun geojson(t: String): List<Fav> {
        val root = JSONObject(t)
        val feats = if (root.optString("type") == "FeatureCollection") root.getJSONArray("features") else JSONArray().put(root)
        val out = ArrayList<Fav>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val g = f.optJSONObject("geometry") ?: continue
            if (g.optString("type") != "Point") continue
            val c = g.getJSONArray("coordinates")
            val lon = c.getDouble(0); val lat = c.getDouble(1)
            val p = f.optJSONObject("properties") ?: JSONObject()
            val name = listOf("name", "title", "Name", "label").firstNotNullOfOrNull { k -> p.optString(k).takeIf { it.isNotBlank() } } ?: coordsName(lat, lon)
            out.add(Fav(name, lat, lon, PLACE, p.optString("description", p.optString("desc", ""))))
        }
        return out
    }

    /** Plain lines or CSV: every line holding a pair of coordinates becomes a place; the rest of the line is its name. */
    private fun lines(t: String): List<Fav> = t.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        val (p, span) = coordsIn(line) ?: return@mapNotNull null
        val rest = (line.substring(0, span.first) + " " + line.substring(span.last + 1))
            .split(',', ';', '\t').map { it.trim().trim('"') }.filter { it.isNotBlank() }
        Fav(rest.firstOrNull() ?: coordsName(p.latitude, p.longitude), p.latitude, p.longitude,
            if (rest.isEmpty()) COORDS else PLACE, rest.drop(1).joinToString(", "))
    }

    private fun xmlTag(body: String, name: String): String? =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            ?.replace(Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL), "$1")
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")?.replace("&amp;", "&")
            ?.trim()?.takeIf { it.isNotBlank() }

    // ---- export ----

    fun gpx(favs: List<Fav>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Swiss Bikers and Pedestrians Map\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (f in favs) {
            sb.append(String.format(Locale.US, "<wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name>", f.lat, f.lon, esc(f.name)))
            if (f.detail.isNotBlank()) sb.append("<desc>").append(esc(f.detail)).append("</desc>")
            sb.append("<type>").append(f.kind).append("</type></wpt>\n")
        }
        return sb.append("</gpx>\n").toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- coordinates in text ----

    fun coordsName(lat: Double, lon: Double) = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    /** Coordinates typed by hand: "46.5197, 6.6323", "6.6323 46.5197", LV95 "2538000 1152000", LV03, or degrees-minutes. */
    fun parseCoords(text: String): LatLng? = coordsIn(text.trim())?.first

    private val decimal = Regex("(-?\\d{1,3}(?:[.,]\\d+)?)\\s*[°]?\\s*[,;/ ]\\s*(-?\\d{1,3}(?:[.,]\\d+)?)\\s*[°]?")
    private val swiss = Regex("((?:\\d{1,2}[' ]?)?\\d{3}[' ]?\\d{3}(?:\\.\\d+)?)\\s*[,;/ ]\\s*((?:\\d{1,2}[' ]?)?\\d{3}[' ]?\\d{3}(?:\\.\\d+)?)")
    private val dms = Regex("(\\d{1,3})\\s*°\\s*(\\d{1,2}(?:[.,]\\d+)?)\\s*['′]?\\s*(?:(\\d{1,2}(?:[.,]\\d+)?)\\s*[\"″]?)?\\s*([NSEW])")

    /** The coordinates found in a line and where they are, or null. */
    fun coordsIn(line: String): Pair<LatLng, IntRange>? {
        dms.findAll(line).toList().let { ms ->
            if (ms.size >= 2) {
                val a = dmsValue(ms[0]); val b = dmsValue(ms[1])
                val lat = if (ms[0].groupValues[4] in "NS") a else b
                val lon = if (ms[0].groupValues[4] in "NS") b else a
                return Pair(LatLng(lat, lon), ms[0].range.first..ms[1].range.last)
            }
        }
        swiss.find(line)?.let { m ->
            val a = m.groupValues[1].replace("'", "").replace(" ", "").toDouble()
            val b = m.groupValues[2].replace("'", "").replace(" ", "").toDouble()
            val (e, n) = when {
                a >= 2_400_000 && a < 2_900_000 && b >= 1_000_000 && b < 1_400_000 -> a to b
                b >= 2_400_000 && b < 2_900_000 && a >= 1_000_000 && a < 1_400_000 -> b to a
                a >= 400_000 && a < 900_000 && b < 400_000 -> (a + 2_000_000) to (b + 1_000_000)
                b >= 400_000 && b < 900_000 && a < 400_000 -> (b + 2_000_000) to (a + 1_000_000)
                else -> return@let
            }
            return Pair(lv95(e, n), m.range)
        }
        decimal.find(line)?.let { m ->
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val b = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return@let
            if (!m.groupValues[1].contains('.') && !m.groupValues[1].contains(',') && !m.groupValues[2].contains('.') && !m.groupValues[2].contains(',')) return@let
            val (lat, lon) = if (abs(a) <= 90 && abs(b) <= 180 && !(a in 5.0..11.0 && b in 45.0..48.0)) a to b else b to a
            if (abs(lat) > 90 || abs(lon) > 180) return@let
            return Pair(LatLng(lat, lon), m.range)
        }
        return null
    }

    private fun dmsValue(m: MatchResult): Double {
        val d = m.groupValues[1].toDouble()
        val min = m.groupValues[2].replace(',', '.').toDouble()
        val sec = m.groupValues[3].replace(',', '.').toDoubleOrNull() ?: 0.0
        val v = d + min / 60 + sec / 3600
        return if (m.groupValues[4] == "S" || m.groupValues[4] == "W") -v else v
    }

    /** swisstopo's approximate formulas (about a metre), LV95 east/north to WGS84. */
    fun lv95(e: Double, n: Double): LatLng {
        val y = (e - 2_600_000.0) / 1_000_000.0
        val x = (n - 1_200_000.0) / 1_000_000.0
        val lon = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y * y * y
        val lat = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x * x * x
        return LatLng(lat * 100 / 36, lon * 100 / 36)
    }
}
