package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import java.io.File

class TransitTest {
    private fun pack(): Transit = Transit.of(javaClass.getResourceAsStream("/test.transit")!!.readBytes())

    @Test fun readsStops() {
        val t = pack()
        assertEquals(6, t.nStops)
        assertEquals("Alpha", t.stopName(0))
        assertEquals(8500001, t.stopId(0))
        assertEquals(listOf("Gamma", "Gamma, gare"), t.searchStops("gam").map { t.stopName(it) })
    }

    @Test fun plansTrainThenBus() {
        val t = pack()
        // 2026-06-02 is a Tuesday: day 152 of the feed
        val dep = t.epochOnDay(152, 7, 50)
        val js = t.plan(LatLng(46.5000, 6.6000), LatLng(46.5700, 6.7600), dep, 3)
        assertTrue(js.isNotEmpty())
        val j = js[0]
        val rides = j.legs.filter { it.ride }
        assertEquals(2, rides.size)
        assertEquals("S1", rides[0].line); assertEquals("Gamma", rides[0].to.name); assertEquals("1", rides[0].platDep)
        assertEquals(t.epochOnDay(152, 8, 0), rides[0].dep); assertEquals(t.epochOnDay(152, 8, 20), rides[0].arr)
        assertEquals("12", rides[1].line); assertEquals("Epsilon", rides[1].to.name)
        assertEquals(t.epochOnDay(152, 8, 30), rides[1].dep)
        assertEquals(1, j.changes)
        // a transfer walk between the station and the bus stop
        assertTrue(j.legs.any { !it.ride && it.from.name == "Gamma" && it.to.name == "Gamma, gare" })
        // the next departure (09:00 train, 09:30 bus) is offered too
        assertTrue(js.size >= 2)
        assertEquals(t.epochOnDay(152, 9, 0), js[1].firstRide!!.dep)
    }

    @Test fun weekendServiceDiffers() {
        val t = pack()
        // 2026-06-06 is a Saturday: only T2 (08:00) runs, no bus
        val dep = t.epochOnDay(156, 7, 50)
        val js = t.plan(LatLng(46.5000, 6.6000), LatLng(46.5500, 6.7000), dep, 2)
        assertTrue(js.isNotEmpty())
        assertEquals("S1", js[0].firstRide!!.line)
        assertEquals(1, js.size)
        val deps = t.departures(0, dep)
        assertEquals(1, deps.size)
        assertEquals("Gamma", deps[0].headsign)
    }

    @Test fun realPackIfPresent() {
        val f = File(System.getProperty("transit.pack") ?: return)
        if (!f.exists()) return
        val t0 = System.currentTimeMillis()
        val t = Transit.open(f)
        println("opened in ${System.currentTimeMillis() - t0} ms, ${t.nStops} stops, ${t.nTrips} trips, day0 ${t.feedStart}")
        val lausanne = LatLng(46.5197, 6.6323); val montreux = LatLng(46.4312, 6.9107); val wengen = LatLng(46.6050, 7.9217)
        for ((a, b, name) in listOf(Triple(lausanne, montreux, "Lausanne→Montreux"), Triple(lausanne, wengen, "Lausanne→Wengen"))) {
            val t1 = System.currentTimeMillis()
            val js = t.plan(a, b, t.epochOnDay(t.dayIndexOf(System.currentTimeMillis()), 8, 0), 4)
            println("$name: ${js.size} journeys in ${System.currentTimeMillis() - t1} ms")
            for (j in js) {
                println("  ${Journey.clock(j.dep)} → ${Journey.clock(j.arr)}  ${j.changes} changes")
                for (l in j.legs) println("    " + (if (l.ride) "${l.line} → ${l.headsign}" else "walk") + "  ${l.from.name} ${Journey.clock(l.dep)} [${l.platDep}] → ${l.to.name} ${Journey.clock(l.arr)} [${l.platArr}]  ${l.stops.size} stops")
            }
        }
        val s = t.searchStops("lausanne ouchy")
        println("search: " + s.map { t.stopName(it) })
        val d = t.departures(t.stopByDidok(8501120), t.epochOnDay(t.dayIndexOf(System.currentTimeMillis()), 8, 0), 8)
        println("departures Lausanne: " + d.map { "${Journey.clock(it.time)} ${it.line} ${it.headsign} [${it.platform}]" })
    }
}
