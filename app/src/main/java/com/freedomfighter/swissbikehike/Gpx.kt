package com.freedomfighter.swissbikehike

import org.maplibre.android.geometry.LatLng
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class TrackPoint(val lat: Double, val lon: Double, val ele: Double, val time: Long)

/** A GPX track as read from a file: its name and its points in order. */
class Track(val name: String, val points: List<TrackPoint>) {
    val latLngs: List<LatLng> get() = points.map { LatLng(it.lat, it.lon) }
}

object Gpx {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun write(name: String, pts: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Swiss Bike-Hike Map\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<trk><name>").append(esc(name)).append("</name><trkseg>\n")
        for (p in pts) {
            sb.append("<trkpt lat=\"").append(String.format(Locale.US, "%.6f", p.lat))
                .append("\" lon=\"").append(String.format(Locale.US, "%.6f", p.lon)).append("\">")
            if (!p.ele.isNaN()) sb.append("<ele>").append(String.format(Locale.US, "%.1f", p.ele)).append("</ele>")
            if (p.time > 0) sb.append("<time>").append(iso.format(Date(p.time))).append("</time>")
            sb.append("</trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Track points and route points of a GPX file, in file order. */
    fun read(input: InputStream): List<LatLng> = readTrack(input).latLngs

    /** The track with its name, elevations and times (NaN / 0 when absent). */
    fun readTrack(input: InputStream): Track {
        val out = ArrayList<TrackPoint>()
        var name = ""
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        var ev = parser.eventType
        var lat = Double.NaN; var lon = Double.NaN; var ele = Double.NaN; var time = 0L
        var inPt = false; var tag = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "trkpt" || tag == "rtept") {
                        inPt = true; ele = Double.NaN; time = 0L
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text?.trim() ?: ""
                    if (inPt && tag == "ele") ele = t.toDoubleOrNull() ?: Double.NaN
                    else if (inPt && tag == "time") time = runCatching { iso.parse(t.take(19) + "Z")?.time ?: 0L }.getOrDefault(0L)
                    else if (!inPt && tag == "name" && name.isEmpty() && t.isNotEmpty()) name = t
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "trkpt" || parser.name == "rtept") {
                        if (!lat.isNaN() && !lon.isNaN()) out.add(TrackPoint(lat, lon, ele, time))
                        inPt = false
                    }
                    tag = ""
                }
            }
            ev = parser.next()
        }
        return Track(name, out)
    }
}
