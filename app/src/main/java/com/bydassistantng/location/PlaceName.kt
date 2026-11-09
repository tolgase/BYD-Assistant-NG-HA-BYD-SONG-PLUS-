package com.bydassistantng.location

import java.util.Locale

/**
 * Song PLUS edition: a place name a person would say ("Monastir, Tunisia"), for looking up the weather. Pure: the
 * reverse-geocoding answer is read as text, so this is unit-tested without Android or a network.
 */
object PlaceName {
    // The town as people name it, most specific first; the region only when there is no town.
    private val TOWN_KEYS = listOf("city", "town", "village", "municipality", "suburb", "county")

    /** From OpenStreetMap Nominatim's reverse answer (format=jsonv2), or null when it names nothing. */
    fun fromNominatim(json: String?): String? {
        if (json.isNullOrBlank()) return null
        fun field(key: String): String? =
            Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)
                ?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() }
        val town = TOWN_KEYS.firstNotNullOfOrNull(::field) ?: field("state")
        val country = field("country")
        return listOfNotNull(town, country).distinct().joinToString(", ").ifBlank { null }
    }

    /** From the parts Android's own geocoder gives; null when there are none. */
    fun fromParts(locality: String?, adminArea: String?, country: String?): String? =
        listOfNotNull((locality ?: adminArea)?.trim()?.takeIf { it.isNotEmpty() }, country?.trim()?.takeIf { it.isNotEmpty() })
            .distinct().joinToString(", ").ifBlank { null }

    /** When nothing names the place: the coordinates, which the model can still search the weather for. */
    fun coordinates(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.3f, %.3f", lat, lon)

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> if (i + 5 < s.length) {
                        s.substring(i + 2, i + 6).toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 6
                        continue
                    }
                    '"', '\\', '/' -> out.append(n)
                    'n', 't', 'r' -> out.append(' ')
                    else -> out.append(n)
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
