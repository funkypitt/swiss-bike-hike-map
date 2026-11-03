package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningHoursTest {
    private val names = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test fun weekdaysAndSaturday() {
        val w = OpeningHours.parse("Mo-Fr 07:00-12:00,13:30-19:00; Sa 08:00-14:00; PH off")!!
        // Tuesday 10:00: open until 12:00 today
        OpeningHours.status(w, 1, at(10)).let { assertTrue(it.open); assertEquals(0, it.inDays); assertEquals(at(12), it.minute) }
        // Tuesday 12:30: closed, opens 13:30 today
        OpeningHours.status(w, 1, at(12, 30)).let { assertFalse(it.open); assertEquals(0, it.inDays); assertEquals(at(13, 30), it.minute) }
        // Saturday 15:00: closed, opens Monday 07:00 (two days later)
        OpeningHours.status(w, 5, at(15)).let { assertFalse(it.open); assertEquals(2, it.inDays); assertEquals(at(7), it.minute) }
        assertEquals(listOf("Mo–Fr  07:00–12:00, 13:30–19:00", "Sa  08:00–14:00", "Su  closed"), OpeningHours.table(w, "closed", names))
    }

    @Test fun pastMidnightAndAlways() {
        val w = OpeningHours.parse("Mo-Sa 18:00-02:00")!!
        // Sunday 01:00 is still Saturday night
        OpeningHours.status(w, 6, at(1)).let { assertTrue(it.open); assertEquals(0, it.inDays); assertEquals(at(2), it.minute) }
        OpeningHours.status(w, 6, at(3)).let { assertFalse(it.open); assertEquals(1, it.inDays); assertEquals(at(18), it.minute) }
        assertTrue(OpeningHours.parse("24/7")!!.always)
        assertTrue(OpeningHours.status(OpeningHours.parse("24/7")!!, 3, at(4)).open)
    }

    @Test fun laterRuleWinsAndWrapAround() {
        val w = OpeningHours.parse("08:00-18:00; Su off; Fr-Mo 09:00-17:00")!!
        assertEquals(listOf(at(9)..at(17)), w.days[0])   // Monday from the third rule
        assertEquals(listOf(at(8)..at(18)), w.days[2])   // Wednesday from the first
        assertEquals(listOf(at(9)..at(17)), w.days[6])   // Sunday: off, then reopened by Fr-Mo
        assertEquals(listOf(at(8)..at(24)), OpeningHours.parse("Mo-Sa 08:00-24:00")!!.days[0])
        assertEquals(listOf(at(7)..at(19)), OpeningHours.parse("Mo-Fr: 07:00-19:00")!!.days[4])
    }

    @Test fun unreadableGivesNull() {
        assertNull(OpeningHours.parse("Jan-Mar Mo-Fr 08:00-12:00"))
        assertNull(OpeningHours.parse("sunrise-sunset"))
        assertNull(OpeningHours.parse("Mo-Fr 08:00+"))
        assertNull(OpeningHours.parse("by appointment"))
        assertNull(OpeningHours.parse(""))
        assertNull(OpeningHours.parse(null))
    }
}
