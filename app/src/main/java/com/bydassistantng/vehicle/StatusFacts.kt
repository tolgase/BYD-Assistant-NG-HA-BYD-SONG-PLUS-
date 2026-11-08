package com.bydassistantng.vehicle

/**
 * Song PLUS edition: the handful of numbers in a car status reading (see [CarStatus.encode]) that the welcome summary
 * and the drive coach use. Read straight out of the JSON text, so this stays pure and unit-tested; a reading the car did
 * not give is null, never zero.
 */
data class StatusFacts(
    val batteryPercent: Double? = null,
    val electricRangeKm: Double? = null,
    val fuelRangeKm: Double? = null,
    val insideCelsius: Double? = null,
    val outsideCelsius: Double? = null,
    val speedKmh: Double? = null,
    val acOn: Boolean? = null,
    val odometerKm: Double? = null,
) {
    /** Whether the car said anything at all this time. */
    val hasAny: Boolean
        get() = batteryPercent != null || electricRangeKm != null || fuelRangeKm != null || insideCelsius != null ||
            outsideCelsius != null || speedKmh != null || acOn != null || odometerKm != null

    companion object {
        private const val NUMBER = """(-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)"""

        fun parse(json: String?): StatusFacts {
            if (json.isNullOrBlank()) return StatusFacts()
            fun num(key: String): Double? =
                Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*" + NUMBER).find(json)?.groupValues?.get(1)?.toDoubleOrNull()
            return StatusFacts(
                batteryPercent = num("battery_percent"),
                electricRangeKm = num("electric_range_km"),
                fuelRangeKm = num("fuel_range_km"),
                insideCelsius = num("cabin_temperature_c"),
                outsideCelsius = num("outside_temperature_c"),
                speedKmh = num("speed_kmh"),
                // The A/C's power is the only "on" in a status reading.
                acOn = Regex("\"on\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBoolean(),
                odometerKm = odometer(json),
            )
        }

        /**
         * The odometer, under whatever name this car's trip computer uses for it (see [TripComputer]): "total_mileage"
         * first, then a name with "odometer"/"odo" in it. Split totals (EV-only, HEV-only kilometres) are not the odometer.
         */
        fun odometer(json: String): Double? {
            val trip = Regex("\"trip_computer\"\\s*:\\s*\\{([^}]*)\\}").find(json)?.groupValues?.get(1) ?: return null
            val entries = Regex("\"([a-z0-9_]+)\"\\s*:\\s*" + NUMBER).findAll(trip)
                .mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.let { m.groupValues[1] to it } }
                .toList()
            return entries.firstOrNull { it.first == "total_mileage" }?.second
                ?: entries.firstOrNull { (k, _) -> k.contains("odometer") || k.split('_').contains("odo") }?.second
        }
    }
}
