package com.bydassistantng.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spoken drive tips: when one is said, which, and how rarely. */
class DriveTipsTest {
    private val min = 60_000L
    private val now = 1_000 * min

    private val calm = DriveReport(windowMinutes = 15, enoughData = true, distanceKm = 20.0, avgMovingSpeedKmh = 85.0, speedChangePerMin = 4.0, stoppedShare = 0.05, rangeUsedPerKm = 1.0, batteryPercent = 70.0)

    @Test
    fun aCalmDriveGetsNoTip() {
        assertNull(DriveTips.pick(calm, 40, emptyMap(), now))
    }

    @Test
    fun eachKindIsSaidWhenClearlyCalledFor() {
        assertEquals(TipKind.SPEED, DriveTips.pick(calm.copy(avgMovingSpeedKmh = 128.0), 40, emptyMap(), now)?.kind)
        assertEquals(TipKind.SMOOTH, DriveTips.pick(calm.copy(speedChangePerMin = 30.0), 40, emptyMap(), now)?.kind)
        assertEquals(TipKind.CONSUMPTION, DriveTips.pick(calm.copy(rangeUsedPerKm = 1.5), 40, emptyMap(), now)?.kind)
        val townOnFuel = calm.copy(avgMovingSpeedKmh = 35.0, fuelRangeUsedKm = 6.0, batteryPercent = 60.0)
        assertEquals(TipKind.EV_IN_TOWN, DriveTips.pick(townOnFuel, 40, emptyMap(), now)?.kind)
        // Fuel in town is right when the battery is nearly empty.
        assertNull(DriveTips.pick(townOnFuel.copy(batteryPercent = 15.0), 40, emptyMap(), now))
    }

    @Test
    fun aBreakAfterTwoHoursComesFirst() {
        val tip = DriveTips.pick(calm.copy(avgMovingSpeedKmh = 128.0), 135, emptyMap(), now)!!
        assertEquals(TipKind.BREAK, tip.kind)
        assertTrue(tip.facts.contains("2 h 15 min"))
        // Even with too little data for anything else.
        assertEquals(TipKind.BREAK, DriveTips.pick(DriveReport(), 125, emptyMap(), now)?.kind)
    }

    @Test
    fun neverInTheFirstMinutesNorTooOftenNorTheSameTwice() {
        val fast = calm.copy(avgMovingSpeedKmh = 128.0)
        assertNull(DriveTips.pick(fast, 5, emptyMap(), now))
        // Any tip ten minutes ago: nothing now.
        assertNull(DriveTips.pick(fast, 40, mapOf(TipKind.SMOOTH to now - 10 * min), now))
        // The same kind half an hour ago: not again yet, but another kind may be said.
        assertNull(DriveTips.pick(fast, 40, mapOf(TipKind.SPEED to now - 30 * min), now))
        assertEquals(TipKind.SMOOTH, DriveTips.pick(fast.copy(speedChangePerMin = 30.0), 40, mapOf(TipKind.SPEED to now - 30 * min), now)?.kind)
        // An hour later the same kind is fine again.
        assertEquals(TipKind.SPEED, DriveTips.pick(fast, 40, mapOf(TipKind.SPEED to now - 61 * min), now)?.kind)
    }

    @Test
    fun notEnoughDataNoTip() {
        assertNull(DriveTips.pick(calm.copy(avgMovingSpeedKmh = 140.0, enoughData = false), 40, emptyMap(), now))
        // Jerky but barely moved (a car park): no tip.
        assertNull(DriveTips.pick(calm.copy(speedChangePerMin = 40.0, distanceKm = 0.5), 40, emptyMap(), now))
    }

    @Test
    fun theTipIsOneFriendlySentenceThatAsksNothing() {
        val text = DriveTipPrompt.build(DriveTip(TipKind.SMOOTH, "the speed changed by about 30 km/h every minute"), "French")
        assertTrue(text.contains("ONE sentence"))
        assertTrue(text.contains("do not ask anything"))
        assertTrue(text.contains("30 km/h"))
        assertTrue(text.contains("Speak in French."))
        assertFalse(DriveTipPrompt.build(DriveTip(TipKind.BREAK, "x"), null).contains("Speak in"))
    }
}
