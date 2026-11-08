package com.bydassistantng.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Naming where the car is, for the weather. */
class PlaceNameTest {

    @Test
    fun aTownFromOpenStreetMap() {
        val answer = """{"place_id":1,"name":"Monastir","display_name":"Monastir, Tunisie","address":{"city":"Monastir",""" +
            """"state":"Gouvernorat de Monastir","country":"Tunisie","country_code":"tn"}}"""
        assertEquals("Monastir, Tunisie", PlaceName.fromNominatim(answer))
    }

    @Test
    fun aVillageOrOnlyARegion() {
        assertEquals("Ksibet el Médiouni, Tunisia", PlaceName.fromNominatim("""{"address":{"village":"Ksibet el Médiouni","country":"Tunisia"}}"""))
        assertEquals("Monastir Governorate, Tunisia", PlaceName.fromNominatim("""{"address":{"state":"Monastir Governorate","country":"Tunisia"}}"""))
    }

    @Test
    fun escapedTextIsRead() {
        assertEquals("Sousse, تونس", PlaceName.fromNominatim("""{"address":{"city":"Sousse","country":"تونس"}}"""))
    }

    @Test
    fun nothingNamedIsNull() {
        assertNull(PlaceName.fromNominatim("""{"error":"Unable to geocode"}"""))
        assertNull(PlaceName.fromNominatim(null))
        assertNull(PlaceName.fromParts(null, null, null))
    }

    @Test
    fun fromTheHeadUnitsGeocoderAndAsCoordinates() {
        assertEquals("Monastir, Tunisia", PlaceName.fromParts("Monastir", "Monastir", "Tunisia"))
        assertEquals("Monastir Governorate, Tunisia", PlaceName.fromParts(null, "Monastir Governorate", "Tunisia"))
        assertEquals("35.764, 10.826", PlaceName.coordinates(35.7643, 10.8257))
    }
}
