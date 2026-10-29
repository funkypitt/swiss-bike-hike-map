package com.freedomfighter.swissbikehike

import org.maplibre.android.geometry.LatLng

/**
 * The public-transport guidance as a pure state machine: a journey, the leg we are on, the stops
 * passed; given a position (or none) and the time, it says what to do now. No clock, no GPS, no
 * Android inside, so it is unit-tested; NavService turns its steps into words and vibrations.
 *
 * Thresholds: a walk ends within 35 m of its stop (or at its time without a fix); a ride missed =
 * still within 120 m of the boarding stop three minutes after departure; stops passed are counted
 * within 150 m; get off is said one stop before, within 400 m, or one minute before arrival; a leg
 * is done within 60 m of its end from two minutes before arrival, or by the clock without a fix.
 */
class TransitGuide(var journey: Journey) {
    var legIdx = 0; private set
    var passedStops = 0; private set
    /** The step just given follows a change of vehicle. */
    var changeNext = false; private set

    sealed class Step(val leg: Int) {
        class Arrived(leg: Int) : Step(leg)
        class Walk(leg: Int, val distM: Double?, val last: Boolean, val change: Boolean) : Step(leg)
        class Board(leg: Int, val change: Boolean, val soon: Boolean) : Step(leg)
        class Ride(leg: Int, val stopsLeft: Int) : Step(leg)
        class GetOff(leg: Int) : Step(leg)
        /** The ride of this leg left without us: the caller plans again from here. */
        class Missed(leg: Int) : Step(leg)
    }

    companion object {
        const val WALK_DONE_M = 35.0; const val MISSED_M = 120.0; const val MISSED_MS = 180_000L
        const val BOARD_M = 250.0; const val PASSED_M = 150.0; const val GET_OFF_M = 400.0
        const val DONE_M = 60.0; const val DONE_LATE_M = 200.0
    }

    /** Replaces the journey (a new plan) and starts from its first leg. */
    fun restart(j: Journey) { journey = j; legIdx = 0; passedStops = 0; changeNext = false }

    private fun advance(j: Journey) {
        val wasRide = j.legs[legIdx].ride
        legIdx++; passedStops = 0
        val next = j.legs.getOrNull(legIdx)
        changeNext = wasRide && next != null && (next.ride || j.legs.getOrNull(legIdx + 1)?.ride == true)
    }

    fun step(here: LatLng?, now: Long): Step {
        val j = journey
        var guard = 0
        while (guard++ < j.legs.size + 2) {
            if (legIdx >= j.legs.size) return Step.Arrived(legIdx)
            val leg = j.legs[legIdx]
            val last = legIdx == j.legs.size - 1
            val next = j.legs.getOrNull(legIdx + 1)
            if (!leg.ride) {
                val d = here?.distanceTo(leg.to.at)
                val done = (d != null && d < WALK_DONE_M) || (d == null && now >= leg.arr)
                if (done) { if (last) legIdx++ else advance(j); continue }
                if (next != null && next.ride && now > next.dep + MISSED_MS && d != null && d > WALK_DONE_M) return Step.Missed(legIdx + 1)
                return Step.Walk(legIdx, d, last, changeNext)
            }
            val dFrom = here?.distanceTo(leg.from.at)
            val dTo = here?.distanceTo(leg.to.at)
            if (passedStops == 0 && now > leg.dep + MISSED_MS && dFrom != null && dFrom < MISSED_M) return Step.Missed(legIdx)
            val boarding = passedStops == 0 && now < leg.dep + MISSED_MS && (dFrom == null || dFrom < BOARD_M)
            if (boarding) return Step.Board(legIdx, changeNext, now >= leg.dep - 120_000L)
            if (here != null) for (k in passedStops until leg.stops.size) if (here.distanceTo(leg.stops[k].first.at) < PASSED_M) passedStops = k + 1
            val byClock = leg.stops.count { it.second <= now }
            val passed = if (here == null) maxOf(passedStops, byClock) else passedStops
            val left = leg.stops.size - passed
            val legDone = (dTo != null && dTo < DONE_M && now >= leg.arr - 120_000L) ||
                (dTo != null && dTo < DONE_LATE_M && now >= leg.arr + 60_000L) ||
                (here == null && now >= leg.arr + 30_000L)
            if (legDone) { advance(j); continue }
            if (left <= 1 || (dTo != null && dTo < GET_OFF_M) || now >= leg.arr - 60_000L) return Step.GetOff(legIdx)
            return Step.Ride(legIdx, left)
        }
        return Step.Arrived(legIdx)
    }
}
