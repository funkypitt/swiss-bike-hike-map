package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FavouritesTest {
    @Test fun decimalCoordinates() {
        val p = Favourites.parseCoords("46.5197, 6.6323")!!
        assertEquals(46.5197, p.latitude, 1e-9); assertEquals(6.6323, p.longitude, 1e-9)
        val swapped = Favourites.parseCoords("6.6323 46.5197")!!
        assertEquals(46.5197, swapped.latitude, 1e-9)
        assertNull(Favourites.parseCoords("Rue du Lac 12"))
        assertNull(Favourites.parseCoords("1003 Lausanne"))
    }

    @Test fun swissCoordinates() {
        val p = Favourites.parseCoords("2'538'000 1'152'000")!!   // Lausanne; the formula checks out on Bern's Zytglogge too
        assertEquals(46.5164, p.latitude, 1e-3); assertEquals(6.6307, p.longitude, 1e-3)
        val bern = Favourites.lv95(2_600_670.0, 1_199_655.0)
        assertEquals(46.9480, bern.latitude, 1e-3); assertEquals(7.4474, bern.longitude, 1e-3)
        val lv03 = Favourites.parseCoords("538000 152000")!!
        assertEquals(p.latitude, lv03.latitude, 1e-9)
    }

    @Test fun degreesMinutes() {
        val p = Favourites.parseCoords("46°31'11\"N 6°37'56\"E")!!
        assertEquals(46.5197, p.latitude, 1e-3); assertEquals(6.6322, p.longitude, 1e-3)
    }

    @Test fun gpxWaypoints() {
        val l = Favourites.parse("""<?xml version="1.0"?><gpx><wpt lat="46.1" lon="7.2"><name>Cabane &amp; co</name><desc>hut</desc></wpt><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""")
        assertEquals(1, l.size); assertEquals("Cabane & co", l[0].name); assertEquals("hut", l[0].detail); assertEquals(7.2, l[0].lon, 1e-9)
    }

    @Test fun kmlPlacemarks() {
        val l = Favourites.parse("""<kml><Document><Placemark><name>Home</name><Point><coordinates>6.63,46.52,0</coordinates></Point></Placemark><Placemark><name>Line</name><LineString><coordinates>1,2 3,4</coordinates></LineString></Placemark></Document></kml>""")
        assertEquals(1, l.size); assertEquals("Home", l[0].name); assertEquals(46.52, l[0].lat, 1e-9)
    }

    @Test fun geojsonPoints() {
        val l = Favourites.parse("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[6.6,46.5]},"properties":{"name":"P"}}]}""")
        assertEquals(1, l.size); assertEquals("P", l[0].name); assertEquals(6.6, l[0].lon, 1e-9)
    }

    @Test fun csvAndPlainLines() {
        val l = Favourites.parse("name,lat,lon\nBoulangerie,46.52,6.63\n\"Cabane\";2538000;1152000\n46.1 7.1\nno coordinates here\n")
        assertEquals(3, l.size)
        assertEquals("Boulangerie", l[0].name); assertEquals(Favourites.PLACE, l[0].kind)
        assertEquals("Cabane", l[1].name); assertEquals(46.5164, l[1].lat, 1e-3)
        assertEquals(Favourites.COORDS, l[2].kind); assertNotNull(l[2].name)
    }

    @Test fun jsonRoundTrip() {
        val l = listOf(Fav("A", 1.0, 2.0, Favourites.PERSON, "+41 21"), Fav("B", 3.0, 4.0))
        val back = Favourites.fromJson(Favourites.toJson(l))
        assertEquals(2, back.size); assertEquals("+41 21", back[0].detail); assertEquals(Favourites.PLACE, back[1].kind)
        val gpx = Favourites.gpx(l)
        assertEquals(2, Favourites.parse(gpx).size); assertEquals(Favourites.PERSON, Favourites.parse(gpx)[0].kind)
    }
}
