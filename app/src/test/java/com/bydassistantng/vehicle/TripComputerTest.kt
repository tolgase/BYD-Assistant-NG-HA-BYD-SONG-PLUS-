package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's trip computer and its consumption history. */
class TripComputerTest {

    @Test
    fun onlyConsumptionAndDistanceGettersAreRead() {
        assertTrue(TripComputer.wanted("getLastFuelConPHMValue"))
        assertTrue(TripComputer.wanted("getTotalMileageValue"))
        assertTrue(TripComputer.wanted("getTotalElecConValue"))
        assertFalse(TripComputer.wanted("getClass"))
        assertFalse(TripComputer.wanted("getInstance"))
        assertFalse(TripComputer.wanted("setFuelConValue"))
        assertFalse(TripComputer.wanted("getDoorState"))
    }

    @Test
    fun namesAreMadeReadable() {
        assertEquals("last_fuel_con_phm", TripComputer.key("getLastFuelConPHMValue"))
        assertEquals("total_mileage", TripComputer.key("getTotalMileageValue"))
        assertEquals("elec_percentage", TripComputer.key("getElecPercentageValue"))
    }

    @Test
    fun noReadingCodesAreDroppedAndRealValuesKept() {
        val selected = TripComputer.select(
            mapOf(
                "getLastFuelConPHMValue" to 5.234,
                "getTotalMileageValue" to 12_480,
                "getLastElecConPHMValue" to -10011,
                "getTotalFuelConValue" to 65535,
                "getAvgSpeedValue" to null,
                "getTripEnergyValue" to Double.NaN,
                "getClass" to 1,
                "getEvMileageValue" to 0,
            ),
        )
        assertEquals(mapOf("ev_mileage" to 0.0, "last_fuel_con_phm" to 5.23, "total_mileage" to 12480.0), selected)
    }

    @Test
    fun numericReturnTypes() {
        assertTrue(TripComputer.numeric(Int::class.javaPrimitiveType!!))
        assertTrue(TripComputer.numeric(Float::class.javaPrimitiveType!!))
        assertTrue(TripComputer.numeric(java.lang.Double::class.java))
        assertFalse(TripComputer.numeric(String::class.java))
        assertFalse(TripComputer.numeric(Boolean::class.javaPrimitiveType!!))
    }

    // ── History ──────────────────────────────────────────────────────────────────────────────────

    private val hour = 3_600_000L

    @Test
    fun aReadingIsKeptAtMostEveryHalfHourAndOnlyWhenSomethingChanged() {
        val first = EnergyReading(0, mapOf("fuel_range_km" to 600.0))
        var log = ConsumptionLog.record(emptyList(), first)
        assertEquals(1, log.size)
        // Too soon.
        log = ConsumptionLog.record(log, EnergyReading(10 * 60_000L, mapOf("fuel_range_km" to 590.0)))
        assertEquals(1, log.size)
        // Later but unchanged.
        log = ConsumptionLog.record(log, EnergyReading(2 * hour, mapOf("fuel_range_km" to 600.0)))
        assertEquals(1, log.size)
        // Later and changed.
        log = ConsumptionLog.record(log, EnergyReading(3 * hour, mapOf("fuel_range_km" to 540.0)))
        assertEquals(2, log.size)
        // Empty readings are never kept.
        assertEquals(log, ConsumptionLog.record(log, EnergyReading(9 * hour, emptyMap())))
    }

    @Test
    fun theLogIsCappedAndPreviousReadingsComeNewestFirst() {
        var log = emptyList<EnergyReading>()
        for (i in 0 until ConsumptionLog.MAX_READINGS + 10) {
            log = ConsumptionLog.record(log, EnergyReading(i * hour, mapOf("total_mileage" to i.toDouble())))
        }
        assertEquals(ConsumptionLog.MAX_READINGS, log.size)
        val previous = ConsumptionLog.previous(log, log.last().atMs + 2 * hour)
        assertEquals(ConsumptionLog.SHOWN, previous.size)
        assertEquals(2.0, previous.first().first, 0.01)
        assertTrue(previous.first().second.getValue("total_mileage") > previous.last().second.getValue("total_mileage"))
    }

    @Test
    fun storageRoundTripsAndSkipsDamagedLines() {
        val log = listOf(
            EnergyReading(1L, mapOf("battery_percent" to 80.0, "last_fuel_con_phm" to 5.2)),
            EnergyReading(2L * hour, mapOf("battery_percent" to 40.0)),
        )
        val text = ConsumptionLog.encode(log)
        assertEquals(log, ConsumptionLog.decode(text))
        assertEquals(log, ConsumptionLog.decode(text + "\nbroken\nx\ty=z"))
        assertTrue(ConsumptionLog.decode("").isEmpty())
    }
}
