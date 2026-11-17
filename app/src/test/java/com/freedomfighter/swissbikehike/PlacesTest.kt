package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The places pack built by tools/make-places.py from app/src/test/resources/places/test.osm. */
class PlacesTest {
    private fun pack(): Places = Places.of(javaClass.getResourceAsStream("/test.places")!!.readBytes())

    @Test fun readsHeaderAndCategories() {
        val p = pack()
        assertEquals(14, p.nPlaces)
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

    @Test fun cafesShopsAndContactDetails() {
        val p = pack()
        val cafe = p.search("romand", 46.52, 6.63).single().place
        assertEquals(Places.Cat.CAFE, cafe.cat)
        assertEquals("info@example.org", cafe.extras["mail"])
        assertEquals("1003", cafe.extras["pc"])
        val shop = p.search("librairie", 46.52, 6.63).single().place
        assertEquals(Places.Cat.SHOP, shop.cat)
        assertEquals("books", shop.extras["shop"])
        // a vacant shop is not a place
        assertEquals(1, p.nearby(46.52, 6.63, 1000.0, setOf(Places.Cat.SHOP), 10).size)
    }

    @Test fun aNameAndATownAreReadAsWhatAndWhere() {
        val p = pack()
        // the dentist says no town in its address: the town comes from the places around that do
        val w = p.where("ardentis lausanne")!!
        assertEquals("ardentis", w.what); assertEquals("Lausanne", w.town.name)
        assertEquals(46.521, w.town.at.latitude, 5e-3)
        val there = p.search(w.what, w.town.at.latitude, w.town.at.longitude, 15, Places.TOWN_M)
        assertEquals(1, there.size)
        assertEquals(Places.Cat.DOCTOR, there[0].place.cat)
        assertEquals(6.6246, there[0].place.at.longitude, 1e-4)
        // the other town's one, the town first, a linking word, accents and case
        val bern = p.where("ardentis in Bern")!!
        assertEquals(7.4474, p.search(bern.what, bern.town.at.latitude, bern.town.at.longitude, 15, Places.TOWN_M).single().place.at.longitude, 1e-4)
        assertEquals("etude du lac", p.where("Étude du Lac à LAUSANNE")!!.what)
        assertEquals(Places.Cat.FIRM, p.search("etude", 46.52, 6.63).single().place.cat)
        // a town alone, a text without a town, a name that holds the town
        assertEquals(null, p.where("lausanne"))
        assertEquals(null, p.where("lausanne ardentis"))   // at the start, only a town of some size
        assertEquals(null, p.where("ardentis morges"))
        assertEquals("cafe", p.where("café bern")!!.what)
        assertEquals("Café Bern", p.search("café bern", 46.52, 6.63).single().place.name)
        assertEquals("buchs", Places.townKey("Buchs SG")); assertEquals("la tour de peilz", Places.townKey("La Tour-de-Peilz"))
    }

    @Test fun boxLimitsAndFilters() {
        val p = pack()
        val all = p.inBox(46.51, 6.62, 46.54, 6.65, null)
        assertEquals(11, all.size)
        // practices and firms are not among the dots of the map
        assertEquals(9, p.inBox(46.51, 6.62, 46.54, 6.65, Places.ON_MAP).size)
        assertEquals(1, p.inBox(46.51, 6.62, 46.54, 6.65, setOf(Places.Cat.BIKE_SHOP)).size)
        assertEquals(2, p.inBox(46.51, 6.62, 46.54, 6.65, null, 2).size)
    }
}
