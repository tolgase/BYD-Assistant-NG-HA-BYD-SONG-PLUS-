package com.bydassistantng.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car learning the driver's own words from Gemini, for offline use. */
class LearnedCommandsTest {
    private val t0 = 1_000_000L

    @Test
    fun aLearnedPhraseMatchesItselfAndSmallRecognitionSlips() {
        val list = LearnedCommands.learn(emptyList(), "حلّلي البلار متاعي", "window.driver" to "open", null, t0, replace = true)
        assertNotNull(LearnedCommands.match("حلّلي البلار متاعي", list))
        // The offline recognizer writes one letter differently.
        assertNotNull(LearnedCommands.match("حللي البلار متاعى", list))
        // Same words, another order.
        assertNotNull(LearnedCommands.match("البلار متاعي حللي", list))
    }

    @Test
    fun aDifferentPhraseDoesNotMatch() {
        val list = LearnedCommands.learn(emptyList(), "حلّلي البلار متاعي", "window.driver" to "open", null, t0, replace = true)
        assertNull(LearnedCommands.match("سكر الكوفر", list))
        assertNull(LearnedCommands.match("ok", list))
        assertNull(LearnedCommands.match("", list))
    }

    @Test
    fun severalCommandsForOnePhraseAreKeptTogether() {
        var list = LearnedCommands.learn(emptyList(), "taffi kol chay", "ac.power" to "off", null, t0, replace = true)
        list = LearnedCommands.learn(list, "taffi kol chay", "cabin.light" to "off", null, t0 + 1, replace = false)
        val plan = LearnedCommands.plan(LearnedCommands.match("taffi kol chay", list)!!)!!
        assertEquals(listOf("ac.power" to "off", "cabin.light" to "off"), plan.actions)
    }

    @Test
    fun aNewConversationReplacesWhatThePhraseMeantBefore() {
        var list = LearnedCommands.learn(emptyList(), "fet7 chwaya", "window.driver" to "open", null, t0, replace = true)
        list = LearnedCommands.learn(list, "fet7 chwaya", "sunroof.tilt" to "tilt", null, t0 + 1, replace = true)
        assertEquals(listOf("sunroof.tilt" to "tilt"), list.single().actions)
    }

    @Test
    fun aSceneIsLearnedAndRunAsTheScene() {
        val list = LearnedCommands.learn(emptyList(), "barcha s5ana", null, "cool_down", t0, replace = true)
        assertEquals("cool_down", LearnedCommands.plan(list.single())!!.scene!!.id)
    }

    @Test
    fun doorsAndLocksAreNeverLearned() {
        val list = LearnedCommands.learn(emptyList(), "7ell el bibane", "door.unlock" to "unlock", null, t0, replace = true)
        assertTrue(list.isEmpty())
        assertTrue(LearnedCommands.learn(emptyList(), "sakker", "central.lock" to "lock", null, t0, replace = true).isEmpty())
    }

    @Test
    fun nothingWorthKeepingReturnsTheSameList() {
        val before = listOf(LearnedCommand("existing phrase", listOf("ac.power" to "on")))
        assertSame(before, LearnedCommands.learn(before, "ab", "ac.power" to "on", null, t0, replace = true))
        assertSame(before, LearnedCommands.learn(before, "a phrase", null, null, t0, replace = true))
        assertSame(before, LearnedCommands.learn(before, "a phrase", "ac.power" to "on\tbad", null, t0, replace = true))
    }

    @Test
    fun theListIsCappedNewestFirst() {
        var list = emptyList<LearnedCommand>()
        for (i in 0 until LearnedCommands.MAX_ENTRIES + 20) {
            list = LearnedCommands.learn(list, "phrase number $i", "ac.fan" to "3", null, t0 + i, replace = true)
        }
        assertEquals(LearnedCommands.MAX_ENTRIES, list.size)
        assertEquals("phrase number ${LearnedCommands.MAX_ENTRIES + 19}", list.first().phrase)
    }

    @Test
    fun storageRoundTripsAndSurvivesDamage() {
        var list = LearnedCommands.learn(emptyList(), "سكّر كلشي يا خويا", null, "close_all", t0, replace = true)
        list = LearnedCommands.learn(list, "clim a fond", "ac.fan" to "7", null, t0 + 5, replace = true)
        list = LearnedCommands.markUsed(list, "clim a fond")
        val text = LearnedCommands.encode(list)
        assertEquals(list, LearnedCommands.decode(text))
        val damaged = text + "\nnot\ta\tvalid\tline" + "\n\t\t\t\t" + "\ngarbage"
        assertEquals(list, LearnedCommands.decode(damaged))
        assertTrue(LearnedCommands.decode(null).isEmpty())
    }

    @Test
    fun usesAreCounted() {
        var list = LearnedCommands.learn(emptyList(), "clim a fond", "ac.fan" to "7", null, t0, replace = true)
        list = LearnedCommands.markUsed(LearnedCommands.markUsed(list, "clim a fond"), "clim a fond")
        assertEquals(2, list.single().uses)
    }
}
