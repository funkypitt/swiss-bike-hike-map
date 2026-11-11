package com.freedomfighter.swissbikehike

import btools.router.TurnHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.maplibre.android.geometry.LatLng

/** The street names after the places (tools/make-places.py, fixture app/src/test/resources/places/test.osm). */
class StreetsTest {
    private fun streets(): Streets {
        val s = Places.of(javaClass.getResourceAsStream("/test.places")!!.readBytes()).streets
        assertNotNull("the fixture pack has a streets section", s)
        return s!!
    }

    @Test fun piecesOfOneStreetAreJoinedAndRefsKept() {
        // « Rue Est » in two pieces is one line; the nameless path is left out; the main road keeps its number
        assertEquals(3, streets().size)
    }

    @Test fun eachStepIsNamedByTheStreetItRunsAlong() {
        val pts = listOf(LatLng(46.5400, 6.6300), LatLng(46.5400, 6.6340), LatLng(46.5440, 6.6340), LatLng(46.5440, 6.6380), LatLng(46.5480, 6.6380))
        val turns = listOf(Router.Turn(1, TurnHints.TL, 0), Router.Turn(2, TurnHints.TR, 0), Router.Turn(3, TurnHints.TL, 0), Router.Turn(4, TurnHints.END, 0))
        assertEquals(listOf("Rue Est", "Avenue Nord", "", "9", ""), streets().stepNames(pts, turns))
    }

    @Test fun aRouteBesideAStreetDoesNotTakeItsName() {
        // 60 m north of « Rue Est », parallel: too far to be on it
        val pts = listOf(LatLng(46.54054, 6.6300), LatLng(46.54054, 6.6340))
        assertEquals(listOf("", ""), streets().stepNames(pts, listOf(Router.Turn(1, TurnHints.END, 0))))
    }

    @Test fun aRouteCrossingAStreetDoesNotTakeItsName() {
        // going north across « Rue Est »: the angle is wrong
        val pts = listOf(LatLng(46.5390, 6.6310), LatLng(46.5410, 6.6310))
        assertEquals(listOf("", ""), streets().stepNames(pts, listOf(Router.Turn(1, TurnHints.END, 0))))
    }
}
