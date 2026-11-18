package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TracksTest {
    private val gpx = """<?xml version="1.0"?>
<gpx version="1.1" creator="x" xmlns="http://www.topografix.com/GPX/1/1">
 <metadata><name>File name</name></metadata>
 <wpt lat="46.5" lon="6.6"><name>A waypoint</name></wpt>
 <trk><name>Tour du lac &amp; retour</name><type>hiking</type>
  <trkseg>
   <trkpt lat="46.5000" lon="6.6000"><ele>400.5</ele><time>2026-05-01T08:00:00Z</time></trkpt>
   <trkpt lon="6.6010" lat="46.5010"><ele>410</ele></trkpt>
   <trkpt lat="46.5010" lon="6.6010"/>
  </trkseg>
  <trkseg><trkpt lat="46.5020" lon="6.6020"></trkpt></trkseg>
 </trk>
 <trk><trkseg><trkpt lat='47.0' lon='7.0'/><trkpt lat='47.1' lon='7.1'/></trkseg></trk>
 <rte><name>A route</name><rtept lat="46.0" lon="7.0"><name>Start</name></rtept><rtept lat="46.1" lon="7.1"/></rte>
</gpx>"""

    @Test fun gpxTracksAndRoutes() {
        val found = Tracks.read(gpx.toByteArray(), "my_file.gpx")
        assertEquals(3, found.size)
        assertEquals("Tour du lac & retour", found[0].name)
        assertEquals(Tours.WALK, found[0].kind)
        assertEquals(3, found[0].points.size)                       // the repeated point is dropped
        assertEquals(400.5, found[0].points[0].ele, 1e-9)
        assertTrue(found[0].points[0].time > 0)
        assertEquals(46.5010, found[0].points[1].lat, 1e-9); assertEquals(6.6010, found[0].points[1].lon, 1e-9)
        assertTrue(found[0].points[2].ele.isNaN())
        assertEquals("File name", found[1].name)                    // a nameless track takes the file's own name
        assertEquals("A route", found[2].name)
        assertEquals(2, found[2].points.size)
    }

    @Test fun kmlLinesAndTracks() {
        val kml = """<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document><name>Doc</name>
<Placemark><name>A point</name><Point><coordinates>6.6,46.5,0</coordinates></Point></Placemark>
<Placemark><name><![CDATA[Sentier des crêtes]]></name><LineString><coordinates>
 6.60,46.50,500 6.61,46.51,520
 6.62,46.52
</coordinates></LineString></Placemark>
<Placemark><name>Recorded</name><gx:Track><when>2026-01-01T00:00:00Z</when><gx:coord>7.0 46.0 800</gx:coord><gx:coord>7.1 46.1 810</gx:coord></gx:Track></Placemark>
</Document></kml>"""
        val found = Tracks.read(kml.toByteArray(), "x.kml")
        assertEquals(2, found.size)
        assertEquals("Sentier des crêtes", found[0].name)
        assertEquals(3, found[0].points.size)
        assertEquals(46.51, found[0].points[1].lat, 1e-9); assertEquals(520.0, found[0].points[1].ele, 1e-9)
        assertEquals("Recorded", found[1].name)
        assertEquals(46.1, found[1].points[1].lat, 1e-9); assertEquals(810.0, found[1].points[1].ele, 1e-9)

        val zipped = ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray()); z.closeEntry() } }.toByteArray()
        assertEquals(2, Tracks.read(zipped, "x.kmz").size)
    }

    @Test fun geojsonLines() {
        val g = """{"type":"FeatureCollection","features":[
 {"type":"Feature","properties":{"name":"Boucle"},"geometry":{"type":"LineString","coordinates":[[6.6,46.5,400],[6.7,46.6,450]]}},
 {"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[6.6,46.5]}},
 {"type":"Feature","properties":{"title":"Two parts"},"geometry":{"type":"MultiLineString","coordinates":[[[7.0,46.0],[7.1,46.1]],[[7.1,46.1],[7.2,46.2]]]}}]}"""
        val found = Tracks.read(g.toByteArray(), "walks.geojson")
        assertEquals(2, found.size)
        assertEquals("Boucle", found[0].name); assertEquals(450.0, found[0].points[1].ele, 1e-9)
        assertEquals("Two parts", found[1].name); assertEquals(3, found[1].points.size)
    }

    @Test fun tcxCourse() {
        val t = """<?xml version="1.0"?><TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"><Courses><Course><Name>Col</Name><Track>
<Trackpoint><Time>2026-05-01T08:00:00Z</Time><Position><LatitudeDegrees>46.5</LatitudeDegrees><LongitudeDegrees>6.6</LongitudeDegrees></Position><AltitudeMeters>400</AltitudeMeters></Trackpoint>
<Trackpoint><Time>2026-05-01T08:00:05Z</Time></Trackpoint>
<Trackpoint><Position><LatitudeDegrees>46.6</LatitudeDegrees><LongitudeDegrees>6.7</LongitudeDegrees></Position></Trackpoint>
</Track></Course></Courses></TrainingCenterDatabase>"""
        val found = Tracks.read(t.toByteArray(), "col.tcx")
        assertEquals(1, found.size); assertEquals("Col", found[0].name); assertEquals(2, found[0].points.size)
        assertEquals(400.0, found[0].points[0].ele, 1e-9)
    }

    /** A FIT file written by hand: a sport message (cycling), then two records, one of them with a compressed-timestamp header. */
    @Test fun fitRecords() {
        val body = ByteArrayOutputStream()
        fun semi(deg: Double) = (deg * 2147483648.0 / 180.0).toInt()
        fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        // definition, local 1: sport (global 12), field 0 (sport), 1 byte
        body.write(byteArrayOf(0x41, 0, 0, 12, 0, 1, 0, 1, 0)); body.write(byteArrayOf(0x01, 2))
        // definition, local 0: record (global 20): lat, lon, altitude, timestamp, and one field this reader ignores
        body.write(byteArrayOf(0x40, 0, 0, 20, 0, 5, 0, 4, 0x85.toByte(), 1, 4, 0x85.toByte(), 2, 2, 0x84.toByte(), 253.toByte(), 4, 0x86.toByte(), 3, 1, 2))
        for ((i, p) in listOf(Pair(46.5, 6.6), Pair(46.6, 6.7)).withIndex()) {
            body.write(if (i == 0) 0x00 else 0x80)                 // the second one with a compressed-timestamp header (local 0)
            body.write(le(4).putInt(semi(p.first)).array()); body.write(le(4).putInt(semi(p.second)).array())
            body.write(le(2).putShort(((450.0 + 500.0) * 5).toInt().toShort()).array())
            body.write(le(4).putInt(1_000_000_000 + i).array()); body.write(120)
        }
        val data = body.toByteArray()
        val file = ByteArrayOutputStream()
        file.write(byteArrayOf(12, 0x10, 0, 0)); file.write(le(4).putInt(data.size).array()); file.write(".FIT".toByteArray())
        file.write(data); file.write(byteArrayOf(0, 0))
        val found = Tracks.read(file.toByteArray(), "Morning_ride.fit")
        assertEquals(1, found.size)
        assertEquals("Morning ride", found[0].name)
        assertEquals(Tours.BIKE, found[0].kind)
        assertEquals(2, found[0].points.size)
        assertEquals(46.6, found[0].points[1].lat, 1e-6); assertEquals(6.7, found[0].points[1].lon, 1e-6)
        assertEquals(450.0, found[0].points[0].ele, 1e-9)
        assertEquals((1_000_000_000L + 631065600L) * 1000L, found[0].points[0].time)
    }

    @Test fun nothingToRead() {
        assertTrue(Tracks.read("hello".toByteArray(), "x.txt").isEmpty())
        assertTrue(Tracks.read("<gpx><wpt lat=\"1\" lon=\"2\"/></gpx>".toByteArray(), "x.gpx").isEmpty())
        assertTrue(Tracks.read(ByteArray(0), "").isEmpty())
    }

    @Test fun denseLinesAreThinned() {
        val pts = (0 until 10_000).joinToString("") { "<trkpt lat=\"${46.0 + it * 1e-6}\" lon=\"7.0\"/>" }
        val found = Tracks.read("<gpx><trk><name>Dense</name><trkseg>$pts</trkseg></trk></gpx>".toByteArray(), "")
        assertEquals(1, found.size)
        assertTrue(found[0].points.size < 100)
        assertEquals(46.0, found[0].points.first().lat, 1e-9); assertEquals(46.009999, found[0].points.last().lat, 1e-9)
    }
}
