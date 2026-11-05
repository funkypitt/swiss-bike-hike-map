package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosuresTest {
    private val epalinges = javaClass.getResource("/closures/epalinges.json")!!.readText()

    @Test fun realDiversionInFrench() {
        val c = Closures.parse(epalinges, "fr").single()
        assertTrue(c.detour)
        assertEquals("Déviation", c.kind)
        assertEquals("Epalinges", c.title)
        assertEquals("Trajet modifié", c.reason)
        assertEquals("jusqu'à nouvel ordre", c.duration)
        assertEquals("SuisseMobile / Service cantonal", c.source)
        assertEquals("", c.link)
    }

    @Test fun languagesAndFallbacks() {
        assertEquals("Diversion", Closures.parse(epalinges, "en").single().kind)
        assertEquals("en", Closures.lang("ru"))
        assertEquals("de", Closures.lang("de"))
        // a missing translation falls back on German; closures come before diversions
        val json = """{"results":[
            {"layerBodId":"a","featureId":1,"attributes":{"sperrungen_type":"detour","title_de":"Umweg","title_en":null}},
            {"layerBodId":"a","featureId":2,"attributes":{"sperrungen_type":"closure","title_en":"Bridge","url1_link_en":"https://x.ch/y"}},
            {"layerBodId":"a","featureId":2,"attributes":{"sperrungen_type":"closure"}}]}"""
        val l = Closures.parse(json, "en")
        assertEquals(2, l.size)
        assertEquals("Bridge", l[0].title); assertEquals("https://x.ch/y", l[0].link)
        assertEquals("Umweg", l[1].title)
        assertEquals(emptyList<Closures.Closure>(), Closures.parse("""{"results":[]}""", "fr"))
    }
}
