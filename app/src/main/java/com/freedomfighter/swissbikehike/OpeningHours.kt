package com.freedomfighter.swissbikehike

import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * OpenStreetMap opening hours, the common part of the syntax: « 24/7 », rules separated by « ; »,
 * each rule = days (Mo, Mo-Fr, Sa,Su, Fr-Mo; none = every day) then times (08:00-12:00,13:30-18:30,
 * past midnight like 18:00-02:00) or « off ». A later rule replaces the days it names. Public
 * holidays (PH, SH) are ignored. Anything else (months, weeks, dates, sunrise, « + ») makes the
 * whole text unreadable: `parse` gives null and the app shows the text as it is.
 */
object OpeningHours {
    private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private const val DAY = 1440

    /** Minutes from midnight, per weekday (0 = Monday); an end beyond 1440 runs into the next morning. */
    class Week(val days: List<List<IntRange>>) {
        val always: Boolean get() = days.all { d -> d.any { it.first <= 0 && it.last >= DAY } }
    }

    fun parse(text: String?): Week? {
        val s = text?.trim()?.replace(Regex("\"[^\"]*\""), "")?.lowercase(Locale.ROOT) ?: return null
        if (s.isEmpty()) return null
        if (s == "24/7") return Week(List(7) { listOf(0..DAY) })
        val days = MutableList(7) { emptyList<IntRange>() }
        for (rawRule in s.split(';', '|').map { it.trim() }.filter { it.isNotEmpty() }) {
            var rule = rawRule.trim().trimEnd(',')
            if (rule.startsWith("24/7")) { for (d in 0..6) days[d] = listOf(0..DAY); continue }
            // the day selector: everything up to the first digit or « off »/« closed »
            val m = Regex("^([a-z ,:\\-]*?)\\s*(\\d.*|off|closed)?$").find(rule) ?: return null
            val sel = m.groupValues[1].trim().trimEnd(':').trim()
            val times = m.groupValues[2].trim()
            val which = BooleanArray(7)
            if (sel.isEmpty()) which.fill(true)
            else {
                var named = false
                for (part in sel.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    if (part == "ph" || part == "sh") continue
                    val ab = part.split('-').map { it.trim() }
                    val a = DAYS.indexOf(ab[0]); val b = if (ab.size == 2) DAYS.indexOf(ab[1]) else a
                    if (a < 0 || b < 0 || ab.size > 2) return null
                    var d = a
                    while (true) { which[d] = true; if (d == b) break; d = (d + 1) % 7 }
                    named = true
                }
                if (!named) continue // a public-holiday rule only
            }
            val ranges = if (times.isEmpty() || times == "off" || times == "closed") emptyList() else {
                times.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { span ->
                    val t = Regex("^(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})$").find(span) ?: return null
                    val (h1, m1, h2, m2) = t.destructured
                    val from = h1.toInt() * 60 + m1.toInt()
                    var to = h2.toInt() * 60 + m2.toInt()
                    if (from > DAY || to > 2 * DAY) return null
                    if (to <= from) to += DAY
                    from..to
                }
            }
            if (times.isEmpty() && sel.isNotEmpty()) return null // days without hours: not something we read
            for (d in 0..6) if (which[d]) days[d] = ranges
        }
        return Week(days)
    }

    /** Open now (until `minute` of `inDays` days from today), or closed (opens then; -1 = not this week). */
    class Status(val open: Boolean, val inDays: Int, val minute: Int)

    /** The state at a weekday (0 = Monday) and minute. */
    fun status(w: Week, day: Int, minute: Int): Status {
        if (w.always) return Status(true, -1, -1)
        // open by a range of today, or by yesterday's range that runs past midnight
        val todays = w.days[day].firstOrNull { minute >= it.first && minute < it.last }
        val prev = w.days[(day + 6) % 7].firstOrNull { it.last > DAY && minute + DAY >= it.first && minute + DAY < it.last }
        if (todays != null || prev != null) {
            // follow the closing time over ranges that join (…-24:00 then 00:00-…)
            var endDay = if (todays != null) 0 else -1
            var end = todays?.last ?: prev!!.last
            repeat(7) {
                if (end < DAY) return@repeat
                val nd = endDay + 1; val nextStart = end - DAY
                val cont = w.days[(day + nd + 7) % 7].firstOrNull { it.first <= nextStart && it.last > nextStart } ?: return@repeat
                endDay = nd; end = cont.last
            }
            while (end >= DAY) { end -= DAY; endDay++ }
            return Status(true, endDay, end)
        }
        for (k in 0..7) {
            val d = (day + k) % 7
            val next = w.days[d].filter { k > 0 || it.first > minute }.minOfOrNull { it.first } ?: continue
            return Status(false, k, next)
        }
        return Status(false, -1, -1)
    }

    fun status(w: Week, now: Calendar = Calendar.getInstance()): Status =
        status(w, (now.get(Calendar.DAY_OF_WEEK) + 5) % 7, now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))

    fun clock(min: Int): String = if (min == DAY) "24:00" else String.format(Locale.US, "%02d:%02d", (min / 60) % 24, min % 60)

    /** Short weekday names in the phone's language, Monday first. */
    fun dayNames(locale: Locale = Locale.getDefault()): List<String> {
        val w = DateFormatSymbols.getInstance(locale).shortWeekdays // index 1 = Sunday
        return listOf(w[2], w[3], w[4], w[5], w[6], w[7], w[1]).map { it.trimEnd('.').replaceFirstChar { c -> c.titlecase(locale) } }
    }

    /** The week as lines: consecutive days with the same hours together (« Mon–Fri  07:00–19:00 »). */
    fun table(w: Week, closed: String, names: List<String> = dayNames()): List<String> {
        val out = ArrayList<String>()
        var d = 0
        while (d < 7) {
            var e = d
            while (e + 1 < 7 && w.days[e + 1] == w.days[d]) e++
            val days = if (e == d) names[d] else names[d] + "–" + names[e]
            val hours = if (w.days[d].isEmpty()) closed else w.days[d].joinToString(", ") { clock(it.first) + "–" + clock(if (it.last > DAY) it.last - DAY else it.last) }
            out.add("$days  $hours")
            d = e + 1
        }
        return out
    }
}
