package com.bydassistantng.drive

import com.bydassistantng.vehicle.StatusFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drive coach: what the last 15 or 30 minutes of samples say about the drive. */
class DriveAnalysisTest {
    private val min = 60_000L
    private val now = 100 * min

    private fun sample(minutesAgo: Double, speed: Double?, battery: Double? = null, eRange: Double? = null, fRange: Double? = null, odo: Double? = null, ac: Boolean? = null) =
        DriveSample((now - minutesAgo * min).toLong(), StatusFacts(batteryPercent = battery, electricRangeKm = eRange, fuelRangeKm = fRange, speedKmh = speed, odometerKm = odo, acOn = ac))

    @Test
    fun steadyHighwayDriveFromTheOdometer() {
        // 15 minutes at a steady 100 km/h, every 30 s: 25 km, battery and electric range falling.
        val samples = (0..30).map { i ->
            val t = 15.0 - i * 0.5
            sample(t, 100.0, battery = 80.0 - i * 0.5, eRange = 120.0 - i, fRange = 600.0, odo = 10_000.0 + i * (25.0 / 30), ac = true)
        }
        val r = DriveAnalysis.analyse(samples, 15, now)
        assertTrue(r.enoughData)
        assertEquals(15.0, r.coveredMinutes, 0.01)
        assertEquals("odometer", r.distanceFrom)
        assertEquals(25.0, r.distanceKm!!, 0.1)
        assertEquals(100.0, r.avgMovingSpeedKmh!!, 0.01)
        assertEquals(0.0, r.stoppedShare!!, 0.001)
        assertEquals(0, r.stops)
        assertEquals(0.0, r.speedChangePerMin!!, 0.001)
        assertEquals(15.0, r.batteryUsedPercent!!, 0.01)
        assertEquals(30.0, r.electricRangeUsedKm!!, 0.01)
        assertEquals(0.0, r.fuelRangeUsedKm!!, 0.01)
        // 30 km of range for 25 km driven: 1.2, more than the car estimated.
        assertEquals(1.2, r.rangeUsedPerKm!!, 0.01)
        assertEquals(6.0, r.batteryPercentPer10Km!!, 0.01)
        assertEquals(1.0, r.acOnShare!!, 0.001)
    }

    @Test
    fun stopAndGoTownDriveFromTheSpeedSamples() {
        // Alternating 50 km/h and standing still, every 30 s, for 10 minutes; no odometer.
        val samples = (0..20).map { i -> sample(10.0 - i * 0.5, if (i % 2 == 0) 50.0 else 0.0) }
        val r = DriveAnalysis.analyse(samples, 15, now)
        assertEquals("speed", r.distanceFrom)
        // Half the time at an average of 25 km/h between samples: 10 min * 25 km/h = 4.2 km.
        assertEquals(4.2, r.distanceKm!!, 0.1)
        assertEquals(50.0, r.avgMovingSpeedKmh!!, 0.01)
        assertEquals(10, r.stops)
        assertTrue(r.stoppedShare!! > 0.4)
        // 50 km/h change every half minute: 100 km/h per minute — very jerky.
        assertEquals(100.0, r.speedChangePerMin!!, 0.01)
    }

    @Test
    fun onlyTheWindowCounts() {
        val old = (0..20).map { i -> sample(40.0 - i * 0.5, 120.0) }
        val recent = (0..10).map { i -> sample(5.0 - i * 0.5, 40.0) }
        val r = DriveAnalysis.analyse(old + recent, 15, now)
        assertEquals(40.0, r.maxSpeedKmh!!, 0.01)
        val r30 = DriveAnalysis.analyse(old + recent, 30, now)
        assertEquals(120.0, r30.maxSpeedKmh!!, 0.01)
    }

    @Test
    fun aGapIsNotDrivenThrough() {
        // Car asleep for an hour between two samples: no distance is invented across it.
        val samples = listOf(sample(14.0, 100.0), sample(1.0, 100.0))
        val r = DriveAnalysis.analyse(samples, 15, now)
        assertNull(r.distanceKm)
        assertFalse(r.enoughData)
    }

    @Test
    fun tooLittleDataIsSaidSoAndUnknownsStayUnknown() {
        val r = DriveAnalysis.analyse(listOf(sample(1.0, null)), 15, now)
        assertFalse(r.enoughData)
        assertNull(r.distanceKm)
        assertNull(r.avgMovingSpeedKmh)
        assertNull(r.batteryUsedPercent)
        assertNull(r.rangeUsedPerKm)
        assertNull(r.acOnShare)
        assertTrue(DriveAnalysis.analyse(emptyList(), 30, now).samples == 0)
    }

    @Test
    fun theLogKeepsOnlyTheLastStretchAndRestartsOnAClockJump() {
        val log = DriveLog(keepMs = 10 * min)
        for (i in 0..30) log.add(sample(30.0 - i, 50.0))
        val kept = log.snapshot()
        assertTrue(kept.first().atMs >= kept.last().atMs - 10 * min)
        log.add(DriveSample(kept.first().atMs - min, StatusFacts(speedKmh = 1.0)))
        assertEquals(1, log.snapshot().size)
    }
}
