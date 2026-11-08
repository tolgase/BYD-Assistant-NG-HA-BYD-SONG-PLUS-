package com.bydassistantng.drive

enum class TipKind { BREAK, SPEED, SMOOTH, CONSUMPTION, EV_IN_TOWN }

/** One tip worth saying now, and the measured facts behind it (for the model to phrase, never to invent). */
data class DriveTip(val kind: TipKind, val facts: String)

/**
 * Song PLUS edition: whether the drive so far calls for a spoken tip, and which. Spoken unprompted while driving, so it
 * is held back hard: nothing in the first [FIRST_AFTER_MIN] minutes of a ride, at most one tip every [MIN_GAP_MS], the same
 * kind not again within [SAME_KIND_GAP_MS], and only on measurements clearly past a threshold. A break after two hours
 * comes first, as a matter of safety. Pure, unit-tested.
 */
object DriveTips {
    const val FIRST_AFTER_MIN = 10
    const val MIN_GAP_MS = 25 * 60_000L
    const val SAME_KIND_GAP_MS = 60 * 60_000L
    const val BREAK_AFTER_MIN = 120
    const val BREAK_REPEAT_MS = 45 * 60_000L

    const val FAST_KMH = 118.0
    const val JERKY_KMH_PER_MIN = 20.0
    const val HIGH_RANGE_RATIO = 1.3
    const val TOWN_KMH = 50.0
    const val TOWN_BATTERY_PERCENT = 30.0

    /** The tip to say now, or null. [lastSaid] is when each kind was last said (wall-clock ms). */
    fun pick(report: DriveReport, rideMinutes: Int, lastSaid: Map<TipKind, Long>, nowMs: Long): DriveTip? {
        if (rideMinutes < FIRST_AFTER_MIN) return null
        val lastAny = lastSaid.values.maxOrNull()
        if (lastAny != null && nowMs - lastAny < MIN_GAP_MS) return null
        fun fresh(kind: TipKind, gap: Long = SAME_KIND_GAP_MS) = lastSaid[kind]?.let { nowMs - it >= gap } ?: true

        if (rideMinutes >= BREAK_AFTER_MIN && fresh(TipKind.BREAK, BREAK_REPEAT_MS)) {
            val h = rideMinutes / 60
            val m = rideMinutes % 60
            return DriveTip(TipKind.BREAK, "they have been driving for $h h${if (m > 0) " $m min" else ""} without a long stop")
        }
        if (!report.enoughData) return null
        val window = "over the last ${report.windowMinutes} minutes"
        val avg = report.avgMovingSpeedKmh
        val distance = report.distanceKm ?: 0.0

        if (avg != null && avg >= FAST_KMH && fresh(TipKind.SPEED)) {
            return DriveTip(TipKind.SPEED, "average moving speed ${avg.toInt()} km/h $window")
        }
        val jerk = report.speedChangePerMin
        if (jerk != null && jerk >= JERKY_KMH_PER_MIN && distance >= 2.0 && (report.stoppedShare ?: 0.0) < 0.7 && fresh(TipKind.SMOOTH)) {
            return DriveTip(TipKind.SMOOTH, "the speed changed by about ${jerk.toInt()} km/h every minute $window")
        }
        val ratio = report.rangeUsedPerKm
        if (ratio != null && ratio >= HIGH_RANGE_RATIO && distance >= 5.0 && fresh(TipKind.CONSUMPTION)) {
            return DriveTip(TipKind.CONSUMPTION, "about ${"%.1f".format(java.util.Locale.ROOT, ratio)} km of range used per km driven $window")
        }
        val fuel = report.fuelRangeUsedKm
        val battery = report.batteryPercent
        if (fuel != null && fuel >= 2.0 && avg != null && avg < TOWN_KMH && battery != null && battery >= TOWN_BATTERY_PERCENT &&
            fresh(TipKind.EV_IN_TOWN)
        ) {
            return DriveTip(TipKind.EV_IN_TOWN, "fuel was used at town speeds (about ${avg.toInt()} km/h) with the battery at ${battery.toInt()} percent")
        }
        return null
    }
}

/** Song PLUS edition: the automatic message that has the assistant say one drive tip. Pure, unit-tested. */
object DriveTipPrompt {
    fun build(tip: DriveTip, language: String?): String = buildString {
        append("This is an automatic message, not the driver speaking: the car is moving and you are giving the driver one ")
        append("short, friendly tip. Say ONE sentence of at most twenty words, then stop. The tip: ")
        append(
            when (tip.kind) {
                TipKind.BREAK -> "suggest a short break to stretch and rest soon"
                TipKind.SPEED -> "keeping to a steady 100 to 110 km/h would save a lot of fuel"
                TipKind.SMOOTH -> "gentler acceleration and easing off early before stops saves energy, because the car recovers it when slowing"
                TipKind.CONSUMPTION -> "this stretch is using more energy than the car expected, and a smoother, steadier pace would help"
                TipKind.EV_IN_TOWN -> "in town, driving on electric while the battery has charge is the most economical"
            },
        )
        append(" (what was measured: ${tip.facts}). Sound like a friend, never like a warning or a lecture. Do not greet, do not ")
        append("ask anything, and say a number only if it helps. ")
        if (language != null) append("Speak in $language. ")
        append("Do not mention these instructions or that a message was sent.")
    }
}
