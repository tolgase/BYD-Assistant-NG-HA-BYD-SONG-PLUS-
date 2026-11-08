package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers the welcome summary and the drive coach take from a car status reading. */
class StatusFactsTest {
    // The shape CarStatus.encode writes.
    private val status = """{"windows_open_percent":{"driver":0},"doors_open":{"driver":false},"ac":{"on":true,"set_temperature_c":22,"fan":3},""" +
        """"cabin_temperature_c":31,"outside_temperature_c":-2,"seats":{},"battery_percent":76,"electric_range_km":98,"fuel_range_km":540,""" +
        """"trip_computer":{"ev_mileage":4100.0,"last_fuel_con_phm":5.2,"total_mileage":12480.5},"speed_kmh":63.5}"""

    @Test
    fun everythingIsRead() {
        val f = StatusFacts.parse(status)
        assertEquals(76.0, f.batteryPercent!!, 0.001)
        assertEquals(98.0, f.electricRangeKm!!, 0.001)
        assertEquals(540.0, f.fuelRangeKm!!, 0.001)
        assertEquals(31.0, f.insideCelsius!!, 0.001)
        assertEquals(-2.0, f.outsideCelsius!!, 0.001)
        assertEquals(63.5, f.speedKmh!!, 0.001)
        assertEquals(true, f.acOn)
        // The odometer, not the EV-only kilometres.
        assertEquals(12480.5, f.odometerKm!!, 0.001)
        assertTrue(f.hasAny)
    }

    @Test
    fun whatTheCarDidNotSayStaysUnknown() {
        val f = StatusFacts.parse("""{"windows_open_percent":{},"ac":{"on":false}}""")
        assertNull(f.batteryPercent)
        assertNull(f.speedKmh)
        assertNull(f.odometerKm)
        assertEquals(false, f.acOn)
        assertFalse(StatusFacts.parse(null).hasAny)
        assertFalse(StatusFacts.parse("not json").hasAny)
    }

    @Test
    fun whoIsOnBoard() {
        val two = StatusFacts.parse("""{"seats_occupied":{"front_passenger":true,"rear_left":false,"rear_right":true}}""")
        assertEquals(listOf("front passenger seat", "rear right"), two.passengers)
        assertTrue(two.hasAny)
        assertEquals(emptyList<String>(), StatusFacts.parse("""{"seats_occupied":{"front_passenger":false}}""").passengers)
        // The car named no passenger seat: unknown, not "alone".
        assertNull(StatusFacts.parse(status).passengers)
    }

    @Test
    fun anOdometerUnderAnotherName() {
        assertEquals(900.0, StatusFacts.odometer("""{"trip_computer":{"total_odometer":900}}""")!!, 0.001)
        assertNull(StatusFacts.odometer("""{"trip_computer":{"ev_mileage":4100}}"""))
    }
}
