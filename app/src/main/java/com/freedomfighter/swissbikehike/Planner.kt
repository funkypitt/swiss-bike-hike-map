package com.freedomfighter.swissbikehike

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

/**
 * Public-transport journeys from a point to a point: the online timetable when the phone is
 * connected (real-time), the offline pack otherwise, and the walks to, between and from the stops
 * routed on the phone when the routing data is there.
 */
object Planner {
    class NoTimetable : Exception("no timetable")
    /** The live service failed and the offline pack answered instead: why, for a short notice. Read once. */
    @Volatile var notice: String? = null
    fun takeNotice(): String? = notice.also { notice = null }

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun plan(ctx: Context, from: LatLng, to: LatLng, depart: Long, count: Int = 4): List<Journey> {
        var journeys: List<Journey> = emptyList()
        var liveError: String? = null
        if (online(ctx)) {
            val r = runCatching { planOnline(from, to, depart, count) }
            journeys = r.getOrDefault(emptyList())
            liveError = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
        if (journeys.isEmpty()) {
            val pack = Timetable.open(ctx)
            if (pack == null) {
                if (liveError != null) throw Exception(ctx.getString(R.string.live_failed, liveError))
                if (!online(ctx)) throw NoTimetable()
            } else {
                journeys = withContext(Dispatchers.Default) { pack.plan(from, to, depart, count) }
                if (liveError != null) notice = ctx.getString(R.string.live_failed_offline_used, liveError)
            }
        }
        return routeWalks(ctx, journeys, depart)
    }

    /**
     * The online API goes from stop to stop: the nearest stops around each end are tried in pairs
     * (the nearest of all, and the nearest station proper, whose name carries no comma in the
     * Swiss convention), the walks are added, the best arrivals kept.
     */
    private suspend fun planOnline(from: LatLng, to: LatLng, depart: Long, count: Int): List<Journey> {
        val o = TransitOnline.nearest(from); val d = TransitOnline.nearest(to)
        if (o.isEmpty() || d.isEmpty()) return emptyList()
        var pairError: String? = null
        fun picks(l: List<TransitOnline.Station>): List<TransitOnline.Station> {
            val near = l.first()
            val station = l.firstOrNull { !it.name.contains(',') && it.distanceM <= 1500 }
            return listOfNotNull(near, station?.takeIf { it.id != near.id })
        }
        val out = ArrayList<Journey>()
        for (a in picks(o)) for (b in picks(d)) {
            if (a.id == b.id) continue
            val wIn = Transit.walkSeconds(a.distanceM) * 1000L
            val r = runCatching { TransitOnline.connections(a, b, depart + wIn, count) }
            r.exceptionOrNull()?.let { if (pairError == null) pairError = it.message ?: it.javaClass.simpleName }
            val found = r.getOrDefault(emptyList())
            for (j in found) {
                val first = j.legs.first(); val last = j.legs.last()
                val legs = ArrayList<Leg>()
                legs.add(Leg(false, TStop("", from), first.from, first.dep - wIn, first.dep, Journey.WALK, "", "", "", "", emptyList(), listOf(from, first.from.at)))
                legs.addAll(j.legs)
                val wOut = Transit.walkSeconds(b.distanceM) * 1000L
                legs.add(Leg(false, last.to, TStop("", to), last.arr, last.arr + wOut, Journey.WALK, "", "", "", "", emptyList(), listOf(last.to.at, to)))
                out.add(Journey(legs, false))
            }
        }
        // the same vehicles from two nearby stops: keep the one arriving first, then the latest start
        val best = LinkedHashMap<String, Journey>()
        for (j in out.sortedWith(compareBy({ it.arr }, { -it.dep }))) {
            val key = j.legs.filter { it.ride }.joinToString("|") { it.line + "@" + it.dep }
            if (!best.containsKey(key)) best[key] = j
        }
        if (best.isEmpty() && pairError != null) throw Exception(pairError)
        return dominated(best.values).take(count)
    }

    /** Same arrival with more changes, or same arrival and changes with an earlier start: not worth showing. */
    fun dominated(js: Collection<Journey>): List<Journey> {
        val out = ArrayList<Journey>()
        for (j in js.sortedWith(compareBy({ it.arr }, { it.changes }, { -it.dep }))) {
            if (out.none { it.arr == j.arr && it.changes <= j.changes }) out.add(j)
        }
        return out
    }

    /** Walk legs get a real path (and length) from the hiking router when its data is on the phone. */
    private suspend fun routeWalks(ctx: Context, journeys: List<Journey>, depart: Long): List<Journey> {
        if (!Segments.isReady(ctx)) return journeys
        val keep = ArrayList<Journey>()
        for (j in journeys) {
            var late = false
            for ((i, leg) in j.legs.withIndex()) {
                if (leg.ride) continue
                val d = leg.from.at.distanceTo(leg.to.at)
                if (d < 40 || d > 3000) continue
                val r = runCatching { Router.route(ctx, Mode.WALK, leg.from.at, leg.to.at) }.getOrNull() ?: continue
                leg.path = r.points
                // the first walk starts when it must to catch the first ride; the last ends when it ends
                val sec = maxOf(r.seconds, Transit.walkSeconds(r.distanceM.toDouble()))
                if (i == j.legs.size - 1) leg.setTimes(leg.dep, leg.dep + sec * 1000L)
                else if (i == 0) {
                    // the real walk is longer than the estimate that chose the ride: it would start in the past
                    if (leg.arr - sec * 1000L < depart - 60_000L) late = true
                    leg.setTimes(leg.arr - sec * 1000L, leg.arr)
                }
            }
            if (!late) keep.add(j)
        }
        return keep
    }

    class Departure(val time: Long, val kind: Int, val line: String, val headsign: String, val platform: String, val delayMin: Int, val realtime: Boolean)

    private val boardCache = HashMap<String, Pair<Long, List<Departure>>>()

    /** The next departures at a stop, online when possible; one answer per stop is kept 30 s (the API limits the rate). */
    suspend fun departures(ctx: Context, stop: TStop): List<Departure> {
        val key = stop.id.ifBlank { stop.name }
        synchronized(boardCache) { boardCache[key]?.let { (t, l) -> if (System.currentTimeMillis() - t < 30_000) return l } }
        val fresh = departuresFresh(ctx, stop)
        synchronized(boardCache) { boardCache[key] = Pair(System.currentTimeMillis(), fresh) }
        return fresh
    }

    private suspend fun departuresFresh(ctx: Context, stop: TStop): List<Departure> {
        var liveError: String? = null
        if (online(ctx)) {
            val r = runCatching { TransitOnline.departures(TransitOnline.Station(stop.id, stop.name, stop.at, 0.0), 40) }
            val found = r.getOrDefault(emptyList())
            liveError = r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            if (found.isNotEmpty()) return found.map { Departure(it.time, it.kind, it.line, it.headsign, it.platform, it.delayMin, true) }
        }
        val pack = Timetable.open(ctx) ?: throw (if (liveError != null) Exception(ctx.getString(R.string.live_failed, liveError)) else NoTimetable())
        if (liveError != null) notice = ctx.getString(R.string.live_failed_offline_used, liveError)
        val idx = stop.id.toIntOrNull()?.let { pack.stopByDidok(it) }?.takeIf { it >= 0 } ?: pack.nearest(stop.at, 150.0, 1).firstOrNull()?.first ?: return emptyList()
        return withContext(Dispatchers.Default) { pack.departures(idx, System.currentTimeMillis(), 40) }.map { Departure(it.time, it.kind, it.line, it.headsign, it.platform, 0, false) }
    }

    /** Stops by name, online or from the pack. */
    suspend fun searchStops(ctx: Context, q: String): List<TStop> {
        if (online(ctx)) {
            val found = runCatching { TransitOnline.search(q) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found.map { TStop(it.name, it.at, it.id) }
        }
        val pack = Timetable.open(ctx) ?: return emptyList()
        return withContext(Dispatchers.Default) { pack.searchStops(q).map { pack.stop(it) } }
    }
}
