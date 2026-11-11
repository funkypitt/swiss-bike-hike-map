package com.freedomfighter.swissbikehike

import android.content.Context
import btools.router.TurnHints
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A planned itinerary: where from, where to, when, by which mode, and the result (a routed trip
 * for bike and foot, a public-transport journey for transit). Kept as JSON in the app's private
 * storage when saved, written out as plain text when shared.
 */
class Itinerary(
    val mode: Mode,
    val from: Stop,
    val to: Stop,
    val depart: Long,
    val trip: Trip?,
    val journey: Journey?,
    val file: String = "",
) {
    val arrive: Long get() = journey?.arr ?: (depart + (trip?.seconds ?: 0) * 1000L)
    val title: String get() = from.name + " → " + to.name
}

object Itineraries {
    private fun dir(ctx: Context) = File(ctx.filesDir, "itineraries").apply { mkdirs() }

    fun list(ctx: Context): List<Itinerary> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText()), f.name) }.getOrNull() }
            .sortedByDescending { it.depart }

    fun save(ctx: Context, it: Itinerary): Itinerary {
        val name = it.file.ifBlank { SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".json" }
        File(dir(ctx), name).writeText(toJson(it).toString())
        return Itinerary(it.mode, it.from, it.to, it.depart, it.trip, it.journey, name)
    }

    fun remove(ctx: Context, it: Itinerary) { if (it.file.isNotBlank()) File(dir(ctx), it.file).delete() }

    // ---- text, for sharing and for the detail screen ----

    fun clock(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    fun day(t: Long): String = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))

    /** The steps of the itinerary, one per line, in the person's language. */
    fun steps(ctx: Context, it: Itinerary): List<String> {
        val out = ArrayList<String>()
        val j = it.journey; val t = it.trip
        if (j != null) {
            for (l in j.legs) {
                if (l.ride) {
                    out.add(clock(l.dep) + "  " + l.from.name + plat(ctx, l.platDep))
                    out.add("      " + kindLabel(ctx, l.kind, l.line) + "  → " + l.headsign + "  ·  " + ctx.getString(R.string.nav_stops_left, l.stops.size))
                    out.add(clock(l.arr) + "  " + l.to.name + plat(ctx, l.platArr))
                } else {
                    val m = pathLength(l.path)
                    if (l.seconds >= 60 || m >= 50)
                        out.add("      " + ctx.getString(R.string.step_walk, NavService.navM(m), ctx.getString(R.string.minutes_n, maxOf(1, l.seconds / 60))))
                }
            }
        } else if (t != null) {
            out.add(clock(it.depart) + "  " + it.from.name)
            for (st in tripSteps(ctx, t)) {
                if (st.cmd == TurnHints.END) continue
                out.add("      " + NavService.glyph(st.cmd) + "  " + listOf(st.title, st.street).filter { x -> x.isNotBlank() }.joinToString(" · ") +
                    (if (st.metres >= 1) "  ·  " + NavService.navM(st.metres) else ""))
            }
            out.add(clock(it.arrive) + "  " + it.to.name)
        }
        return out
    }

    /**
     * One step of a routed trip as the preview lists it: the manoeuvre, the street taken, how far
     * it runs (to the next step), and where it is. `cmd` -1 = a stop on the way.
     */
    class Step(val cmd: Int, val exit: Int, val title: String, val street: String, val metres: Double, val index: Int, val at: LatLng)

    /** What to do at a turn, in words. */
    fun turnText(ctx: Context, cmd: Int, exit: Int): String = when (cmd) {
        TurnHints.TL -> ctx.getString(R.string.step_left); TurnHints.TSLL -> ctx.getString(R.string.step_slight_left); TurnHints.TSHL -> ctx.getString(R.string.step_sharp_left)
        TurnHints.TR -> ctx.getString(R.string.step_right); TurnHints.TSLR -> ctx.getString(R.string.step_slight_right); TurnHints.TSHR -> ctx.getString(R.string.step_sharp_right)
        TurnHints.KL, TurnHints.EL -> ctx.getString(R.string.step_keep_left); TurnHints.KR, TurnHints.ER -> ctx.getString(R.string.step_keep_right)
        TurnHints.TU, TurnHints.TLU, TurnHints.TRU -> ctx.getString(R.string.step_uturn)
        TurnHints.RNDB, TurnHints.RNLB -> if (exit > 0) ctx.getString(R.string.step_roundabout, exit) else ctx.getString(R.string.step_continue)
        TurnHints.END -> ctx.getString(R.string.step_arrive)
        else -> ctx.getString(R.string.step_continue)
    }

    private fun slight(cmd: Int) = cmd == TurnHints.TSLL || cmd == TurnHints.TSLR || cmd == TurnHints.KL || cmd == TurnHints.KR || cmd == TurnHints.C || cmd == TurnHints.BL

    /**
     * The steps of a trip: the start, every turn with the street it leads onto, the stops on the
     * way, the arrival. A slight bend that stays on the same named street is not a step.
     */
    fun tripSteps(ctx: Context, t: Trip): List<Step> {
        if (t.points.size < 2) return emptyList()
        class Raw(val cmd: Int, val exit: Int, val title: String, val street: String, val index: Int)
        val raw = ArrayList<Raw>()
        raw.add(Raw(TurnHints.C, 0, ctx.getString(R.string.step_start), t.names.getOrNull(0) ?: "", 0))
        for ((k, turn) in t.turns.withIndex()) {
            if (turn.cmd == TurnHints.END) break
            val street = t.nameAfter(k)
            val prev = raw.last()
            if (slight(turn.cmd) && (turn.cmd == TurnHints.C || turn.cmd == TurnHints.BL || (street.isNotBlank() && street == prev.street))) continue
            raw.add(Raw(turn.cmd, turn.exit, turnText(ctx, turn.cmd, turn.exit), street, turn.index))
        }
        // a nameless jog of a few metres (a forecourt, a crossing) is part of the next step in a list
        val end0 = t.points.size - 1
        for (i in raw.size - 1 downTo 1) {
            val r = raw[i]; val next = raw.getOrNull(i + 1)?.index ?: end0
            if (r.street.isBlank() && t.cum[next] - t.cum[r.index] < 25.0 && i + 1 < raw.size) raw.removeAt(i)
        }
        // the stops on the way (not the destination), where the route passes them
        for ((s, idx) in t.stopIdx.withIndex()) if (s < t.stops.size - 1)
            raw.add(Raw(-1, 0, ctx.getString(R.string.stop_n, s + 1), t.stops[s].name, idx))
        val end = t.points.size - 1
        val sorted = raw.sortedWith(compareBy({ it.index }, { if (it.cmd == -1) 1 else 0 }))
        val out = ArrayList<Step>()
        for ((i, r) in sorted.withIndex()) {
            val next = sorted.getOrNull(i + 1)?.index ?: end
            out.add(Step(r.cmd, r.exit, r.title, r.street, t.cum[next] - t.cum[r.index], r.index, t.points[r.index]))
        }
        out.add(Step(TurnHints.END, 0, ctx.getString(R.string.step_arrive), t.stops.lastOrNull()?.name ?: "", 0.0, end, t.points[end]))
        return out
    }

    /** The two or three streets that carry most of the trip, in route order (for « via … »). */
    fun via(ctx: Context, t: Trip): List<String> {
        val len = LinkedHashMap<String, Double>()
        for (st in tripSteps(ctx, t)) if (st.cmd != -1 && st.street.isNotBlank()) len[st.street] = (len[st.street] ?: 0.0) + st.metres
        val total = t.distanceM.coerceAtLeast(1)
        val top = len.entries.filter { it.value >= 0.15 * total || it.value >= 1500 }.sortedByDescending { it.value }.take(3).map { it.key }.toSet()
        return len.keys.filter { it in top }
    }

    fun summary(ctx: Context, it: Itinerary): String {
        val j = it.journey; val t = it.trip
        return when {
            j != null -> clock(j.dep) + " → " + clock(j.arr) + "   " + Fmt.hm(j.seconds) + "   " +
                when (j.changes) { 0 -> ctx.getString(R.string.direct); 1 -> ctx.getString(R.string.change_1); else -> ctx.getString(R.string.changes_n, j.changes) }
            t != null -> clock(it.depart) + " → " + clock(it.arrive) + "   " + Fmt.km(t.distanceM.toDouble()) + " km  ↑" + t.ascendM + " m  ↓" + t.descendM + " m" +
                (if (t.seconds > 0) "  " + Fmt.hm(t.seconds) else "")
            else -> ""
        }
    }

    fun text(ctx: Context, it: Itinerary): String {
        val head = ctx.getString(when (it.mode) { Mode.BIKE -> R.string.bike; Mode.WALK -> R.string.walk; Mode.TRANSIT -> R.string.transit }) +
            "  ·  " + day(it.depart) + "\n" + it.title + "\n" + summary(ctx, it)
        val lat = String.format(Locale.US, "%.5f", it.to.at.latitude); val lon = String.format(Locale.US, "%.5f", it.to.at.longitude)
        return head + "\n\n" + steps(ctx, it).joinToString("\n") + "\n\ngeo:$lat,$lon?q=$lat,$lon(" + android.net.Uri.encode(it.to.name) + ")"
    }

    private fun plat(ctx: Context, p: String) = if (p.isBlank()) "" else "  ·  " + ctx.getString(R.string.nav_platform, p)

    fun kindLabel(ctx: Context, kind: Int, line: String): String = when (kind) {
        Journey.BUS -> ctx.getString(R.string.kind_bus) + " " + line
        Journey.TRAM -> if (line.startsWith("m", ignoreCase = true)) ctx.getString(R.string.kind_metro) + " " + line else if (line.length <= 3) ctx.getString(R.string.kind_tram) + " " + line else line
        Journey.BOAT -> ctx.getString(R.string.kind_boat) + " " + line
        Journey.LIFT -> ctx.getString(R.string.kind_lift) + " " + line
        else -> line
    }.trim()

    fun pathLength(p: List<LatLng>): Double { var d = 0.0; for (i in 1 until p.size) d += p[i - 1].distanceTo(p[i]); return d }

    // ---- JSON ----

    private fun stopJson(s: Stop) = JSONObject().put("name", s.name).put("lat", s.at.latitude).put("lon", s.at.longitude).put("kind", s.kind).put("detail", s.detail)
    private fun stopOf(o: JSONObject) = Stop(LatLng(o.getDouble("lat"), o.getDouble("lon")), o.optString("name"), o.optString("kind", Favourites.PLACE), o.optString("detail"))
    private fun tstopJson(s: TStop) = JSONObject().put("name", s.name).put("lat", s.at.latitude).put("lon", s.at.longitude).put("id", s.id)
    private fun tstopOf(o: JSONObject) = TStop(o.optString("name"), LatLng(o.getDouble("lat"), o.getDouble("lon")), o.optString("id"))
    private fun pathJson(p: List<LatLng>) = JSONArray().also { a -> for (x in p) a.put(JSONArray().put(x.latitude).put(x.longitude)) }
    private fun pathOf(a: JSONArray) = (0 until a.length()).map { val p = a.getJSONArray(it); LatLng(p.getDouble(0), p.getDouble(1)) }

    fun toJson(it: Itinerary): JSONObject {
        val o = JSONObject().put("mode", it.mode.name).put("from", stopJson(it.from)).put("to", stopJson(it.to)).put("depart", it.depart)
        it.trip?.let { t ->
            val pts = JSONArray(); for ((i, p) in t.points.withIndex()) pts.put(JSONArray().put(p.latitude).put(p.longitude).put(if (t.ele[i].isNaN()) JSONObject.NULL else t.ele[i]))
            val turns = JSONArray(); for (x in t.turns) turns.put(JSONArray().put(x.index).put(x.cmd).put(x.exit))
            o.put("trip", JSONObject().put("points", pts).put("turns", turns).put("dist", t.distanceM).put("asc", t.ascendM).put("desc", t.descendM)
                .put("sec", t.seconds).put("names", JSONArray(t.names)).put("stops", JSONArray().also { a -> for (s in t.stops) a.put(stopJson(s)) }).put("name", t.name))
        }
        it.journey?.let { j ->
            val legs = JSONArray()
            for (l in j.legs) legs.put(JSONObject().put("ride", l.ride).put("from", tstopJson(l.from)).put("to", tstopJson(l.to)).put("dep", l.dep).put("arr", l.arr)
                .put("kind", l.kind).put("line", l.line).put("headsign", l.headsign).put("platDep", l.platDep).put("platArr", l.platArr)
                .put("stops", JSONArray().also { a -> for ((s, t) in l.stops) a.put(tstopJson(s).put("t", t)) }).put("path", pathJson(l.path)))
            o.put("journey", JSONObject().put("legs", legs).put("offline", j.offline))
        }
        return o
    }

    fun fromJson(o: JSONObject, file: String = ""): Itinerary {
        val trip = o.optJSONObject("trip")?.let { t ->
            val pa = t.getJSONArray("points")
            val pts = ArrayList<LatLng>(); val ele = DoubleArray(pa.length())
            for (i in 0 until pa.length()) { val p = pa.getJSONArray(i); pts.add(LatLng(p.getDouble(0), p.getDouble(1))); ele[i] = if (p.isNull(2)) Double.NaN else p.getDouble(2) }
            val ta = t.getJSONArray("turns")
            val turns = (0 until ta.length()).map { val x = ta.getJSONArray(it); Router.Turn(x.getInt(0), x.getInt(1), x.getInt(2)) }
            val sa = t.getJSONArray("stops")
            Trip(pts, ele, turns, t.getInt("dist"), t.getInt("asc"), t.getInt("desc"), t.getInt("sec"), (0 until sa.length()).map { stopOf(sa.getJSONObject(it)) }, false, t.optString("name"))
                .also { tr -> t.optJSONArray("names")?.let { na -> tr.names = (0 until na.length()).map { na.optString(it) } } }
        }
        val journey = o.optJSONObject("journey")?.let { j ->
            val la = j.getJSONArray("legs")
            Journey((0 until la.length()).map { i ->
                val l = la.getJSONObject(i); val sa = l.getJSONArray("stops")
                Leg(l.getBoolean("ride"), tstopOf(l.getJSONObject("from")), tstopOf(l.getJSONObject("to")), l.getLong("dep"), l.getLong("arr"), l.getInt("kind"),
                    l.optString("line"), l.optString("headsign"), l.optString("platDep"), l.optString("platArr"),
                    (0 until sa.length()).map { val s = sa.getJSONObject(it); Pair(tstopOf(s), s.getLong("t")) }, pathOf(l.getJSONArray("path")))
            }, j.optBoolean("offline"))
        }
        return Itinerary(Mode.of(o.optString("mode")), stopOf(o.getJSONObject("from")), stopOf(o.getJSONObject("to")), o.getLong("depart"), trip, journey, file)
    }
}
