package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FirmsTest {
    private class F(val name: String, val street: String, val zip: Int, val city: String, val lat: Double, val lon: Double, val flag: Int = 0)

    /** A pack laid out as tools/make-firms.py writes it. */
    private fun pack(firms: List<F>): Firms {
        val strings = ByteArrayOutputStream(); val offs = HashMap<String, Int>()
        fun s(t: String): Int = offs.getOrPut(t) {
            val raw = t.toByteArray(Charsets.UTF_8); val o = strings.size()
            strings.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(raw.size.toShort()).array()); strings.write(raw); o
        }
        s("")
        val locs = ArrayList<String>()
        val rows = ByteBuffer.allocate(24 * firms.size).order(ByteOrder.LITTLE_ENDIAN)
        val index = sortedMapOf<String, MutableList<Int>>()
        for ((i, f) in firms.withIndex()) {
            if (f.city !in locs) locs.add(f.city)
            rows.putInt(s(f.name)).putInt(s(f.street)).putInt(Math.round(f.lat * 1e6).toInt()).putInt(Math.round(f.lon * 1e6).toInt())
                .putShort(f.zip.toShort()).putShort(locs.indexOf(f.city).toShort()).putShort(f.flag.toShort()).putShort(0)
            for (t in Firms.tokens(f.name).toSet()) index.getOrPut(t) { ArrayList() }.add(i)
        }
        val tok = ByteBuffer.allocate(12 * index.size).order(ByteOrder.LITTLE_ENDIAN)
        val post = ByteArrayOutputStream(); var n = 0
        for ((t, ids) in index) {
            tok.putInt(s(t)).putInt(n).putInt(ids.size)
            for (id in ids) post.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id).array())
            n += ids.size
        }
        val locTable = ByteBuffer.allocate(4 * locs.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> locs.forEach { b.putInt(s(it)) } }
        val oStrings = 48; val oFirms = oStrings + strings.size(); val oLocs = oFirms + rows.capacity()
        val oTok = oLocs + locTable.capacity(); val oPost = oTok + tok.capacity()
        val head = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46484253).putInt(1).putInt(20261119).putInt(firms.size).putInt(index.size).putInt(locs.size)
            .putInt(oStrings).putInt(oFirms).putInt(oLocs).putInt(oTok).putInt(oPost).putInt(0)
        val out = ByteArrayOutputStream()
        out.write(head.array()); out.write(strings.toByteArray()); out.write(rows.array()); out.write(locTable.array()); out.write(tok.array()); out.write(post.toByteArray())
        return Firms.of(out.toByteArray())
    }

    private val sample = listOf(
        F("Ardentis Clinique Dentaire Vevey SA", "Rue du Collège 3", 1800, "Vevey", 46.4603, 6.8419),
        F("ARDENTIS CLINIQUE DENTAIRE LAUSANNE SA", "Place Chauderon 16", 1003, "Lausanne", 46.5237, 6.6257),
        F("Garage de l'Étoile Sàrl", "Route de Berne 4", 1010, "Lausanne", 46.5400, 6.6600),
        F("Garage du Lac SA", "Quai Perdonnet 1", 1800, "Vevey", 46.4590, 6.8440),
        F("Boulangerie Lausanne-Gare Sàrl, en liquidation", "c/o Dupont", 1003, "Lausanne", 46.5170, 6.6290, flag = 2),
    )

    @Test fun folding() {
        assertEquals("garage de l etoile sarl", Firms.fold("Garage de l'Étoile Sàrl"))
        assertEquals(listOf("garage", "etoile"), Firms.tokens("Garage de l'Étoile Sàrl"))
        assertEquals("aime pouly", Firms.nameKey("Aimé Pouly SA"))
        assertEquals("rue du college", Firms.streetKey("Rue du Collège 3"))
        assertEquals("route de berne", Firms.streetKey("Route de Berne 304b"))
    }

    @Test fun aNameIsFoundByItsWordsBegunOrWhole() {
        val p = pack(sample)
        assertEquals(5, p.nFirms); assertEquals("2026-11-19", p.built)
        assertEquals(2, p.search("ardentis", 46.52, 6.63).size)
        assertEquals("ARDENTIS CLINIQUE DENTAIRE LAUSANNE SA", p.search("ardentis", 46.52, 6.63).first().name)   // nearest first
        assertEquals("Ardentis Clinique Dentaire Vevey SA", p.search("ardentis", 46.46, 6.84).first().name)
        assertEquals(2, p.search("ardent dent", 46.52, 6.63).size)
        assertEquals(1, p.search("étoile garage", 46.52, 6.63).size)
        assertTrue(p.search("zzz", 46.52, 6.63).isEmpty())
        assertTrue(p.search("sa de la", 46.52, 6.63).isEmpty())      // nothing to look for
    }

    @Test fun aWordMayBeTheTown() {
        val p = pack(sample)
        val v = p.search("ardentis vevey", 46.52, 6.63)
        assertEquals(1, v.size); assertEquals("1800", v[0].zip); assertEquals("Rue du Collège 3, 1800 Vevey", v[0].address)
        // « lausanne » is in one name and is the town of two more: the name that holds both words comes first
        val g = p.search("garage lausanne", 46.52, 6.63)
        assertEquals(listOf("Garage de l'Étoile Sàrl"), g.map { it.name })
        val l = p.search("boulangerie lausanne", 46.52, 6.63)
        assertEquals(1, l.size); assertTrue(l[0].allInName); assertFalse(l[0].exact)
        // the town alone gives the firms that bear its name, not all the firms of the town
        assertEquals(setOf("Boulangerie Lausanne-Gare Sàrl, en liquidation", "ARDENTIS CLINIQUE DENTAIRE LAUSANNE SA"), p.search("lausanne", 46.52, 6.63).map { it.name }.toSet())
    }

    @Test fun oneFirmUnderTwoNames() {
        // the legal form apart, at the same postcode
        assertTrue(Firms.same("Aimé Pouly", "", "1003", "Lausanne", "Aimé Pouly SA", "Rue Centrale 5", "1003", "Lausanne"))
        // the sign is part of the legal name
        assertTrue(Firms.same("Cityvet", "Avenue de Cour 1", "1007", "", "CityVet SA", "Avenue de Cour 1", "1007", "Lausanne"))
        assertTrue(Firms.same("Ardentis", "Place Chauderon", "", "Lausanne", "ARDENTIS CLINIQUE DENTAIRE LAUSANNE SA", "Place Chauderon 16", "1003", "Lausanne"))
        // the same name in two towns is two firms
        assertFalse(Firms.same("Ardentis", "", "1800", "Vevey", "ARDENTIS CLINIQUE DENTAIRE LAUSANNE SA", "Place Chauderon 16", "1003", "Lausanne"))
        assertFalse(Firms.same("Ardentis", "", "", "Vevey", "ARDENTIS CLINIQUE DENTAIRE LAUSANNE SA", "Place Chauderon 16", "1003", "Lausanne"))
        // same postcode, another street
        assertFalse(Firms.same("Garage du Lac", "Rue du Simplon 2", "1800", "Vevey", "Garage du Lac SA", "Quai Perdonnet 1", "1800", "Vevey"))
        // a name alone says nothing; other words are another firm
        assertFalse(Firms.same("Ardentis", "", "", "", "Ardentis SA", "", "", ""))
        assertFalse(Firms.same("Garage du Lac", "", "1800", "Vevey", "Garage de l'Étoile", "", "1800", "Vevey"))
        assertFalse(Firms.same("SA", "", "1800", "", "Garage du Lac SA", "", "1800", ""))
    }

    @Test fun anAddressCopiedFromElsewhere() {
        val a = Firms.copiedAddress("Jean Dupont\nRue du Collège 3\n1800 Vevey")
        assertNotNull(a); assertEquals("Jean Dupont", a!!.name); assertEquals("Rue du Collège 3", a.street); assertEquals("1800", a.zip); assertEquals("Vevey", a.city)
        assertEquals("Rue du Collège 3, 1800 Vevey", a.address)
        val b = Firms.copiedAddress("Place Chauderon 16, 1003 Lausanne")
        assertEquals("Place Chauderon 16", b!!.street); assertEquals("", b.name)
        val c = Firms.copiedAddress("Dupont Jean\n021 123 45 67\n1003 Lausanne")
        assertEquals("", c!!.street); assertEquals("1003 Lausanne", c.address)       // a phone number is not a street
        assertEquals("8001", Firms.copiedAddress("Bahnhofstrasse 1; CH-8001 Zürich")!!.zip)
        assertNull(Firms.copiedAddress("see you at 1900 hours"))
        assertNull(Firms.copiedAddress("021 123 45 67"))
        assertNull(Firms.copiedAddress("https://example.org/1003 Lausanne/x"))
        assertNull(Firms.copiedAddress("x".repeat(500)))
    }

    /** With -Dfirms.pack=…/switzerland.firms: the real pack answers as the app expects (the tool's folding and this one agree). */
    @Test fun theRealPack() {
        val path = System.getProperty("firms.pack").orEmpty()
        assumeTrue(path.isNotBlank() && File(path).exists())
        val p = Firms.open(File(path))
        assertTrue(p.nFirms > 500_000)
        val a = p.search("ardentis lausanne", 46.52, 6.63)
        assertTrue(a.isNotEmpty()); assertEquals("1003", a[0].zip); assertTrue(a[0].exact)
        assertTrue(Places.distance(a[0].lat, a[0].lon, 46.5237, 6.6257) < 300.0)
        assertTrue(p.search("ardent", 46.52, 6.63).isNotEmpty())
        assertTrue(p.search("garage", 46.52, 6.63).isEmpty() || p.search("garage", 46.52, 6.63).size <= 15)
    }

    @Test fun `an entry page of search_ch names its town, street and name`() {
        val e = Firms.webEntry("https://search.ch/tel/vevey/rue-de-lausanne-17/fleur-de-pains-sa")!!
        assertEquals("Fleur De Pains SA", e.name); assertEquals("Rue De Lausanne 17", e.street); assertEquals("Vevey", e.city)
        val p = Firms.webEntry("https://search.ch/tel/la-tour-de-peilz/jean-dupont.fr.html?x=1")!!
        assertEquals("Jean Dupont", p.name); assertEquals("", p.street); assertEquals("La Tour De Peilz", p.city)
        assertNull(Firms.webEntry("https://search.ch/tel/?was=dupont&wo=vevey"))
        assertNull(Firms.webEntry("https://search.ch/tel/opensearch.xml"))
        assertNull(Firms.webEntry("https://search.ch/map/vevey/rue-de-lausanne-17/x"))
        assertNull(Firms.webEntry("https://example.org/tel/vevey/rue-de-lausanne-17/fleur-de-pains-sa"))
        assertNull(Firms.webEntry("not an address"))
    }
}
