package com.bydassistantng.drive

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Song PLUS edition: "how am I driving?", "how was my consumption on this trip?", "how can I save fuel?". Reports the last
 * 15 or 30 minutes as [DriveMonitor] saw them, and tells the model how to coach from it.
 */
object DriveReportTool {
    const val FUNCTION_NAME = "drive_report"
    private val WINDOWS = listOf(15, 30)

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "How the car has been driven over the last 15 or 30 minutes — distance, speeds, stops, how jerky the " +
            "speed was, battery and range used, A/C use — measured by the car. Call it for \"how am I driving\", \"how is my " +
            "consumption on this drive\", \"how can I drive more economically\" or \"am I using too much fuel\".",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "minutes" to GeminiSchema(type = "INTEGER", description = "15 or 30. Default 15.", enum = null),
            ),
        ),
    )

    // How to read the figures and what to advise, for a plug-in hybrid (DM-i). Said once here rather than in the prompt.
    private const val COACHING =
        "Coach from these figures only; never invent one. range_used_per_km near 1.0 means the drive used what the car " +
            "predicted; above about 1.2 it used noticeably more, below 0.9 less. speed_change_per_min above about 15 means " +
            "stop-and-go or hard acceleration and braking. stopped_share is the share of time standing still. Give 2 or 3 " +
            "concrete tips that fit THESE numbers, best first, e.g.: jerky speed -> accelerate gently and anticipate stops so " +
            "regenerative braking recovers energy; town driving with battery left -> stay in EV mode, the engine is least " +
            "efficient in stop-and-go; long high speed (above about 110 km/h) -> 90 to 110 km/h saves a lot of fuel; battery " +
            "falling fast at highway speed -> HEV mode keeps electric range for town; A/C on with a big inside/outside " +
            "difference -> a degree or two warmer, or recirculation; battery low -> charge at home, it is the cheapest energy. " +
            "Say first, in one sentence, how the drive went. Spoken, short, encouraging. If enough_data is false, say the " +
            "car has only just started watching and to ask again in a few minutes."

    suspend fun handle(context: Context, call: GeminiFunctionCall, onDispatching: suspend (String) -> Unit): JsonObject {
        onDispatching(AppLanguage.string(context, R.string.banner_drive_report))
        // The model may send 30, "30" or 30.0.
        val asked = (call.args["minutes"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 15
        val minutes = WINDOWS.minByOrNull { Math.abs(it - asked) } ?: 15
        val r = DriveAnalysis.analyse(DriveMonitor.log.snapshot(), minutes, System.currentTimeMillis())
        AppLogger.log("DriveReportTool", "drive_report($minutes) -> $r")
        return buildJsonObject {
            put("status", "ok")
            put("window_minutes", r.windowMinutes)
            put("covered_minutes", r.coveredMinutes)
            put("enough_data", r.enoughData)
            r.distanceKm?.let { put("distance_km", it) }
            r.distanceFrom?.let { put("distance_measured_by", it) }
            r.avgMovingSpeedKmh?.let { put("average_moving_speed_kmh", it) }
            r.maxSpeedKmh?.let { put("max_speed_kmh", it) }
            r.stoppedShare?.let { put("stopped_share", it) }
            r.stops?.let { put("stops", it) }
            r.speedChangePerMin?.let { put("speed_change_per_min", it) }
            r.batteryUsedPercent?.let { put("battery_used_percent", it) }
            r.electricRangeUsedKm?.let { put("electric_range_used_km", it) }
            r.fuelRangeUsedKm?.let { put("fuel_range_used_km", it) }
            r.rangeUsedPerKm?.let { put("range_used_per_km", it) }
            r.batteryPercentPer10Km?.let { put("battery_percent_per_10_km", it) }
            r.acOnShare?.let { put("ac_on_share", it) }
            r.insideCelsius?.let { put("inside_c", it) }
            r.outsideCelsius?.let { put("outside_c", it) }
            put("note", COACHING)
        }
    }
}
