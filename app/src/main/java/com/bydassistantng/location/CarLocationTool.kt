package com.bydassistantng.location

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Song PLUS edition: "what's the weather?", "is it going to rain?", "a pharmacy near here" — the model needs to know
 * where the car is to look those up. Never touches the car, so it is always offered.
 */
object CarLocationTool {
    const val FUNCTION_NAME = "car_location"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Where the car is right now: the town and country, and coordinates. Call it before looking up the " +
            "weather, the forecast, or anything \"near here\" or \"around me\", then use Google Search for that place. " +
            "Answer weather in one or two spoken sentences: the sky, the temperature, and rain if any.",
    )

    suspend fun handle(context: Context, onDispatching: suspend (String) -> Unit): JsonObject {
        onDispatching(AppLanguage.string(context, R.string.banner_location))
        if (!CarLocation.permitted(context)) {
            return buildJsonObject {
                put("status", "error")
                put(
                    "error",
                    "Location access is off. The user can allow it in the app: Settings > Assistant > Location for the weather. " +
                        "Until then, ask which town to look up.",
                )
            }
        }
        val pos = CarLocation.lastKnown(context)
            ?: return buildJsonObject {
                put("status", "error")
                put("error", "The car does not know where it is yet (no position since it woke). Ask which town to look up.")
            }
        val place = CarLocation.placeName(context)
        AppLogger.log("CarLocationTool", "car_location -> ${place ?: "unnamed"} (age ${pos.ageMinutes} min)")
        return buildJsonObject {
            put("status", "ok")
            place?.let { put("place", it) }
            put("latitude", Math.round(pos.lat * 1000.0) / 1000.0)
            put("longitude", Math.round(pos.lon * 1000.0) / 1000.0)
            pos.ageMinutes?.let { put("position_age_minutes", it) }
        }
    }
}
