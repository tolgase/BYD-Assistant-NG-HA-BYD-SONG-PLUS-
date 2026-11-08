package com.bydassistantng.vehicle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Song PLUS edition: one read of the car's current state for `read_car what=status` — windows, roof, doors, the cabin
 * and outside air, the climate system, the seats, the battery and the ranges — so the model can answer "is anything
 * open?" or "how much range do I have?" from facts rather than from a guess.
 *
 * Every state is asked for **by the car's own name for it**, with a number that was verified on a Song PLUS as the
 * fallback. That is how the rest of the app works, and it matters here for the same reason: a number that belongs to
 * another trim's firmware reads nothing, and a reading that is absent is indistinguishable from a car that did not
 * answer — so a wrong number shows up as the assistant saying it does not know the temperature, with nothing in the
 * log to say why. The name is checked against the table the car published; where the car does not use that name, the
 * verified number is tried anyway, so a car with no table at all behaves exactly as before.
 */
object CarStatus {
    private const val AC = 1000
    private const val BODYWORK = 1001
    private const val INSTRUMENT = 1007
    private const val SETTING = 1023

    /** One state: what to call it in the answer, the car's name for it, and the number verified on a Song PLUS. */
    private data class State(val key: String, val name: String, val verified: Int)

    private val windows = listOf(
        State("driver", "Bodywork.BODYWORK_WINDOW_LEFT_FRONT_PERCENT", 947912728),
        State("passenger", "Bodywork.BODYWORK_WINDOW_RIGHT_FRONT_PERCENT", 1267728400),
        State("rear_left", "Bodywork.BODYWORK_WINDOW_LEFT_REAR_PERCENT", 947912736),
        State("rear_right", "Bodywork.BODYWORK_WINDOW_RIGHT_REAR_PERCENT", 947912752),
    )
    private val doors = listOf(
        State("driver", "Bodywork.BODYWORK_LF_DOOR_STATUS", 692060176),
        State("passenger", "Bodywork.BODYWORK_RF_DOOR_STATUS", 692060177),
        State("rear_left", "Bodywork.BODYWORK_LR_DOOR_STATUS", 692060178),
        State("rear_right", "Bodywork.BODYWORK_RR_DOOR_STATUS", 692060179),
        State("tailgate", "Bodywork.BODYWORK_BACK_DOOR_STATUS", 692060181),
    )
    private val seats = listOf(
        State("driver_heating", "Setting.SET_MAIN_DRIVING_SEAT_HEAT_STATE", 1335885835),
        State("driver_ventilation", "Setting.SET_MAIN_DRIVING_SEAT_VENTILATION_STATE", 1335885832),
        State("passenger_heating", "Setting.SET_CO_DRIVING_SEAT_HEAT_STATE", 1335885843),
        State("passenger_ventilation", "Setting.SET_CO_DRIVING_SEAT_VENTILATION_STATE", 1335885840),
    )
    private val sunroof = State("sunroof", "Bodywork.BODYWORK_MOON_ROOF_OPEN_PERCENT", 1101004808)
    private val sunshade = State("sunshade", "Bodywork.BODYWORK_SUNSHADE_PANEL_PERCENT", 1101004816)
    private val acPower = State("on", "Ac.AC_POWER_STATE", 1077936144)
    private val acTemperature = State("set_temperature_c", "Ac.AC_TEMP_MAIN", 1077936168)
    private val acFan = State("fan", "Ac.AC_WIND_LEVEL", 1077936156)
    private val wheelHeating = State("steering_wheel_heating", "Setting.SET_STEERING_WHEEL_HEAT_STATE", 1116733454)

    /** The cabin sensor, filtered by the climate system — the same state the "how warm is it in here?" answer uses. */
    private val cabinTemperature = State("cabin_temperature_c", InsideTemperatureTool.STATE_NAME, InsideTemperatureTool.STATE_ID)

    /** The outside air, as the instrument cluster has it. It can be below zero, so it is not range-checked upwards only. */
    private val outsideTemperature = State("outside_temperature_c", OutsideTemperatureTool.VALUE_NAME, OutsideTemperatureTool.VALUE_ID)

    private val seatLevel = mapOf(1 to "off", 2 to "low", 3 to "high")

    /**
     * The battery and the ranges, which the car reports through the statistic device's own getters rather than as
     * features. Where that device refuses this process, the same three are asked for by name instead.
     */
    private val batteryByName = State("battery_percent", "Statistic.STATISTIC_ELEC_PERCENTAGE", 0)
    private val electricRangeByName = State("electric_range_km", "Statistic.STATISTIC_ELEC_DRIVING_RANGE", 0)
    private val fuelRangeByName = State("fuel_range_km", "Statistic.STATISTIC_FUEL_DRIVING_RANGE", 0)

    /**
     * @param resolve the car's number for a name, or the verified one where the car does not use that name.
     * @param read (device type, feature id) -> value, or null when the car did not answer.
     * @param raw like [read] but keeps a negative answer, which is a real temperature rather than an error code.
     * @param statistic the statistic device's getters, by method name; null where that device is not usable here.
     * @param tripComputer every consumption/mileage getter the statistic device has, by method name (see [TripComputer]).
     */
    fun encode(
        resolve: (String, Int) -> Int?,
        read: (Int, Int) -> Int?,
        raw: (Int, Int) -> Int? = read,
        statistic: (String) -> Number?,
        tripComputer: () -> Map<String, Number?> = { emptyMap() },
        speed: () -> Number? = { null },
    ): String {
        // A verified number of 0 means there is none: the state is known by name alone, so a car whose table does not
        // carry that name simply has no reading for it rather than a read of feature 0.
        fun number(state: State): Int? = resolve(state.name, state.verified)?.takeIf { it != 0 }
        fun value(device: Int, state: State): Int? = number(state)?.let { read(device, it) }
        fun signed(device: Int, state: State): Int? = number(state)?.let { raw(device, it) }

        return buildJsonObject {
            put("windows_open_percent", buildJsonObject { windows.forEach { s -> value(BODYWORK, s)?.let { put(s.key, it) } } })
            value(BODYWORK, sunroof)?.let { put("sunroof_open_percent", it) }
            value(BODYWORK, sunshade)?.let { put("sunshade_open_percent", it) }
            put("doors_open", buildJsonObject { doors.forEach { s -> value(BODYWORK, s)?.let { put(s.key, it != 0) } } })
            put(
                "ac",
                buildJsonObject {
                    value(AC, acPower)?.let { put(acPower.key, it == 1) }
                    value(AC, acTemperature)?.let { put(acTemperature.key, it) }
                    value(AC, acFan)?.let { put(acFan.key, it) }
                },
            )
            // Zero is the climate system's own "no reading", and a cabin is never at 90 °C.
            InsideTemperatureTool.usable(value(AC, cabinTemperature))?.let { put(cabinTemperature.key, it) }
            // The cluster's own "no reading" is 195; everything inside its range is a real temperature, below zero included.
            signed(INSTRUMENT, outsideTemperature)?.takeIf { it in -60..150 }?.let { put(outsideTemperature.key, it) }
            put("seats", buildJsonObject { seats.forEach { s -> value(SETTING, s)?.let { v -> put(s.key, seatLevel[v] ?: v.toString()) } } })
            value(SETTING, wheelHeating)?.let { put(wheelHeating.key, it == 2) }

            val battery = statistic("getElecPercentageValue")?.toDouble()?.toInt() ?: value(INSTRUMENT, batteryByName)
            battery?.takeIf { it in 0..100 }?.let { put(batteryByName.key, it) }
            val electric = statistic("getElecDrivingRangeValue")?.toDouble()?.toInt() ?: value(INSTRUMENT, electricRangeByName)
            electric?.takeIf { it in 0..2000 }?.let { put(electricRangeByName.key, it) }
            val fuel = statistic("getFuelDrivingRangeValue")?.toDouble()?.toInt() ?: value(INSTRUMENT, fuelRangeByName)
            fuel?.takeIf { it in 0..3000 }?.let { put(fuelRangeByName.key, it) }

            // Song PLUS edition: the car's own trip computer, under its own names; absent when the car gives none.
            val trip = runCatching { TripComputer.select(tripComputer()) }.getOrDefault(emptyMap())
            if (trip.isNotEmpty()) put("trip_computer", buildJsonObject { trip.forEach { (k, v) -> put(k, v) } })
            // Song PLUS edition: the speed, for the drive coach. While parked the car answers a "no signal" code instead.
            runCatching { speed()?.toDouble() }.getOrNull()?.takeIf { !it.isNaN() && it in 0.0..300.0 }
                ?.let { put("speed_kmh", Math.round(it * 10.0) / 10.0) }
        }.toString()
    }

    fun report(note: String): JsonObject? = runCatching {
        val facts = Json.parseToJsonElement(note).jsonObject
        buildJsonObject {
            put("status", "ok")
            put("car", facts)
            put(
                "note",
                "Live readings from the car. Window/sunroof values are percent open (0 = closed); temperatures are " +
                    "degrees Celsius. Answer briefly, and only about what is here — a reading the car did not give is absent. " +
                    "trip_computer is the car's own trip computer under its BYD names (con = consumption, phm = per 100 km, " +
                    "elec = electric in kWh, fuel = petrol in litres, mileage = km). previous_readings are earlier snapshots " +
                    "(hours_ago) of the same values. For a question about consumption or driving efficiency: compare the " +
                    "latest with the earlier ones, say plainly what went up or down, then give one or two concrete tips for " +
                    "this plug-in hybrid (charge and drive electric in town, smooth acceleration, anticipate stops so " +
                    "regeneration recovers energy, moderate A/C, steady highway speed, correct tyre pressure). Never invent a " +
                    "number that is not here.",
            )
        }
    }.getOrNull()
}
