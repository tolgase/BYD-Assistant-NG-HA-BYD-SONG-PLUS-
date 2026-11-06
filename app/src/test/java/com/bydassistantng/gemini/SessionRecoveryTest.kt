package com.bydassistantng.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryTest {

    @Test
    fun `a dropped socket on a working network is recovered`() {
        val recovery = SessionRecovery()
        assertTrue(recovery.allow(FailureKind.NO_NETWORK, networkUsable = true, nowMs = 0))
    }

    @Test
    fun `a rejected key is never recovered`() {
        val recovery = SessionRecovery()
        assertFalse(recovery.allow(FailureKind.API_KEY_REJECTED, networkUsable = true, nowMs = 0))
        assertEquals(0, recovery.spent)
    }

    @Test
    fun `an exhausted quota and a refused request are not accidents`() {
        val recovery = SessionRecovery()
        assertFalse(recovery.allow(FailureKind.BUSY, networkUsable = true, nowMs = 0))
        assertFalse(recovery.allow(FailureKind.SERVER_REJECTED, networkUsable = true, nowMs = 0))
    }

    @Test
    fun `a car with no usable network fails straight away`() {
        val recovery = SessionRecovery()
        assertFalse(recovery.allow(FailureKind.NO_NETWORK, networkUsable = false, nowMs = 0))
        assertEquals(0, recovery.spent)
    }

    @Test
    fun `the budget stops a loop`() {
        val recovery = SessionRecovery(maxAttempts = 2, windowMs = 60_000)
        assertTrue(recovery.allow(FailureKind.OTHER, true, 0))
        assertTrue(recovery.allow(FailureKind.OTHER, true, 1_000))
        assertFalse(recovery.allow(FailureKind.OTHER, true, 2_000))
    }

    @Test
    fun `the budget comes back once the window has passed`() {
        val recovery = SessionRecovery(maxAttempts = 2, windowMs = 60_000)
        assertTrue(recovery.allow(FailureKind.OTHER, true, 0))
        assertTrue(recovery.allow(FailureKind.OTHER, true, 1_000))
        assertFalse(recovery.allow(FailureKind.OTHER, true, 30_000))
        // Both earlier attempts are now older than the window.
        assertTrue(recovery.allow(FailureKind.OTHER, true, 70_000))
    }

    @Test
    fun `a new conversation starts with the whole budget`() {
        val recovery = SessionRecovery(maxAttempts = 1, windowMs = 60_000)
        assertTrue(recovery.allow(FailureKind.OTHER, true, 0))
        assertFalse(recovery.allow(FailureKind.OTHER, true, 100))
        recovery.reset()
        assertTrue(recovery.allow(FailureKind.OTHER, true, 200))
    }
}
