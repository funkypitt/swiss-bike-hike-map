package com.freedomfighter.swissbikehike

import org.maplibre.android.geometry.LatLng
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A public-transport stop as the journey sees it: name, position, and the official number when known. */
class TStop(var name: String, val at: LatLng, val id: String = "")

/**
 * One leg of a public-transport journey: a walk, or a ride on one vehicle from a stop to another.
 * Times are epoch milliseconds. `stops` lists the stops served after boarding, the last being `to`,
 * each with its arrival time. `path` is what gets drawn: the walk as routed, the ride through its stops.
 */
class Leg(
    val ride: Boolean,
    val from: TStop,
    val to: TStop,
    dep: Long,
    arr: Long,
    val kind: Int,
    val line: String,
    val headsign: String,
    val platDep: String,
    val platArr: String,
    val stops: List<Pair<TStop, Long>>,
    var path: List<LatLng>,
) {
    var dep: Long = dep; private set
    var arr: Long = arr; private set
    fun setTimes(dep: Long, arr: Long) { this.dep = dep; this.arr = arr }
    val seconds: Int get() = ((arr - dep) / 1000).toInt()
}

/** A whole journey: walks and rides in order. */
class Journey(val legs: List<Leg>, val offline: Boolean = false) {
    val dep: Long get() = legs.first().dep
    val arr: Long get() = legs.last().arr
    val seconds: Int get() = ((arr - dep) / 1000).toInt()
    val rides: Int get() = legs.count { it.ride }
    val changes: Int get() = maxOf(0, rides - 1)
    val firstRide: Leg? get() = legs.firstOrNull { it.ride }
    val destination: TStop get() = legs.last().to
    /** Every point of every leg, for fitting the map. */
    val points: List<LatLng> get() = legs.flatMap { it.path }

    companion object {
        const val WALK = 0; const val TRAIN = 1; const val TRAM = 2; const val BUS = 3; const val BOAT = 4; const val LIFT = 5

        /** Kind from a GTFS route type (the extended set the Swiss feed uses). */
        fun kindOf(routeType: Int): Int = when (routeType) {
            in 100..199, 2, 401, 402, 1400 -> TRAIN
            in 900..999, 0, 1, 400, in 403..405 -> TRAM
            in 1000..1099, 4, 1200 -> BOAT
            in 1300..1399, 6, 7 -> LIFT
            else -> BUS
        }

        /** Kind from a transport.opendata.ch category code (IR, S, B, T, BAT, FUN, PB…). */
        fun kindOfCategory(cat: String): Int = when (cat.uppercase(Locale.ROOT)) {
            "B", "BUS", "EV", "BN", "EXB", "RUB", "BP", "TX", "NFB", "KB", "NB", "CAR" -> BUS
            "T", "TRAM", "M", "NFT", "MET" -> TRAM
            "BAT", "FAE", "SCH" -> BOAT
            "FUN", "PB", "GB", "SL", "LB", "ASC", "CC" -> LIFT
            else -> TRAIN
        }

        /** Line colour on the map, by kind: the signpost-like palette of the transit mode. */
        fun colour(kind: Int): String = when (kind) {
            TRAIN -> MapStyle.PT_TRAIN; TRAM -> MapStyle.PT_TRAM; BOAT -> MapStyle.PT_BOAT; LIFT -> MapStyle.PT_LIFT
            BUS -> MapStyle.PT_BUS; else -> "#000000"
        }

        private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
        fun clock(t: Long): String = hhmm.format(Date(t))
    }
}
