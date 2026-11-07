package com.bydassistantng.service

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bydassistantng.navigation.NavNotifications
import com.bydassistantng.util.AppLogger

/**
 * The app's notification listener. It exists so that the app may see which media sessions are playing (see
 * [com.bydassistantng.audio.MediaSources]), which Android only allows a notification listener.
 *
 * Song PLUS edition: it also reads ONE kind of notification — the ongoing navigation notification of a maps app (Google
 * Maps, a ReVanced Maps, Waze) — so the driver can ask how far it is and whether the battery and fuel will last. Every
 * other app's notifications are ignored without being looked at; the navigation text is kept in memory only, never
 * stored, and only ever goes into the driver's own question (see [com.bydassistantng.navigation.ReadNavigationTool]).
 */
class MediaAccessService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        rescan()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        NavNotifications.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { take(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && NavNotifications.isNavigationApp(sbn.packageName)) NavNotifications.removed(sbn.key)
    }

    /** Looks at what is showing now (the listener may have connected after navigation started). */
    fun rescan() {
        runCatching { activeNotifications?.forEach { take(it) } }
            .onFailure { AppLogger.logError(TAG, "Could not list the active notifications", it) }
    }

    private fun take(sbn: StatusBarNotification) {
        // The package is checked first: nothing of any other app's notification is read.
        if (!NavNotifications.isNavigationApp(sbn.packageName)) return
        val n = sbn.notification ?: return
        if (!sbn.isOngoing && n.category != Notification.CATEGORY_NAVIGATION) return
        val extras = n.extras ?: return
        val lines = TEXT_KEYS.mapNotNull { key -> extras.getCharSequence(key)?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        NavNotifications.posted(sbn.key, sbn.packageName, lines, SystemClock.elapsedRealtime())
    }

    companion object {
        private const val TAG = "MediaAccessService"
        private val TEXT_KEYS = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT,
        )

        /** The running listener, while Android has it connected. */
        @Volatile var instance: MediaAccessService? = null
            private set
    }
}
