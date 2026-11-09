package com.bydassistantng.vehicle

/** One snapshot of the car's energy readings (wall-clock ms, readable name -> value). */
data class EnergyReading(val atMs: Long, val values: Map<String, Double>)

/**
 * Song PLUS edition: a short history of the car's energy readings — battery, ranges, and the trip computer — so the
 * assistant can compare earlier consumption with now and say what changed ("you used more fuel this week than last").
 *
 * A snapshot is kept at most every [MIN_GAP_MS], and only when something changed, so asking ten times in a minute does
 * not fill the log; the last [MAX_READINGS] are kept (about a month of daily driving). Pure, unit-tested; the app stores
 * the text it produces.
 */
object ConsumptionLog {
    const val MAX_READINGS = 60
    const val MIN_GAP_MS = 30 * 60_000L
    const val SHOWN = 8

    /** [readings] with [now] added when it is worth keeping; the same list otherwise. Oldest first. */
    fun record(readings: List<EnergyReading>, now: EnergyReading): List<EnergyReading> {
        if (now.values.isEmpty()) return readings
        val last = readings.lastOrNull()
        if (last != null && (now.atMs - last.atMs < MIN_GAP_MS || last.values == now.values)) return readings
        return (readings + now).takeLast(MAX_READINGS)
    }

    /** The most recent earlier readings, newest first, with how many hours ago each was taken. */
    fun previous(readings: List<EnergyReading>, nowMs: Long): List<Pair<Double, Map<String, Double>>> =
        readings.asReversed().take(SHOWN).map { r -> Math.round((nowMs - r.atMs) / 360_000.0) / 10.0 to r.values }

    // ── Storage: one reading per line, "atMs<TAB>key=value;key=value" ───────────────────────────────────

    fun encode(readings: List<EnergyReading>): String = readings.joinToString("\n") { r ->
        r.atMs.toString() + "\t" + r.values.entries.joinToString(";") { (k, v) -> "$k=$v" }
    }

    fun decode(text: String?): List<EnergyReading> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val values = parts[1].split(';').mapNotNull { kv ->
                val p = kv.split('=')
                val v = p.getOrNull(1)?.toDoubleOrNull()
                if (p.size == 2 && p[0].isNotBlank() && v != null) p[0] to v else null
            }.toMap()
            if (values.isEmpty()) null else EnergyReading(at, values)
        }.toList().takeLast(MAX_READINGS)
    }
}
