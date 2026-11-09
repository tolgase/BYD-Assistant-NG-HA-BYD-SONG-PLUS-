package com.bydassistantng.vehicle

import android.content.Context
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Song PLUS edition: keeps the car's energy readings over time on the head unit (see [ConsumptionLog]) and hands the
 * earlier ones to the model with each status reading, so "how is my consumption?" can be answered against the past.
 * Stored on the car only; nothing leaves it except inside the driver's own question.
 */
object ConsumptionHistory {
    private const val TAG = "ConsumptionHistory"
    private const val PREFS = "consumption_history"
    private const val KEY = "readings"

    // Of the status reading, what counts as an energy reading besides the trip computer.
    private val TOP_LEVEL = listOf("battery_percent", "electric_range_km", "fuel_range_km")

    /** [note] (the car's status as JSON) with the earlier readings added, after this one has been recorded. */
    fun attach(context: Context, note: String?): String? {
        if (note == null) return null
        return runCatching {
            val car = Json.parseToJsonElement(note).jsonObject
            val values = buildMap {
                TOP_LEVEL.forEach { k -> (car[k] as? JsonPrimitive)?.doubleOrNull?.let { put(k, it) } }
                (car["trip_computer"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { put(k, it) } }
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val log = ConsumptionLog.decode(prefs.getString(KEY, null))
            val now = System.currentTimeMillis()
            val earlier = ConsumptionLog.previous(log, now)
            val updated = ConsumptionLog.record(log, EnergyReading(now, values))
            if (updated !== log) prefs.edit().putString(KEY, ConsumptionLog.encode(updated)).apply()
            if (earlier.isEmpty()) {
                note
            } else {
                JsonObject(
                    car + ("previous_readings" to JsonArray(
                        earlier.map { (hoursAgo, v) ->
                            buildJsonObject {
                                put("hours_ago", hoursAgo)
                                v.forEach { (k, value) -> put(k, value) }
                            }
                        },
                    )),
                ).toString()
            }
        }.getOrElse {
            AppLogger.logError(TAG, "Could not add the consumption history", it)
            note
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
