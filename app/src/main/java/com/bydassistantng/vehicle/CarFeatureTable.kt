package com.bydassistantng.vehicle

import java.lang.reflect.Modifier

/**
 * The car's own table of features: every number of its `BYDAutoFeatureIds` class by name (`Group.NAME`, or just `NAME` for a
 * constant of the class itself). The class chooses its numbers when it loads, from the car's bus protocol (see
 * `docs/byd-internals/platforms.md`), so a name is what stays the same from car to car and the number is what this car
 * says it is. The vehicle helper reads it once, as the ADB shell, and the registry looks every command up in it
 * ([NameResolution]).
 */
internal object CarFeatureTable {
    private const val IDS_CLASS = "android.hardware.bydauto.BYDAutoFeatureIds"

    /** The table, or null where the car has no such class or it cannot be read. */
    fun read(loader: ClassLoader? = CarFeatureTable::class.java.classLoader): Map<String, Int>? = try {
        val ids = Class.forName(IDS_CLASS, false, loader)
        val table = HashMap<String, Int>()
        fun collect(type: Class<*>, prefix: String) {
            for (field in constantsOf(type)) {
                field.isAccessible = true
                val value = (field.get(null) as? Number)?.toLong() ?: continue
                if (value in Int.MIN_VALUE..Int.MAX_VALUE) table.putIfAbsent(prefix + field.name, value.toInt())
            }
        }
        collect(ids, "")
        for (group in ids.declaredClasses) collect(group, group.simpleName + ".")
        // A bare name as well, for a constant that sits in exactly one group. A registry entry may then name a feature
        // without knowing which group this firmware files it under (the climate constants are not in the same group on
        // every line), and an ambiguous name — the same constant in two groups — keeps needing its group.
        val bare = HashMap<String, MutableList<Int>>()
        for (group in ids.declaredClasses) {
            for (field in constantsOf(group)) {
                field.isAccessible = true
                val value = (field.get(null) as? Number)?.toLong() ?: continue
                if (value in Int.MIN_VALUE..Int.MAX_VALUE) bare.getOrPut(field.name) { ArrayList() } += value.toInt()
            }
        }
        for ((name, values) in bare) if (values.distinct().size == 1) table.putIfAbsent(name, values[0])
        table
    } catch (_: Throwable) {
        null
    }

    /** The static integer constants a class declares. */
    fun constantsOf(type: Class<*>): List<java.lang.reflect.Field> = type.declaredFields.filter {
        Modifier.isStatic(it.modifiers) && (it.type == Int::class.javaPrimitiveType || it.type == Long::class.javaPrimitiveType)
    }

    /** The window features of the car's own table with their numbers, so that what a car calls its windows can be read from a log. */
    fun windowFeatures(loader: ClassLoader? = CarFeatureTable::class.java.classLoader): String {
        val ids = Class.forName(IDS_CLASS, false, loader)
        val bodywork = ids.declaredClasses.firstOrNull { it.simpleName == "Bodywork" } ?: return "no-bodywork-group"
        return constantsOf(bodywork)
            .filter { "WINDOW" in it.name }
            .sortedBy { it.name }
            .joinToString(",") { field ->
                field.isAccessible = true
                "${field.name}=${field.get(null)}"
            }
    }
}
