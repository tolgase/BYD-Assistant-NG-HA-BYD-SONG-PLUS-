package com.bydassistantng.greeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The welcome summary: what the greeting says about the car, and the one comfort offer it makes. */
class WelcomeSummaryTest {
    private val full = GreetingPrompt.Summary(
        batteryPercent = 76, electricRangeKm = 98, fuelRangeKm = 540, insideCelsius = 34, outsideCelsius = 29, place = "Monastir, Tunisia",
    )

    private fun welcome(s: GreetingPrompt.Summary, language: String? = null) =
        GreetingPrompt.build(GreetingPrompt.PartOfDay.AFTERNOON, GreetingPrompt.TimeOfDay(14, 5), null, language, false, s)

    @Test
    fun aHotCabinIsOfferedCoolingAsAQuestion() {
        val text = welcome(full)
        assertTrue(text.contains("good afternoon"))
        assertTrue(text.contains("14:05"))
        assertTrue(text.contains("76 percent"))
        assertTrue(text.contains("98 km of electric range"))
        assertTrue(text.contains("540 km on fuel"))
        assertTrue(text.contains("638 km in all"))
        assertTrue(text.contains("34 degrees Celsius inside"))
        assertTrue(text.contains("29 outside"))
        assertTrue(text.contains("weather where the car is (Monastir, Tunisia)"))
        assertTrue(text.contains("yes/no question: whether to cool the cabin down now"))
        assertTrue(text.contains("run the cool_down scene"))
    }

    @Test
    fun theOfferFollowsTheReadings() {
        assertEquals("cool_down", GreetingPrompt.offerFor(full)?.scene)
        assertEquals("warm_up", GreetingPrompt.offerFor(full.copy(insideCelsius = 12))?.scene)
        assertNull(GreetingPrompt.offerFor(full.copy(insideCelsius = 23)))
        // No cabin reading: only an extreme outside temperature says the cabin needs it.
        assertEquals("cool_down", GreetingPrompt.offerFor(full.copy(insideCelsius = null, outsideCelsius = 36))?.scene)
        assertEquals("warm_up", GreetingPrompt.offerFor(full.copy(insideCelsius = null, outsideCelsius = 5))?.scene)
        assertNull(GreetingPrompt.offerFor(full.copy(insideCelsius = null, outsideCelsius = 25)))
        assertNull(GreetingPrompt.offerFor(GreetingPrompt.Summary()))
    }

    @Test
    fun aComfortableCabinAsksNothing() {
        val text = welcome(full.copy(insideCelsius = 22))
        assertTrue(text.contains("ask nothing"))
        assertFalse(text.contains("yes/no question"))
    }

    @Test
    fun lowEnergyIsSaidPlainly() {
        val low = GreetingPrompt.lowOn(full.copy(batteryPercent = 15, fuelRangeKm = 60))
        assertEquals(2, low.size)
        assertTrue(welcome(full.copy(electricRangeKm = 20)).contains("the battery is low"))
        assertTrue(GreetingPrompt.lowOn(full).isEmpty())
    }

    @Test
    fun missingReadingsAndPlaceAreLeftOut() {
        val text = welcome(GreetingPrompt.Summary(insideCelsius = 22))
        assertFalse(text.contains("percent"))
        assertFalse(text.contains("km"))
        assertTrue(text.contains("Leave the weather out"))
        assertFalse(text.contains("Google Search"))
    }

    @Test
    fun theOutsideReadingComesFromTheClusterWhenTheStatusHasNone() {
        val text = GreetingPrompt.build(
            GreetingPrompt.PartOfDay.MORNING, GreetingPrompt.TimeOfDay(8, 0), 17, null, false, GreetingPrompt.Summary(insideCelsius = 20),
        )
        assertTrue(text.contains("17 outside"))
    }

    @Test
    fun passengersAreWelcomedToo() {
        val text = welcome(full.copy(passengers = listOf("front passenger seat", "rear left")))
        assertTrue(text.contains("are 2 passengers too (front passenger seat, rear left)"))
        assertTrue(text.contains("welcome them warmly as well"))
        assertFalse(welcome(full.copy(passengers = emptyList())).contains("passenger"))
        assertFalse(welcome(full).contains("passenger"))
    }

    @Test
    fun withoutASummaryTheGreetingIsTheClassicOne() {
        val classic = GreetingPrompt.build(GreetingPrompt.PartOfDay.MORNING, GreetingPrompt.TimeOfDay(8, 5), 24, null, false)
        assertTrue(classic.contains("do not ask them anything"))
        assertFalse(classic.contains("summary"))
        assertTrue(welcome(full, language = "Arabic").contains("Speak in Arabic."))
    }
}
