package com.bydassistantng.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ride and the assistant's three hidden manners. */
class RideStyleTest {
    private val min = 60_000L

    // ── The ride ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aRideCountsMinutesOnTheMove() {
        val ride = RideTracker()
        assertEquals(0, ride.minutes(0))
        for (i in 0..40) ride.update(i * min, 80.0)
        assertEquals(40, ride.minutes(40 * min))
    }

    @Test
    fun shortStopsAreStillTheSameRideButABreakStartsANewOne() {
        val ride = RideTracker()
        for (i in 0..20) ride.update(i * min, 60.0)
        // A five-minute red light or queue: same ride.
        for (i in 26..30) ride.update(i * min, 60.0)
        assertEquals(30, ride.minutes(30 * min))
        // Parked for 15 minutes: while parked there is no ride, and driving off starts a new one.
        assertEquals(0, ride.minutes(45 * min))
        ride.update(46 * min, 50.0)
        ride.update(50 * min, 50.0)
        assertEquals(4, ride.minutes(50 * min))
    }

    @Test
    fun standingStillOrNoSpeedIsNotARide() {
        val ride = RideTracker()
        for (i in 0..30) ride.update(i * min, if (i % 2 == 0) 0.0 else null)
        assertEquals(0, ride.minutes(30 * min))
    }

    // ── The manners ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun theModeIsChosenByTheRideNotByTheUser() {
        assertEquals(RideMode.STANDARD, RideStyle.mode(RideContext()))
        assertEquals(RideMode.STANDARD, RideStyle.mode(RideContext(rideMinutes = 20, minutesToGo = 15)))
        assertEquals(RideMode.LONG, RideStyle.mode(RideContext(rideMinutes = 35)))
        // Just set off, but a long way to go.
        assertEquals(RideMode.LONG, RideStyle.mode(RideContext(rideMinutes = 2, minutesToGo = 90)))
    }

    @Test
    fun quickCommandsAreShortAndEndTheConversationOnAShortTrip() {
        val text = RideStyle.instruction(RideContext())
        assertTrue(text.contains("two or three words"))
        assertTrue(text.contains("call end_conversation right after"))
        assertTrue(text.contains("one to three short spoken sentences"))
        assertFalse(text.contains("long ride"))
        // Hidden: the assistant is never told to name its manner.
        assertTrue(text.contains("without ever mentioning it"))
    }

    @Test
    fun aLongRideIsACompanionThatKeepsTalking() {
        val text = RideStyle.instruction(RideContext(rideMinutes = 50, minutesToGo = 70))
        assertTrue(text.contains("This is a long ride"))
        assertTrue(text.contains("about 50 minutes"))
        assertTrue(text.contains("about 70 minutes are still to go"))
        assertTrue(text.contains("travel companion"))
        assertTrue(text.contains("short break"))
        // A quick command on a long ride does not cut the conversation off.
        assertFalse(text.contains("call end_conversation"))
    }

    @Test
    fun passengersAreLookedAfterOnceTheDriverIsServed() {
        val one = RideStyle.instruction(RideContext(passengers = listOf("front passenger seat")))
        assertTrue(one.contains("a passenger (front passenger seat)"))
        assertTrue(one.contains("When the driver's request is done"))
        assertTrue(one.contains("only occasionally"))
        assertTrue(one.contains("The driver's requests always come first"))
        val long = RideStyle.instruction(RideContext(rideMinutes = 40, passengers = listOf("front passenger seat", "rear left")))
        assertTrue(long.contains("2 passengers (front passenger seat, rear left)"))
        assertTrue(long.contains("now and then"))
    }

    @Test
    fun aloneOrUnknown() {
        assertTrue(RideStyle.instruction(RideContext(passengers = emptyList())).contains("The driver is alone"))
        val unknown = RideStyle.instruction(RideContext(passengers = null))
        assertTrue(unknown.contains("You cannot tell who is in the car"))
        assertFalse(unknown.contains("on board"))
    }
}
