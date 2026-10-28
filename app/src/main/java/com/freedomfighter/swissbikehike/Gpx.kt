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

object Gpx {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun write(name: String, pts: List<TrackPoint>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Swiss Bike-Hike Map\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("<trk><name>").append(name).append("</name><trkseg>\n")
        for (p in pts) {
            sb.append("<trkpt lat=\"").append(String.format(Locale.US, "%.6f", p.lat))
                .append("\" lon=\"").append(String.format(Locale.US, "%.6f", p.lon)).append("\">")
            if (!p.ele.isNaN()) sb.append("<ele>").append(String.format(Locale.US, "%.1f", p.ele)).append("</ele>")
            sb.append("<time>").append(iso.format(Date(p.time))).append("</time></trkpt>\n")
        }
        sb.append("</trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    /** Track points and route points of a GPX file, in file order. */
    fun read(input: InputStream): List<LatLng> {
        val out = ArrayList<LatLng>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(input, null)
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && (parser.name == "trkpt" || parser.name == "rtept")) {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) out.add(LatLng(lat, lon))
            }
            ev = parser.next()
        }
        return out
    }
}
