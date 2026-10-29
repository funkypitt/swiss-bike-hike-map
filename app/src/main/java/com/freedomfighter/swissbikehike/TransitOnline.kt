package com.freedomfighter.swissbikehike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The online timetable: transport.opendata.ch, the free Swiss public-transport API (no key,
 * official timetable with real-time delays). Used when the phone is connected; the offline pack
 * takes over otherwise. Answers are turned into the same Journey legs as the offline planner.
 */
object TransitOnline {
    private const val BASE = "https://transport.opendata.ch/v1/"
    const val CREDIT = "Timetable: transport.opendata.ch (Opendata.ch)"

    class Station(val id: String, val name: String, val at: LatLng, val distanceM: Double)

    private fun get(path: String): JSONObject {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "SwissBikersPedestriansMap")
        try {
            if (c.responseCode != 200) throw Exception("transport.opendata.ch: HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    /** "IR" + "95" = IR95, "B" + "201" = B 201; a bare train number (000143) is not a line and is dropped. */
    private fun lineName(cat: String, num: String): String = when {
        num.isBlank() || num == "null" || Regex("0*\\d{4,}").matches(num) -> cat
        num.uppercase(Locale.ROOT).startsWith(cat.uppercase(Locale.ROOT)) -> num
        cat.length <= 2 && num.length <= 3 -> "$cat$num"
        else -> "$cat $num"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun station(o: JSONObject): Station? {
        val c = o.optJSONObject("coordinate") ?: return null
        if (c.isNull("x") || c.isNull("y")) return null
        val id = o.optString("id", "")
        return Station(if (id == "null") "" else id, o.optString("name", ""), LatLng(c.getDouble("x"), c.getDouble("y")), o.optDouble("distance", 0.0))
    }

    /** Stops by name. */
    suspend fun search(q: String, limit: Int = 20): List<Station> = withContext(Dispatchers.IO) {
        val arr = get("locations?type=station&query=" + enc(q)).optJSONArray("stations") ?: JSONArray()
        (0 until arr.length()).mapNotNull { station(arr.getJSONObject(it)) }.filter { it.name.isNotBlank() }.take(limit)
    }

    /** Stops around a point, nearest first (the API gives the distance). */
    suspend fun nearest(p: LatLng, limit: Int = 8): List<Station> = withContext(Dispatchers.IO) {
        val arr = get("locations?type=station&x=${p.latitude}&y=${p.longitude}").optJSONArray("stations") ?: JSONArray()
        (0 until arr.length()).mapNotNull { station(arr.getJSONObject(it)) }.filter { it.name.isNotBlank() }
            .sortedBy { it.distanceM }.take(limit)
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private fun time(s: String?): Long = if (s.isNullOrBlank() || s == "null") 0L else runCatching { iso.parse(s)?.time ?: 0L }.getOrDefault(0L)

    /** Journeys between two stops, leaving at or after the given time. */
    suspend fun connections(from: Station, to: Station, depart: Long, limit: Int = 4): List<Journey> = withContext(Dispatchers.IO) {
        val d = Date(depart)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d); val time = SimpleDateFormat("HH:mm", Locale.US).format(d)
        val q = "connections?from=" + enc(from.id.ifBlank { from.name }) + "&to=" + enc(to.id.ifBlank { to.name }) +
            "&date=$date&time=$time&limit=$limit"
        val arr = get(q).optJSONArray("connections") ?: JSONArray()
        (0 until arr.length()).mapNotNull { runCatching { journey(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun journey(c: JSONObject): Journey? {
        val sections = c.getJSONArray("sections")
        val legs = ArrayList<Leg>()
        for (i in 0 until sections.length()) {
            val s = sections.getJSONObject(i)
            val dep = s.getJSONObject("departure"); val arr = s.getJSONObject("arrival")
            val fromS = station(dep.getJSONObject("station")) ?: continue
            val toS = station(arr.getJSONObject("station")) ?: continue
            val from = TStop(fromS.name, fromS.at, fromS.id); val to = TStop(toS.name, toS.at, toS.id)
            val tDep = time(dep.optString("departure")) + delay(dep); val tArr = time(arr.optString("arrival")) + delay(arr)
            val j = s.optJSONObject("journey")
            if (j == null) {
                legs.add(Leg(false, from, to, tDep, tArr, Journey.WALK, "", "", "", "", emptyList(), listOf(from.at, to.at)))
                continue
            }
            val cat = j.optString("category", ""); val num = j.optString("number", "")
            val line = lineName(cat, num)
            val stops = ArrayList<Pair<TStop, Long>>()
            val pass = j.optJSONArray("passList") ?: JSONArray()
            for (k in 1 until pass.length()) {
                val p = pass.getJSONObject(k)
                val st = station(p.getJSONObject("station")) ?: continue
                stops.add(Pair(TStop(st.name, st.at, st.id), time(p.optString("arrival")).let { if (it == 0L) time(p.optString("departure")) else it }))
            }
            if (stops.isEmpty() || stops.last().first.name != to.name) stops.add(Pair(to, tArr))
            legs.add(Leg(true, from, to, tDep, tArr, Journey.kindOfCategory(cat), line, j.optString("to", ""),
                plat(dep), plat(arr), stops, listOf(from.at) + stops.map { it.first.at }))
        }
        if (legs.isEmpty()) return null
        return Journey(legs, false)
    }

    private fun delay(o: JSONObject): Long = if (o.isNull("delay")) 0L else o.optInt("delay", 0) * 60_000L
    private fun plat(o: JSONObject): String {
        val p = o.optJSONObject("prognosis")?.optString("platform", "")?.takeIf { it.isNotBlank() && it != "null" }
        return p ?: o.optString("platform", "").let { if (it == "null") "" else it }
    }

    class Board(val time: Long, val kind: Int, val line: String, val headsign: String, val platform: String, val delayMin: Int)

    /** The next departures at a stop. */
    suspend fun departures(st: Station, limit: Int = 20): List<Board> = withContext(Dispatchers.IO) {
        val arr = get("stationboard?" + (if (st.id.isNotBlank()) "id=" + enc(st.id) else "station=" + enc(st.name)) + "&limit=$limit").optJSONArray("stationboard") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val e = arr.getJSONObject(i)
            val stop = e.optJSONObject("stop") ?: return@mapNotNull null
            val cat = e.optString("category", ""); val num = e.optString("number", "")
            val line = lineName(cat, num)
            val delay = if (stop.isNull("delay")) 0 else stop.optInt("delay", 0)
            Board(time(stop.optString("departure")), Journey.kindOfCategory(cat), line, e.optString("to", ""), plat(stop), delay)
        }
    }
}
