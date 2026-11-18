package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** Reads the file shipped with the app (built by tools/make-tours.py). */
class SwissToursTest {
    private val file = File("src/main/assets/tours.pack")
    private val pack = file.inputStream().use { SwissTours.readIndex(it) }

    private fun metres(a: TrackPoint, b: TrackPoint) = hypot((a.lat - b.lat) * 111_320.0, (a.lon - b.lon) * 111_320.0 * cos(Math.toRadians(a.lat)))

    @Test fun indexHoldsBothLands() {
        assertTrue(pack.tours.count { it.land == SwissTours.WALK } > 800)
        assertTrue(pack.tours.count { it.land == SwissTours.MTB } > 300)
        assertTrue(pack.tours.count { it.barrierFree } > 50)
        assertTrue(pack.built.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        // walking stages carry their signposted time
        assertTrue(pack.tours.filter { it.land == SwissTours.WALK }.all { it.minutes > 0 })
    }

    @Test fun aLocalRouteRunsFromItsStartToItsEnd() {
        val dole = pack.tours.single { it.land == SwissTours.WALK && it.nr == "116" }
        assertEquals("Chemin de La Dôle", dole.name); assertEquals("Saint-Cergue", dole.from); assertEquals("La Givrine", dole.to)
        val line = file.inputStream().use { SwissTours.line(it, pack, dole) }
        // Saint-Cergue is east of La Givrine, and 150 m lower
        assertTrue(line.first().lon > line.last().lon)
        assertEquals(46.446, line.first().lat, 0.01); assertEquals(6.157, line.first().lon, 0.01)
        assertTrue(line.first().ele in 1000.0..1100.0); assertTrue(line.last().ele in 1150.0..1260.0)
        assertTrue(line.maxOf { it.ele } in 1600.0..1700.0)          // over La Dôle, 1677 m
        val length = line.zipWithNext { a, b -> metres(a, b) }.sum()
        assertTrue(abs(length - dole.metres) < 0.03 * dole.metres)
        assertEquals(dole.lat, line.first().lat, 1e-4); assertEquals(dole.lat2, line.last().lat, 1e-4)
    }

    @Test fun stagesFollowOneAnother() {
        val jura = pack.tours.filter { it.land == SwissTours.WALK && it.nr == "5" }.sortedBy { it.stage }
        assertTrue(jura.size >= 15); assertEquals("Dielsdorf", jura.first().from); assertEquals("Nyon", jura.last().to)
        for ((a, b) in jura.zipWithNext()) {
            assertEquals(a.to, b.from)
            assertTrue("${a.stage} → ${b.stage}", metres(TrackPoint(a.lat2, a.lon2, 0.0, 0), TrackPoint(b.lat, b.lon, 0.0, 0)) < 300)
        }
    }

    @Test fun everyLineDecodes() {
        val all = file.readBytes()
        for (t in pack.tours) {
            val line = SwissTours.decode(all.copyOfRange((pack.linesAt + t.offset).toInt(), (pack.linesAt + t.offset).toInt() + t.bytes))
            assertTrue(line.size >= 2)
            assertTrue(line.all { it.lat in 45.6..47.9 && it.lon in 5.8..10.7 && it.ele in 150.0..3600.0 })
        }
    }

    @Test fun pages() {
        val dole = pack.tours.single { it.land == SwissTours.WALK && it.nr == "116" }
        assertEquals("https://schweizmobil.ch/fr/suisse-a-pied/itineraire-116", SwissTours.page(dole, "fr"))
        assertEquals("https://schweizmobil.ch/en/hiking-in-switzerland/route-116", SwissTours.page(dole, "ru"))
        val bike = pack.tours.first { it.land == SwissTours.MTB && it.nr == "116" }
        assertEquals("https://schweizmobil.ch/de/mountainbikeland/route-116", SwissTours.page(bike, "de"))
    }
}
