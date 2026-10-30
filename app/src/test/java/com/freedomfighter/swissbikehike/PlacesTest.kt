package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The places pack built by tools/make-places.py from app/src/test/resources/places/test.osm. */
class PlacesTest {
    private fun pack(): Places = Places.of(javaClass.getResourceAsStream("/test.places")!!.readBytes())

    @Test fun readsHeaderAndCategories() {
        val p = pack()
        assertEquals(8, p.nPlaces)
        assertTrue(p.built.startsWith("20"))
        // a shelter of a bus stop and a node without our keys are not in; the toilets south of the border neither
        assertEquals(0, (0 until p.nPlaces).count { p.cat(it) == Places.Cat.SHELTER })
    }

    @Test fun nearbyNearestFirstWithinOneCategory() {
        val p = pack()
        val water = p.nearby(46.5200, 6.6300, 500.0, setOf(Places.Cat.WATER), 10)
        assertEquals(2, water.size)
        assertEquals("", water[0].place.name)                     // the nameless tap right there
        assertEquals("Fontaine de la Palud", water[1].place.name)
        assertTrue(water[1].metres > 100 && water[1].metres < 200)
        // the dry fountain is a fountain, not drinking water
        val fountains = p.nearby(46.5200, 6.6300, 500.0, setOf(Places.Cat.FOUNTAIN), 10)
        assertEquals(listOf("Fontaine sèche"), fountains.map { it.place.name })
    }

    @Test fun radiusGrowsUntilEnoughFound() {
        val p = pack()
        // the hut is 100 km away: a small radius finds nothing, the search widens until the limit or the cap
        val huts = p.nearby(46.5200, 6.6300, 1000.0, setOf(Places.Cat.HUT), 1, maxM = 200_000.0)
        assertEquals(listOf("Berghütte Test"), huts.map { it.place.name })
        assertEquals(0, p.nearby(46.5200, 6.6300, 1000.0, setOf(Places.Cat.HUT), 1, maxM = 2000.0).size)
    }

    @Test fun areaBecomesItsCentroidWithExtras() {
        val p = pack()
        val bakery = p.search("boulangerie", 46.52, 6.63).single().place
        assertEquals(Places.Cat.BAKERY, bakery.cat)
        assertEquals(46.531, bakery.at.latitude, 1e-3)
        assertEquals(6.641, bakery.at.longitude, 1e-3)
        assertEquals("Mo-Fr 06:00-18:00", bakery.extras["oh"])
    }

    @Test fun searchFoldsAccentsAndKeepsExtras() {
        val p = pack()
        val hits = p.search("grutli", 46.52, 6.63)
        assertEquals(1, hits.size)
        val cafe = hits[0].place
        assertEquals("Café du Grütli", cafe.name)
        assertEquals("+41 21 312 94 93", cafe.extras["tel"])
        assertEquals("Lausanne", cafe.extras["city"])
        assertEquals("Rue de la Mercerie 4", cafe.extras["addr"])
        assertEquals("swiss", cafe.extras["cuisine"])
        assertTrue(p.search("nothing like this", 46.52, 6.63).isEmpty())
        assertTrue(p.search("", 46.52, 6.63).isEmpty())
    }

    @Test fun boxLimitsAndFilters() {
        val p = pack()
        val all = p.inBox(46.51, 6.62, 46.54, 6.65, null)
        assertEquals(7, all.size)
        assertEquals(1, p.inBox(46.51, 6.62, 46.54, 6.65, setOf(Places.Cat.BIKE_SHOP)).size)
        assertEquals(2, p.inBox(46.51, 6.62, 46.54, 6.65, null, 2).size)
    }
}
