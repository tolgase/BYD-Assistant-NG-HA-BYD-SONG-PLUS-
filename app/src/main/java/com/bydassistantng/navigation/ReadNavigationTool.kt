package com.bydassistantng.navigation

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.bydassistantng.R
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.service.MediaAccessService
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Song PLUS edition: "how far is it?", "when do we get there?", "will the battery and fuel last?".
 *
 * The maps app (Google Maps, a ReVanced build, Waze) shows the route in its navigation notification; this reads that
 * text — there is no other way for an app to learn another app's route. The model gets the text as shown plus what could
 * be read out of it, and is told which part is likely which, rather than this code pretending to know.
 */
object ReadNavigationTool {
    const val FUNCTION_NAME = "read_navigation"
    private const val TAG = "ReadNavigationTool"

    // The notification is updated every few seconds while navigating; older than this, the route has probably ended.
    private const val STALE_MS = 10 * 60_000L

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Read the route the maps app (Google Maps, ReVanced Maps, Waze) is navigating right now, from its " +
            "live navigation notification: next turn, distance, time left, arrival time. Call it for \"how far is it\", " +
            "\"how long left\", \"when do we arrive\", and — together with read_car (what=status) — for \"do I have enough " +
            "battery / fuel / range to get there\": compare the distance left with electric_range_km plus fuel_range_km and " +
            "say how much margin there is. When two distances are shown, the smaller is usually the next turn and the larger " +
            "the distance left; say so if it is unclear. If nothing is being navigated, say so and offer to start navigation.",
    )

    suspend fun handle(context: Context, onDispatching: suspend (String) -> Unit): JsonObject {
        onDispatching(AppLanguage.string(context, R.string.banner_navigation_read))
        if (!listenerEnabled(context)) {
            return buildJsonObject {
                put("status", "error")
                put(
                    "error",
                    "Notification access is off for this app, so the route cannot be read. The user can turn it on in " +
                        "Android Settings > Apps > Special access > Notification access.",
                )
            }
        }
        MediaAccessService.instance?.rescan()
        val snap = NavNotifications.latest
        val age = snap?.let { SystemClock.elapsedRealtime() - it.atMs }
        if (snap == null || age == null || age > STALE_MS) {
            AppLogger.log(TAG, "read_navigation -> no active navigation")
            return buildJsonObject {
                put("status", "ok")
                put("active", false)
                put("note", "No maps app is navigating right now.")
            }
        }
        val info = NavText.parse(snap.lines)
        AppLogger.log(TAG, "read_navigation -> ${snap.lines.size} line(s) from ${snap.app}: $info")
        return buildJsonObject {
            put("status", "ok")
            put("active", true)
            put("app", snap.app)
            put("shown", JsonArray(snap.lines.map { JsonPrimitive(it) }))
            if (info.distancesKm.isNotEmpty()) put("distances_km", JsonArray(info.distancesKm.map { JsonPrimitive(it) }))
            info.minutesLeft?.let { put("minutes_left", it) }
            info.eta?.let { put("arrival", it) }
            put("seconds_since_update", age / 1000)
        }
    }

    private fun listenerEnabled(context: Context): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true
    }.getOrDefault(false)
}
