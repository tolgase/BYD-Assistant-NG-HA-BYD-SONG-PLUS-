package com.bydassistantng.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Song PLUS edition: a head unit with no Play Services usually carries a patched YouTube, which installs under its
 * own package name. Every YouTube rule has to apply to it, or "play X on YouTube" opens the app and stops there.
 */
class PatchedYouTubeTest {

    @Test
    fun `the official package is YouTube`() {
        assertTrue(FirstResult.isYouTube("com.google.android.youtube"))
    }

    @Test
    fun `the patched builds are YouTube`() {
        assertTrue(FirstResult.isYouTube("app.revanced.android.youtube"))
        assertTrue(FirstResult.isYouTube("app.rvx.android.youtube"))
    }

    @Test
    fun `a build renamed when it was patched is still YouTube`() {
        assertTrue(FirstResult.isYouTube("com.axel.youtube"))
    }

    @Test
    fun `another app is not YouTube`() {
        assertFalse(FirstResult.isYouTube("com.spotify.music"))
        assertFalse(FirstResult.isYouTube("com.byd.autovoice"))
        // Not a YouTube: the family name has to end the package name, not sit inside it.
        assertFalse(FirstResult.isYouTube("com.example.youtubedownloader"))
    }

    @Test
    fun `a patched build gets the YouTube rules, not Spotify's`() {
        assertTrue(FirstResult.hasRule("app.revanced.android.youtube"))
        assertTrue(FirstResult.needsUiAutomation("app.revanced.android.youtube"))
        assertFalse(FirstResult.isSpotify("app.revanced.android.youtube"))
    }
}
