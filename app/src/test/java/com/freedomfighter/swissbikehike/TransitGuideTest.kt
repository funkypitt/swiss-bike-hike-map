package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class TransitGuideTest {
    private val t0 = 1_800_000_000_000L
    private val home = LatLng(46.5000, 6.6000)
    private val stopA = TStop("Alpha", LatLng(46.5030, 6.6000), "1")   // ~330 m north of home
    private val stopX = TStop("Xi", LatLng(46.5200, 6.6500), "2")
    private val stopB = TStop("Beta", LatLng(46.5500, 6.7000), "3")
    private val office = LatLng(46.5520, 6.7000)                       // ~220 m from Beta

    private fun journey(): Journey {
        val m = 60_000L
        val walk1 = Leg(false, TStop("", home), stopA, t0, t0 + 5 * m, Journey.WALK, "", "", "", "", emptyList(), listOf(home, stopA.at))
        val ride = Leg(true, stopA, stopB, t0 + 8 * m, t0 + 30 * m, Journey.TRAIN, "S1", "Beta", "1", "3",
            listOf(Pair(stopX, t0 + 18 * m), Pair(stopB, t0 + 30 * m)), listOf(stopA.at, stopX.at, stopB.at))
        val walk2 = Leg(false, stopB, TStop("Office", office), t0 + 30 * m, t0 + 34 * m, Journey.WALK, "", "", "", "", emptyList(), listOf(stopB.at, office))
        return Journey(listOf(walk1, ride, walk2))
    }
    private fun min(n: Int) = t0 + n * 60_000L

    @Test fun walkThenBoardThenRideThenGetOffThenArrive() {
        val g = TransitGuide(journey())
        val s1 = g.step(home, min(0))
        assertTrue(s1 is TransitGuide.Step.Walk); assertEquals(0, s1.leg)
        val s2 = g.step(stopA.at, min(4))
        assertTrue("at the stop before departure = board", s2 is TransitGuide.Step.Board); assertEquals(1, s2.leg)
        assertTrue(!(s2 as TransitGuide.Step.Board).soon)
        assertTrue((g.step(stopA.at, min(7)) as TransitGuide.Step.Board).soon)
        val s3 = g.step(LatLng(46.5100, 6.6250), min(12))
        assertTrue("on the way, two stops ahead", s3 is TransitGuide.Step.Ride); assertEquals(2, (s3 as TransitGuide.Step.Ride).stopsLeft)
        val s4 = g.step(stopX.at, min(18))
        assertTrue("one stop left = get off", s4 is TransitGuide.Step.GetOff)
        val s5 = g.step(stopB.at, min(30))
        assertTrue("at Beta on time = last walk", s5 is TransitGuide.Step.Walk); assertEquals(2, s5.leg); assertTrue((s5 as TransitGuide.Step.Walk).last)
        val s6 = g.step(office, min(34))
        assertTrue(s6 is TransitGuide.Step.Arrived)
    }

    @Test fun missedRideIsDetectedAtTheStop() {
        val g = TransitGuide(journey())
        g.step(stopA.at, min(4))
        val s = g.step(stopA.at, min(12))   // four minutes after departure, still at the stop
        assertTrue(s is TransitGuide.Step.Missed); assertEquals(1, s.leg)
    }

    @Test fun missedRideIsDetectedWhileStillWalking() {
        val g = TransitGuide(journey())
        val s = g.step(home, min(12))        // never reached the stop, the train left
        assertTrue(s is TransitGuide.Step.Missed); assertEquals(1, s.leg)
    }

    @Test fun withoutAFixTheClockDrivesIt() {
        val g = TransitGuide(journey())
        assertTrue(g.step(null, min(2)) is TransitGuide.Step.Walk)
        assertTrue("walk over by its time", g.step(null, min(6)) is TransitGuide.Step.Board)
        val r = g.step(null, min(12))
        assertTrue("left on time without a fix = riding, not missed", r is TransitGuide.Step.Ride)
        assertTrue(g.step(null, min(19)) is TransitGuide.Step.GetOff)
        assertTrue(g.step(null, min(31)) is TransitGuide.Step.Walk)
        assertTrue(g.step(null, min(35)) is TransitGuide.Step.Arrived)
    }

    @Test fun lateVehicleStillBoardsWithinThreeMinutes() {
        val g = TransitGuide(journey())
        assertTrue(g.step(stopA.at, min(10)) is TransitGuide.Step.Board)
    }

    @Test fun restartTakesTheNewJourney() {
        val g = TransitGuide(journey())
        g.step(stopX.at, min(18))
        g.restart(journey())
        assertEquals(0, g.legIdx)
        assertTrue(g.step(home, min(0)) is TransitGuide.Step.Walk)
    }
}
