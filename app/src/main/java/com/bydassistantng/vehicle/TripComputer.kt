package com.bydassistantng.vehicle

/**
 * Song PLUS edition: the car's own trip computer — consumption, mileage, energy — as its statistic device reports it.
 *
 * Which getters a car has differs between models and firmware, and none of them are written down for this one, so
 * nothing here names a getter: the statistic device's own methods are looked at, the ones whose names are about
 * consumption or distance are read, and BYD's "no reading" codes are thrown away. What comes back is only ever what the
 * car said, under the car's own names (made readable: getLastFuelConPHMValue -> last_fuel_con_phm). Pure, unit-tested.
 */
object TripComputer {
    private const val MAX_READINGS = 30

    // con/consum = consumption, phm = per hundred km, mileage/odo = distance, kwh/energy/elec/fuel = what is used.
    private val ABOUT = Regex("(?i)(con|consum|mileage|odo|energy|average|avg|trip|kwh|phm|fuel|elec)")

    // Getters every object or BYD device has that say nothing about the trip.
    private val IGNORED = setOf("getClass", "getInstance", "getType", "getDeviceType", "getContext", "getHandler", "getName")

    // BYD's codes for "no reading": unsupported now, no signal, not fitted, permission denied, and the int extremes.
    private val SENTINELS = setOf(-10011.0, -10013.0, 65535.0, -2147482644.0, 2147483647.0, -2147483648.0, 4294967295.0, -1.0)

    /** Whether a method of this name is worth reading. */
    fun wanted(methodName: String): Boolean =
        methodName.length > 3 && methodName.startsWith("get") && methodName !in IGNORED &&
            ABOUT.containsMatchIn(methodName.removePrefix("get"))

    /** Whether a getter returning [type] gives a number. */
    fun numeric(type: Class<*>): Boolean = type == Int::class.javaPrimitiveType || type == Long::class.javaPrimitiveType ||
        type == Float::class.javaPrimitiveType || type == Double::class.javaPrimitiveType ||
        type == Short::class.javaPrimitiveType || Number::class.java.isAssignableFrom(type)

    /** getLastFuelConPHMValue -> last_fuel_con_phm. */
    fun key(methodName: String): String =
        methodName.removePrefix("get").removeSuffix("Value")
            .split(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"))
            .filter { it.isNotEmpty() }
            .joinToString("_") { it.lowercase() }

    /** The readings worth keeping, by readable name: real numbers only, rounded to two decimals, sorted by name. */
    fun select(raw: Map<String, Number?>): Map<String, Double> =
        raw.entries.asSequence()
            .filter { (name, _) -> wanted(name) }
            .mapNotNull { (name, value) ->
                val v = value?.toDouble() ?: return@mapNotNull null
                if (v.isNaN() || v.isInfinite() || v in SENTINELS || v < -1_000.0 || v > 10_000_000.0) null
                else key(name) to Math.round(v * 100.0) / 100.0
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .take(MAX_READINGS)
            .toMap()
}
