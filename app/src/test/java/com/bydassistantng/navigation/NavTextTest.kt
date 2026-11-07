package com.bydassistantng.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a maps app's navigation notification. */
class NavTextTest {

    @Test
    fun englishTripSummary() {
        val info = NavText.parse(listOf("300 m", "Turn right onto Avenue Habib Bourguiba", "12 min · 8.4 km · ETA 14:32"))
        assertEquals(listOf(0.3, 8.4), info.distancesKm)
        assertEquals(12, info.minutesLeft)
        assertEquals("14:32", info.eta)
    }

    @Test
    fun frenchWithDecimalCommaHoursAndFrenchClock() {
        val info = NavText.parse(listOf("Tournez à gauche", "1 h 5 min (85,6 km)", "Arrivée à 15h40"))
        assertEquals(listOf(85.6), info.distancesKm)
        assertEquals(65, info.minutesLeft)
        assertEquals("15h40", info.eta)
    }

    @Test
    fun arabicWithArabicDigits() {
        val info = NavText.parse(listOf("٢٠ دقيقة", "١٢٫٥ كم"))
        assertEquals(listOf(12.5), info.distancesKm)
        assertEquals(20, info.minutesLeft)
    }

    @Test
    fun milesAndTwelveHourClock() {
        val info = NavText.parse(listOf("2 mi", "Arrive 2:45 PM"))
        assertEquals(3.219, info.distancesKm.single(), 0.001)
        assertEquals("2:45 PM", info.eta)
    }

    @Test
    fun minutesAreNotMetresAndNothingIsInvented() {
        val info = NavText.parse(listOf("5 min"))
        assertTrue(info.distancesKm.isEmpty())
        assertEquals(5, info.minutesLeft)
        val none = NavText.parse(listOf("Navigation", "Tap to return"))
        assertTrue(none.distancesKm.isEmpty())
        assertNull(none.minutesLeft)
        assertNull(none.eta)
    }

    @Test
    fun onlyMapsAppsAreRead() {
        assertTrue(NavNotifications.isNavigationApp("com.google.android.apps.maps"))
        assertTrue(NavNotifications.isNavigationApp("app.revanced.android.apps.maps"))
        assertTrue(NavNotifications.isNavigationApp("com.waze"))
        assertFalse(NavNotifications.isNavigationApp("com.whatsapp"))
        assertFalse(NavNotifications.isNavigationApp("com.android.systemui"))
        assertFalse(NavNotifications.isNavigationApp("com.google.android.gm"))
    }

    @Test
    fun aNotificationFromAnotherAppIsIgnoredAndRemovalEndsTheRoute() {
        NavNotifications.clear()
        NavNotifications.posted("k1", "com.whatsapp", listOf("Hi"), 1L)
        assertNull(NavNotifications.latest)
        NavNotifications.posted("k2", "com.google.android.apps.maps", listOf("8 km"), 2L)
        assertEquals("com.google.android.apps.maps", NavNotifications.latest!!.app)
        NavNotifications.removed("other")
        assertEquals("8 km", NavNotifications.latest!!.lines.single())
        NavNotifications.removed("k2")
        assertNull(NavNotifications.latest)
    }
}
