package com.bydassistantng.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline phrase book: what the driver says, in English, French and Tunisian Derja, and what it becomes. */
class QuickParserTest {
    private val allWindows = listOf("window.driver", "window.passenger", "window.rearLeft", "window.rearRight")

    private fun actions(phrase: String) = QuickParser.parse(phrase)?.allActions
    private fun scene(phrase: String) = QuickParser.parse(phrase)?.scene?.id

    // ── Commands that worked before must keep working ──────────────────────────────────────────────

    @Test
    fun englishCommands() {
        assertEquals(listOf("window.driver" to "open"), actions("open the driver window"))
        assertEquals(allWindows.map { it to "close" }, actions("close the windows"))
        assertEquals(listOf("ac.temperature" to "22"), actions("set the temperature to 22"))
        assertEquals(listOf("ac.power" to "on"), actions("turn on the AC"))
        assertEquals(listOf("ac.power" to "off"), actions("turn off the AC"))
        assertEquals(listOf("ac.fan" to "3"), actions("fan 3"))
        assertEquals(listOf("seat.driver.heating" to "high"), actions("heat my seat"))
        assertEquals(listOf("trunk" to "open"), actions("open the trunk"))
        assertEquals(listOf("sunroof" to "open"), actions("open the sunroof"))
        assertEquals(listOf("sunroof.tilt" to "tilt"), actions("open the sunroof a bit"))
    }

    @Test
    fun frenchCommands() {
        assertEquals(allWindows.map { it to "close" }, actions("ferme la fenêtre"))
        assertEquals(listOf("ac.temperature" to "22"), actions("clim vingt-deux"))
        assertEquals(listOf("ambient.color" to "blue"), actions("ambiance bleue"))
        assertEquals("cool_down", scene("j'ai chaud"))
        assertEquals("warm_up", scene("j'ai froid"))
    }

    @Test
    fun tunisianCommands() {
        assertEquals(allWindows.map { it to "close" }, actions("سكّر الشبابك"))
        assertEquals(listOf("ac.temperature" to "22"), actions("شعّل الكليم اثنين و عشرين"))
        assertEquals(listOf("trunk" to "open"), actions("حل الكوفر"))
        assertEquals(listOf("ac.power" to "off"), actions("طفي الكليم"))
        assertEquals("close_all", scene("سكر كل شي"))
    }

    @Test
    fun scenesStillRecognised() {
        assertEquals("close_all", scene("close everything"))
        assertEquals("cool_down", scene("I'm hot"))
        assertEquals("climate_off", scene("turn everything off"))
        assertEquals("air_out", scene("air out the car"))
    }

    @Test
    fun unrelatedSpeechIsNotACarCommand() {
        assertNull(QuickParser.parse("what is the weather tomorrow"))
        assertNull(QuickParser.parse(""))
    }

    // ── Goodbye ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun goodbyeTidiesTheCabinInEveryLanguage() {
        for (p in listOf("goodbye", "bye bye", "I'm leaving", "au revoir", "je pars", "بسلامة", "في امان الله", "باي باي")) {
            assertEquals(p, "goodbye", scene(p))
        }
    }

    @Test
    fun goodbyeClosesGlassAndClimateButNeverTouchesPowerOrDrive() {
        val actions = Scenes.byId("goodbye")!!.actions
        assertTrue(actions.containsAll(allWindows.map { it to "close" }))
        assertTrue(("sunroof" to "close") in actions)
        assertTrue(("ac.power" to "off") in actions)
        // Seat ids say "driver"; what must never appear is a door, a lock, a drive mode, or any power other than the A/C's.
        assertFalse(
            actions.any { (id, _) ->
                id.contains("door") || id.contains("lock") || id.startsWith("drive") || (id.contains("power") && id != "ac.power")
            },
        )
    }

    @Test
    fun aStrongerAcIsNotAGoodbye() {
        // "à plus" is also "goodbye" in French, which is why it is not in the list: this must not close the glass.
        assertFalse(scene("mets la clim a plus fort") == "goodbye")
    }

    // ── Again ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun repeatIsRecognised() {
        for (p in listOf("again", "do it again", "encore", "عاود", "مرة اخرى")) assertTrue(p, QuickParser.isRepeat(p))
    }

    @Test
    fun realCommandsAreNotRepeats() {
        // "زيد الصوت" is volume up and "remets la clim" switches the A/C back on: neither may replay the last command.
        assertFalse(QuickParser.isRepeat("زيد الصوت"))
        assertFalse(QuickParser.isRepeat("remets la clim"))
        assertFalse(QuickParser.isRepeat("open the window"))
        assertFalse(QuickParser.isRepeat(""))
    }
}
