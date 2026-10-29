package com.freedomfighter.swissbikehike

import org.maplibre.android.geometry.LatLng
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The offline timetable: the binary written by tools/make-transit.py, memory-mapped, and a
 * journey planner over it (RAPTOR: rounds of rides, footpaths between rounds, every round one
 * more change). Stops are stations; a stop's platform comes with the trip. Times in the file are
 * minutes of the service day; a trip may run past midnight (minutes ≥ 1440), so the previous
 * day's services are looked at too. Everything is read on demand, nothing is unpacked.
 */
class Transit private constructor(private val b: ByteBuffer) {
    companion object {
        private const val MAGIC = 0x54484253 // "SBHT" little-endian
        private const val ROUNDS = 6
        private const val INF = Int.MAX_VALUE / 2
        const val WALK_MPS = 1.1
        const val WALK_FACTOR = 1.3
        const val REACH_M = 1000.0
        val ZONE: ZoneId = ZoneId.of("Europe/Zurich")

        fun open(f: File): Transit {
            val ch = RandomAccessFile(f, "r").channel
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN)
            ch.close()
            return Transit(buf)
        }
        fun of(bytes: ByteArray): Transit = Transit(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

        fun walkSeconds(m: Double): Int = maxOf(30, (m * WALK_FACTOR / WALK_MPS).toInt())

        fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    }

    val feedStart: LocalDate
    val nDays: Int
    private val secStrings: Int; private val secStops: Int; private val secRoutes: Int; private val secPatterns: Int
    private val secPatternStops: Int; private val secProfiles: Int; private val secProfileData: Int; private val secBitmaps: Int
    private val secTrips: Int; private val secStopPatterns: Int; private val secFootpaths: Int
    val nStops: Int; val nRoutes: Int; val nPatterns: Int; val nProfiles: Int; val nTrips: Int
    private val nStrings: Int; private val bitmapLen: Int; private val nBitmaps: Int
    private val stopsAt: Int; private val routesAt: Int; private val patternsAt: Int; private val patternStopsAt: Int
    private val profileOffsAt: Int; private val profileDataAt: Int; private val bitmapsAt: Int; private val tripsAt: Int
    private val stopPatternsDataAt: Int; private val footpathsDataAt: Int
    private val folded: Array<String?>

    init {
        require(b.getInt(0) == MAGIC) { "not a transit pack" }
        require(b.getInt(4) == 1) { "unknown pack version" }
        val d = b.getInt(8)
        feedStart = LocalDate.of(d / 10000, d / 100 % 100, d % 100)
        nDays = b.getShort(12).toInt() and 0xFFFF
        var p = 14
        secStrings = b.getInt(p); secStops = b.getInt(p + 4); secRoutes = b.getInt(p + 8); secPatterns = b.getInt(p + 12)
        secPatternStops = b.getInt(p + 16); secProfiles = b.getInt(p + 20); secProfileData = b.getInt(p + 24); secBitmaps = b.getInt(p + 28)
        secTrips = b.getInt(p + 32); secStopPatterns = b.getInt(p + 36); secFootpaths = b.getInt(p + 40)
        nStrings = b.getInt(secStrings)
        nStops = b.getInt(secStops); stopsAt = secStops + 4
        nRoutes = b.getInt(secRoutes); routesAt = secRoutes + 4
        nPatterns = b.getInt(secPatterns); patternsAt = secPatterns + 4
        patternStopsAt = secPatternStops + 4
        nProfiles = b.getInt(secProfiles); profileOffsAt = secProfiles + 4
        profileDataAt = secProfileData + 4
        nBitmaps = b.getInt(secBitmaps); bitmapLen = b.getShort(secBitmaps + 4).toInt() and 0xFFFF; bitmapsAt = secBitmaps + 6
        nTrips = b.getInt(secTrips); tripsAt = secTrips + 4
        stopPatternsDataAt = secStopPatterns + 4 * nStops + 4
        footpathsDataAt = secFootpaths + 4 * nStops + 4
        folded = arrayOfNulls(nStops)
        p = 0
    }

    // ---- raw access ----

    fun string(i: Int): String {
        if (i <= 0 || i >= nStrings) return ""
        val offs = secStrings + 4
        val s = b.getInt(offs + 4 * i); val e = b.getInt(offs + 4 * (i + 1))
        val bytes = ByteArray(e - s)
        for (k in bytes.indices) bytes[k] = b.get(secStrings + 4 + 4 * (nStrings + 1) + s + k)
        return String(bytes, Charsets.UTF_8)
    }
    fun stopLat(i: Int): Double = b.getInt(stopsAt + 16 * i) / 1e6
    fun stopLon(i: Int): Double = b.getInt(stopsAt + 16 * i + 4) / 1e6
    fun stopId(i: Int): Int = b.getInt(stopsAt + 16 * i + 8)
    fun stopName(i: Int): String = string(b.getInt(stopsAt + 16 * i + 12))
    fun stop(i: Int): TStop = TStop(stopName(i), LatLng(stopLat(i), stopLon(i)), stopId(i).let { if (it > 0) it.toString() else "" })
    fun stopByDidok(id: Int): Int { for (i in 0 until nStops) if (stopId(i) == id) return i; return -1 }

    private fun routeType(r: Int): Int = b.getShort(routesAt + 14 * r).toInt() and 0xFFFF
    private fun routeShort(r: Int): String = string(b.getInt(routesAt + 14 * r + 2))
    private fun routeDesc(r: Int): String = string(b.getInt(routesAt + 14 * r + 10))

    private fun patRoute(p: Int): Int = b.getInt(patternsAt + 18 * p)
    private fun patN(p: Int): Int = b.getShort(patternsAt + 18 * p + 4).toInt() and 0xFFFF
    private fun patStopsOff(p: Int): Int = b.getInt(patternsAt + 18 * p + 6)
    private fun patTripFirst(p: Int): Int = b.getInt(patternsAt + 18 * p + 10)
    private fun patTripCount(p: Int): Int = b.getInt(patternsAt + 18 * p + 14)
    private fun patStop(p: Int, i: Int): Int = b.getInt(patternStopsAt + 4 * (patStopsOff(p) + i))

    private fun profAt(f: Int): Int = profileDataAt + b.getInt(profileOffsAt + 4 * f)
    private fun profN(at: Int): Int = b.getShort(at + 4).toInt() and 0xFFFF
    private fun profArr(at: Int, i: Int): Int = b.getShort(at + 7 + 2 * i).toInt() and 0xFFFF
    private fun profDep(at: Int, i: Int): Int = b.getShort(at + 7 + 2 * profN(at) + 2 * i).toInt() and 0xFFFF
    private fun profPlat(at: Int, i: Int): String =
        if (b.get(at + 6).toInt() == 0) "" else string(b.getShort(at + 7 + 4 * profN(at) + 2 * i).toInt() and 0xFFFF)

    private fun tripProfile(t: Int): Int = b.getInt(tripsAt + 18 * t)
    private fun tripStart(t: Int): Int = b.getShort(tripsAt + 18 * t + 4).toInt() and 0xFFFF
    private fun tripBitmap(t: Int): Int = b.getInt(tripsAt + 18 * t + 6)
    private fun tripHeadsign(t: Int): String = string(b.getInt(tripsAt + 18 * t + 10))
    private fun tripNumber(t: Int): String = string(b.getInt(tripsAt + 18 * t + 14))

    private fun runs(bitmap: Int, day: Int): Boolean {
        if (day < 0 || day >= nDays) return false
        return (b.get(bitmapsAt + bitmapLen * bitmap + (day shr 3)).toInt() and (1 shl (day and 7))) != 0
    }

    private fun changeMin(s: Int): Int = ((b.getShort(footpathsDataAt + b.getInt(secFootpaths + 4 * s)).toInt() and 0xFFFF) + 59) / 60

    /** Calls f(neighbour, seconds) for every footpath out of s. */
    private inline fun footpaths(s: Int, f: (Int, Int) -> Unit) {
        val at = footpathsDataAt + b.getInt(secFootpaths + 4 * s)
        val n = b.getShort(at + 2).toInt() and 0xFFFF
        for (k in 0 until n) f(b.getInt(at + 4 + 6 * k), b.getShort(at + 8 + 6 * k).toInt() and 0xFFFF)
    }

    /** Calls f(pattern, position) for every pattern serving s. */
    private inline fun patternsAt(s: Int, f: (Int, Int) -> Unit) {
        val at = stopPatternsDataAt + b.getInt(secStopPatterns + 4 * s)
        val n = b.getShort(at).toInt() and 0xFFFF
        for (k in 0 until n) f(b.getInt(at + 2 + 6 * k), b.getShort(at + 6 + 6 * k).toInt() and 0xFFFF)
    }

    // ---- stops ----

    fun distanceM(i: Int, p: LatLng): Double {
        val k = cos(Math.toRadians(p.latitude)) * 111_320.0
        return hypot((stopLat(i) - p.latitude) * 111_320.0, (stopLon(i) - p.longitude) * k)
    }

    /** Served stops within maxM of p, nearest first. */
    fun nearest(p: LatLng, maxM: Double = REACH_M, limit: Int = 8): List<Pair<Int, Double>> {
        val dLat = maxM / 111_320.0; val dLon = dLat / cos(Math.toRadians(p.latitude))
        val out = ArrayList<Pair<Int, Double>>()
        for (i in 0 until nStops) {
            if (abs(stopLat(i) - p.latitude) > dLat || abs(stopLon(i) - p.longitude) > dLon) continue
            if (!served(i)) continue
            val d = distanceM(i, p)
            if (d <= maxM) out.add(Pair(i, d))
        }
        return out.sortedBy { it.second }.take(limit)
    }

    private fun served(s: Int): Boolean = (b.getShort(stopPatternsDataAt + b.getInt(secStopPatterns + 4 * s)).toInt() and 0xFFFF) > 0

    /** Stops whose name contains every word of the query, accents and case ignored; shortest names first. */
    fun searchStops(q: String, limit: Int = 25): List<Int> {
        val words = fold(q).split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        for (i in 0 until nStops) {
            val n = folded[i] ?: fold(stopName(i)).also { folded[i] = it }
            if (words.all { n.contains(it) } && served(i)) out.add(i)
        }
        return out.sortedWith(compareBy({ !folded[it]!!.startsWith(words[0]) }, { folded[it]!!.length })).take(limit)
    }

    // ---- time ----

    private fun dayOf(t: Long): Pair<Int, Int> {
        val z = Instant.ofEpochMilli(t).atZone(ZONE)
        val day = ChronoUnit.DAYS.between(feedStart, z.toLocalDate()).toInt()
        return Pair(day, z.hour * 60 + z.minute)
    }

    private fun epoch(day: Int, minutes: Int): Long =
        feedStart.plusDays(day.toLong()).atStartOfDay(ZONE).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()

    // ---- departures at a stop ----

    class Departure(val time: Long, val kind: Int, val line: String, val headsign: String, val platform: String, val number: String)

    fun departures(stop: Int, from: Long, limit: Int = 20): List<Departure> {
        val (day, minutes) = dayOf(from)
        val out = ArrayList<Departure>()
        patternsAt(stop) { p, pos ->
            if (pos >= patN(p) - 1) return@patternsAt
            val first = patTripFirst(p); val cnt = patTripCount(p)
            val r = patRoute(p); val kind = Journey.kindOf(routeType(r)); val line = lineName(r)
            for (t in first until first + cnt) {
                val at = profAt(tripProfile(t))
                val dep = tripStart(t) + profDep(at, pos)
                for (dd in 0..1) {
                    val m = dep - 1440 * dd
                    if (m < minutes || m > minutes + 180 || !runs(tripBitmap(t), day - dd)) continue
                    out.add(Departure(epoch(day, m), kind, line, tripHeadsign(t), profPlat(at, pos), tripNumber(t)))
                }
            }
        }
        return out.sortedBy { it.time }.take(limit)
    }

    private fun lineName(r: Int): String {
        val s = routeShort(r); val d = routeDesc(r)
        return if (s.isBlank()) d else if (d.isBlank() || s.startsWith(d) || d == "B" || d == "T") s else "$d $s"
    }

    // ---- planning ----

    /**
     * Up to `count` journeys from `from` to `to`, leaving at or after `depart`: the earliest arrival
     * from each departure, then the following departures. Walks to and from the stops are straight
     * lines here; the caller may route them.
     */
    fun plan(from: LatLng, to: LatLng, depart: Long, count: Int = 4): List<Journey> {
        val out = ArrayList<Journey>()
        var t = depart
        val origins = nearest(from, REACH_M, 40); val dests = nearest(to, REACH_M, 40)
        if (origins.isEmpty() || dests.isEmpty()) return out
        var tries = 0
        while (out.size < count && tries < count + 3) {
            tries++
            val found = planOnce(from, to, origins, dests, t) ?: break
            val best = found.minByOrNull { it.arr } ?: break
            for (j in found) {
                // the same vehicles reached a few minutes later are the same journey: keep the later start
                val twin = out.indexOfFirst { same(it, j) }
                if (twin < 0) out.add(j) else if (j.dep > out[twin].dep) out[twin] = j
            }
            val ride = best.firstRide ?: break
            t = ride.dep + 60_000
        }
        return Planner.dominated(out).take(count)
    }

    private fun same(a: Journey, b: Journey): Boolean {
        val ra = a.legs.filter { it.ride }; val rb = b.legs.filter { it.ride }
        if (ra.isEmpty() || rb.isEmpty()) return ra.isEmpty() && rb.isEmpty()
        return a.arr == b.arr && ra.last().dep == rb.last().dep && ra.last().line == rb.last().line
    }

    /** One RAPTOR run; the non-dominated journeys by number of rides. */
    private fun planOnce(from: LatLng, to: LatLng, origins: List<Pair<Int, Double>>, dests: List<Pair<Int, Double>>, depart: Long): List<Journey>? {
        val (day, startMin) = dayOf(depart)
        val arr = Array(ROUNDS + 1) { IntArray(nStops) { INF } }
        val best = IntArray(nStops) { INF }
        val viaType = Array(ROUNDS + 1) { ByteArray(nStops) }        // 1 ride, 2 foot, 3 origin
        val viaFrom = Array(ROUNDS + 1) { IntArray(nStops) }
        val viaTrip = Array(ROUNDS + 1) { IntArray(nStops) }
        val viaBoard = Array(ROUNDS + 1) { IntArray(nStops) }       // boarding position; for foot: seconds
        val viaAlight = Array(ROUNDS + 1) { IntArray(nStops) }
        val viaDay = Array(ROUNDS + 1) { ByteArray(nStops) }        // 0 = today's trip, 1 = yesterday's
        var marked = HashSet<Int>()
        for ((s, d) in origins) {
            val m = startMin + (walkSeconds(d) + 59) / 60
            arr[0][s] = m; best[s] = m; viaType[0][s] = 3; marked.add(s)
        }
        var bound = INF
        val destWalk = HashMap<Int, Int>().also { for ((s, d) in dests) it[s] = (walkSeconds(d) + 59) / 60 }

        for (k in 1..ROUNDS) {
            System.arraycopy(arr[k - 1], 0, arr[k], 0, nStops)
            // patterns touched, with the earliest position marked on each
            val queue = HashMap<Int, Int>()
            for (s in marked) patternsAt(s) { p, pos -> val old = queue[p]; if (old == null || pos < old) queue[p] = pos }
            val newly = HashSet<Int>()
            for ((p, pos0) in queue) {
                val n = patN(p)
                var trip = -1; var tripDay = 0; var boardPos = -1; var tripAt = 0; var tripStart0 = 0
                for (i in pos0 until n) {
                    val s = patStop(p, i)
                    if (trip >= 0) {
                        val a = tripStart0 + profArr(tripAt, i) - 1440 * tripDay
                        if (a < best[s] && a < bound) {
                            arr[k][s] = a; best[s] = a; newly.add(s)
                            viaType[k][s] = 1; viaFrom[k][s] = patStop(p, boardPos); viaTrip[k][s] = trip
                            viaBoard[k][s] = boardPos; viaAlight[k][s] = i; viaDay[k][s] = tripDay.toByte()
                            destWalk[s]?.let { w -> if (a + w < bound) bound = a + w }
                        }
                    }
                    // board an earlier trip here if the previous round reached this stop in time
                    val reached = arr[k - 1][s]
                    if (reached < INF && i < n - 1) {
                        val ready = if (viaType[k - 1][s].toInt() == 1) reached + changeMin(s) else reached
                        val cur = if (trip >= 0) tripStart0 + profDep(tripAt, i) - 1440 * tripDay else INF
                        if (ready < cur) {
                            val e = earliestTrip(p, i, ready, day)
                            if (e != null && (trip < 0 || e.dep < cur)) {
                                trip = e.trip; tripDay = e.dayOffset; boardPos = i; tripAt = profAt(tripProfile(e.trip)); tripStart0 = tripStart(e.trip)
                            }
                        }
                    }
                }
            }
            if (newly.isEmpty()) break
            // footpaths from everything reached by a ride this round
            val byFoot = HashSet<Int>()
            for (s in newly) {
                val a = arr[k][s]
                footpaths(s) { j, sec ->
                    val t = a + (sec + 59) / 60
                    if (t < best[j] && t < bound) {
                        arr[k][j] = t; best[j] = t; byFoot.add(j)
                        viaType[k][j] = 2; viaFrom[k][j] = s; viaBoard[k][j] = sec
                        destWalk[j]?.let { w -> if (t + w < bound) bound = t + w }
                    }
                }
            }
            marked = HashSet(newly).also { it.addAll(byFoot) }
        }

        // journeys: for each number of rides, the best destination stop
        val journeys = ArrayList<Journey>()
        var lastArr = INF
        for (k in 1..ROUNDS) {
            var bestS = -1; var bestT = INF
            for ((s, w) in destWalk) { val a = arr[k][s]; if (a < INF && a + w < bestT) { bestT = a + w; bestS = s } }
            if (bestS < 0 || bestT >= lastArr) continue
            // only count it when the destination was reached in exactly k rounds (otherwise it is the previous journey)
            if (viaType[k][bestS].toInt() == 0 && k > 1 && arr[k][bestS] == arr[k - 1][bestS]) continue
            val j = rebuild(from, to, origins, destWalk, arr, viaType, viaFrom, viaTrip, viaBoard, viaAlight, viaDay, k, bestS, day, depart)
            if (j != null) { journeys.add(j); lastArr = bestT }
        }
        if (journeys.isEmpty()) {
            // maybe a plain walk
            val d = distanceOf(from, to)
            if (d <= 2 * REACH_M) {
                val w = walkSeconds(d)
                val a = TStop("", from); val z = TStop("", to)
                return listOf(Journey(listOf(Leg(false, a, z, depart, depart + w * 1000L, Journey.WALK, "", "", "", "", emptyList(), listOf(from, to))), true))
            }
            return null
        }
        return journeys
    }

    private class Found(val trip: Int, val dep: Int, val dayOffset: Int)

    /** Earliest trip of pattern p departing position i at or after `after` (minutes of `day`), today's or yesterday's. */
    private fun earliestTrip(p: Int, i: Int, after: Int, day: Int): Found? {
        val first = patTripFirst(p); val cnt = patTripCount(p)
        var bestTrip = -1; var bestDep = INF; var bestOff = 0
        for (t in first until first + cnt) {
            val bm = tripBitmap(t)
            val at = profAt(tripProfile(t))
            val dep = tripStart(t) + profDep(at, i)
            if (dep >= after && dep < bestDep && runs(bm, day)) { bestDep = dep; bestTrip = t; bestOff = 0 }
            val y = dep - 1440
            if (y >= after && y < bestDep && runs(bm, day - 1)) { bestDep = y; bestTrip = t; bestOff = 1 }
        }
        return if (bestTrip < 0) null else Found(bestTrip, bestDep, bestOff)
    }

    private fun distanceOf(a: LatLng, b: LatLng): Double {
        val k = cos(Math.toRadians(a.latitude)) * 111_320.0
        return hypot((b.latitude - a.latitude) * 111_320.0, (b.longitude - a.longitude) * k)
    }

    private fun rebuild(
        from: LatLng, to: LatLng, origins: List<Pair<Int, Double>>, destWalk: Map<Int, Int>,
        arr: Array<IntArray>, viaType: Array<ByteArray>, viaFrom: Array<IntArray>, viaTrip: Array<IntArray>,
        viaBoard: Array<IntArray>, viaAlight: Array<IntArray>, viaDay: Array<ByteArray>, rounds: Int, dest: Int, day: Int, depart: Long,
    ): Journey? {
        val legs = ArrayList<Leg>()
        var s = dest; var k = rounds
        // the final walk
        val endStop = stop(dest)
        val wEnd = (destWalk[dest] ?: 0) * 60_000L
        val arrAtDest = epoch(day, arr[k][dest])
        legs.add(Leg(false, endStop, TStop("", to), arrAtDest, arrAtDest + wEnd, Journey.WALK, "", "", "", "", emptyList(), listOf(endStop.at, to)))
        var guard = 0
        while (guard++ < 40) {
            // find the round in which s got its time
            while (k > 0 && viaType[k][s].toInt() == 0) k--
            if (k == 0 || viaType[k][s].toInt() == 3) break
            when (viaType[k][s].toInt()) {
                2 -> {
                    val f = viaFrom[k][s]
                    val a = epoch(day, arr[k][s]); val sec = viaBoard[k][s]
                    legs.add(Leg(false, stop(f), stop(s), a - sec * 1000L, a, Journey.WALK, "", "", "", "", emptyList(), listOf(stop(f).at, stop(s).at)))
                    s = f
                    // a footpath follows a ride of the same round
                }
                1 -> {
                    val t = viaTrip[k][s]; val bp = viaBoard[k][s]; val ap = viaAlight[k][s]; val off = 1440 * viaDay[k][s]
                    val at = profAt(tripProfile(t)); val st = tripStart(t)
                    val prof = tripProfile(t); val p = b.getInt(profAt(prof))
                    val r = patRoute(p)
                    val stops = ArrayList<Pair<TStop, Long>>()
                    for (i in bp + 1..ap) stops.add(Pair(stop(patStop(p, i)), epoch(day, st + profArr(at, i) - off)))
                    val fromS = stop(patStop(p, bp))
                    val toS = stop(s)
                    legs.add(Leg(true, fromS, toS, epoch(day, st + profDep(at, bp) - off), epoch(day, st + profArr(at, ap) - off),
                        Journey.kindOf(routeType(r)), lineName(r), tripHeadsign(t), profPlat(at, bp), profPlat(at, ap), stops,
                        listOf(fromS.at) + stops.map { it.first.at }))
                    s = viaFrom[k][s]; k--
                }
                else -> break
            }
        }
        // the first walk, from the origin point
        val o = origins.firstOrNull { it.first == s } ?: return null
        val firstDep = legs.last().dep
        val w = walkSeconds(o.second) * 1000L
        val os = stop(s)
        legs.add(Leg(false, TStop("", from), os, firstDep - w, firstDep, Journey.WALK, "", "", "", "", emptyList(), listOf(from, os.at)))
        legs.reverse()
        return Journey(legs.filter { it.ride || it.seconds > 0 || it === legs.first() || it === legs.last() }, true)
    }

    /** Service date and minutes of a time, for the menu and tests. */
    fun debugDay(t: Long): String { val (d, m) = dayOf(t); return "$d/$m" }

    /** A departure for a time on the pack's first day, for the tests. */
    fun epochOnDay(day: Int, hour: Int, minute: Int): Long = epoch(day, hour * 60 + minute)

    fun dayIndexOf(t: Long): Int = dayOf(t).first
    fun startOf(t: Long): ZonedDateTime = Instant.ofEpochMilli(t).atZone(ZONE)
}
