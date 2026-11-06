package com.bydassistantng.vehicle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Song PLUS edition: one read of the car's current state for `read_car what=status` — windows, roof, doors, A/C, seats,
 * battery and range — so the model can answer "is anything open?" or "how much range do I have?" from facts.
 * The feature ids are the Song PLUS 2025 (DiLink 5.0) table's; a value the car does not report is left out.
 */
object CarStatus {
    private const val AC = 1000
    private const val BODYWORK = 1001
    private const val SETTING = 1023

    private val windows = listOf("driver" to 947912728, "passenger" to 1267728400, "rear_left" to 947912736, "rear_right" to 947912752)
    private val doors = listOf(
        "driver" to 692060176, "passenger" to 692060177, "rear_left" to 692060178, "rear_right" to 692060179, "tailgate" to 692060181,
    )
    private val seats = listOf(
        "driver_heating" to 1335885835, "driver_ventilation" to 1335885832,
        "passenger_heating" to 1335885843, "passenger_ventilation" to 1335885840,
    )
    private val seatLevel = mapOf(1 to "off", 2 to "low", 3 to "high")

    /** [read] is (device type, feature id) -> value or null; [statistic] reads battery % and ranges by method name. */
    fun encode(read: (Int, Int) -> Int?, statistic: (String) -> Number?): String = buildJsonObject {
        put("windows_open_percent", buildJsonObject { windows.forEach { (k, id) -> read(BODYWORK, id)?.let { put(k, it) } } })
        read(BODYWORK, 1101004808)?.let { put("sunroof_open_percent", it) }
        read(BODYWORK, 1101004816)?.let { put("sunshade_open_percent", it) }
        put("doors_open", buildJsonObject { doors.forEach { (k, id) -> read(BODYWORK, id)?.let { put(k, it != 0) } } })
        put(
            "ac",
            buildJsonObject {
                read(AC, 1077936144)?.let { put("on", it == 1) }
                read(AC, 1077936168)?.let { put("set_temperature_c", it) }
                read(AC, 1077936156)?.let { put("fan", it) }
                read(AC, 1031798832)?.takeIf { it in 1..80 }?.let { put("inside_temperature_c", it) }
            },
        )
        put("seats", buildJsonObject { seats.forEach { (k, id) -> read(SETTING, id)?.let { v -> put(k, seatLevel[v] ?: v.toString()) } } })
        read(SETTING, 1116733454)?.let { put("steering_wheel_heating", it == 2) }
        statistic("getElecPercentageValue")?.toDouble()?.takeIf { it in 0.0..100.0 }?.let { put("battery_percent", it.toInt()) }
        statistic("getElecDrivingRangeValue")?.toDouble()?.takeIf { it in 0.0..2000.0 }?.let { put("electric_range_km", it.toInt()) }
        statistic("getFuelDrivingRangeValue")?.toDouble()?.takeIf { it in 0.0..3000.0 }?.let { put("fuel_range_km", it.toInt()) }
    }.toString()

    fun report(note: String): JsonObject? = runCatching {
        val facts = Json.parseToJsonElement(note).jsonObject
        buildJsonObject {
            put("status", "ok")
            put("car", facts)
            put("note", "Live readings from the car. Window/sunroof values are percent open (0 = closed). Answer briefly.")
        }
    }.getOrNull()
}
