package com.freedomfighter.swissbikehike

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:openSearch="http://a9.com/-/spec/opensearchrss/1.0/" xmlns:tel="http://tel.search.ch/api/spec/result/1.0/">
<openSearch:totalResults>2</openSearch:totalResults>
<entry><id>a</id><tel:type>Person</tel:type><tel:name>Meier</tel:name><tel:firstname>John</tel:firstname>
<tel:street>Rue du Lac</tel:street><tel:streetno>12</tel:streetno><tel:zip>1003</tel:zip><tel:city>Lausanne</tel:city><tel:canton>VD</tel:canton>
<tel:phone>+41211234567</tel:phone></entry>
<entry><id>b</id><tel:type>Organisation</tel:type><tel:name>Boulangerie M&amp;C SA</tel:name><tel:zip>1000</tel:zip><tel:city>Lausanne</tel:city><tel:phone>+41217654321</tel:phone></entry>
</feed>"""

    @Test fun parsesPeopleAndFirms() {
        val hits = Directory.parse(feed)
        assertEquals(2, hits.size)
        assertEquals("John Meier", hits[0].name)
        assertEquals("Rue du Lac 12, 1003 Lausanne", hits[0].address)
        assertEquals("+41211234567", hits[0].phone)
        assertEquals("Boulangerie M&C SA", hits[1].name)
        assertEquals("1000 Lausanne", hits[1].address)
    }
}
