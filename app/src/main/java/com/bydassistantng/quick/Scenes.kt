package com.bydassistantng.quick

/**
 * Song PLUS edition: named groups of car settings ("I'm hot" → cool the cabin). Offered to Gemini as `run_scene`
 * and recognised offline by [QuickParser]. Each action is a registry command id and a value word the registry
 * resolves (an option name or a number), so a scene can only ever do what single commands can.
 */
data class Scene(val id: String, val label: String, val description: String, val actions: List<Pair<String, String>>)

object Scenes {
    private val allWindows = listOf("window.driver", "window.passenger", "window.rearLeft", "window.rearRight")

    val all: List<Scene> = listOf(
        Scene(
            "cool_down", "Cool down", "the user is hot: A/C on at 20 °C, strong fan, recirculation, driver seat ventilation high",
            listOf(
                "ac.power" to "on", "ac.compressor" to "on", "ac.temperature" to "20", "ac.fan" to "5",
                "ac.airSource" to "inside", "seat.driver.ventilation" to "high",
            ),
        ),
        Scene(
            "warm_up", "Warm up", "the user is cold: A/C on at 26 °C, driver seat heating high, steering wheel heating on",
            listOf(
                "ac.power" to "on", "ac.temperature" to "26", "ac.fan" to "4",
                "seat.driver.heating" to "high", "steeringWheel.heating" to "on",
            ),
        ),
        Scene(
            "defrost", "Defrost", "clear fogged or frosted glass: front and rear defrost on",
            listOf("ac.frontDefrost" to "on", "ac.rearDefrost" to "on"),
        ),
        Scene(
            "close_all", "Close everything", "close all four windows and the sunroof",
            allWindows.map { it to "close" } + ("sunroof" to "close"),
        ),
        Scene(
            "air_out", "Air out", "let air in: all four windows to the vent position",
            allWindows.map { it to "breath" },
        ),
        Scene(
            "climate_off", "Climate off", "switch off the A/C and every seat and steering wheel heating or ventilation",
            listOf(
                "ac.power" to "off", "seat.driver.heating" to "off", "seat.driver.ventilation" to "off",
                "seat.passenger.heating" to "off", "seat.passenger.ventilation" to "off", "steeringWheel.heating" to "off",
            ),
        ),
    )

    fun byId(id: String?): Scene? = all.find { it.id.equals(id?.trim(), ignoreCase = true) }
}
