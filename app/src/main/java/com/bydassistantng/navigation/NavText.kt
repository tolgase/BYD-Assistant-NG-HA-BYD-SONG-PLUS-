package com.bydassistantng.navigation

/** What a maps app's navigation notification says, read out of its text. Every field may be missing. */
data class NavInfo(
    /** Every distance the notification shows, in km, in the order shown. Usually the next turn and the distance left. */
    val distancesKm: List<Double> = emptyList(),
    /** Time left to the destination, when the notification shows one. */
    val minutesLeft: Int? = null,
    /** Arrival time as shown ("14:32", "2:32 PM"). */
    val eta: String? = null,
)

/** The latest navigation notification: which app, its lines of text, and when it was last updated (monotonic ms). */
data class NavSnapshot(val app: String, val lines: List<String>, val atMs: Long)

/**
 * Song PLUS edition: reads a navigation notification's text (Google Maps, a ReVanced Maps, Waze, the car's own maps) in
 * English, French or Arabic. Pure: no Android, unit-tested. It only ever finds what the text says; it does not guess a
 * distance that is not there.
 */
object NavText {
    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    // A unit is a whole word: "m" is not the start of "min", and "mi" is not the start of "min" either.
    private const val END = """(?![\p{L}\d])"""

    private val DISTANCE = Regex(
        NUMBER + """\s*(km|kms|kilometers?|kilometres?|kilomètres?|كم|كلم|mi|miles?|m|meters?|metres?|mètres?|م|ft|feet)""" + END,
        RegexOption.IGNORE_CASE,
    )
    private val HOURS = Regex("""(\d+)\s*(h|hr|hrs|hours?|heures?|ساعه|ساعات|سا)""" + END, RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""(\d+)\s*(min|mins|minutes?|mn|دقيقه|دقائق|دقايق|د)""" + END, RegexOption.IGNORE_CASE)
    // 14:32, 2:32 PM, and the French 14h32.
    private val CLOCK = Regex("""(?<![\d:])([01]?\d|2[0-3])[:h]([0-5]\d)(?:\s*([ap])\.?\s?m\.?)?(?!\d)""", RegexOption.IGNORE_CASE)

    fun parse(lines: List<String>): NavInfo {
        val text = lines.joinToString("  ") { clean(it) }
        val distances = DISTANCE.findAll(text).mapNotNull { m -> toKm(m.groupValues[1], m.groupValues[2]) }.toList()
        val hours = HOURS.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val left = if (hours == null && minutes == null) null else (hours ?: 0) * 60 + (minutes ?: 0)
        val eta = CLOCK.find(text)?.value?.trim()
        return NavInfo(distances, left, eta)
    }

    /** Arabic-Indic digits as plain ones, the Arabic decimal comma as a comma, odd spaces as spaces, no tatweel. */
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                'ـ' -> Unit // tatweel: decoration only
                in '٠'..'٩' -> sb.append('0' + (c - '٠'))
                in '۰'..'۹' -> sb.append('0' + (c - '۰'))
                '٫' -> sb.append(',')
                ' ', ' ', ' ', ' ' -> sb.append(' ')
                'ة' -> sb.append('ه')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun toKm(number: String, unit: String): Double? {
        val v = number.replace(',', '.').toDoubleOrNull() ?: return null
        val km = when (unit.lowercase()) {
            "m", "meter", "meters", "metre", "metres", "mètre", "mètres", "م" -> v / 1000.0
            "mi", "mile", "miles" -> v * 1.609344
            "ft", "feet" -> v * 0.0003048
            else -> v
        }
        return if (km in 0.0..20_000.0) Math.round(km * 1000.0) / 1000.0 else null
    }
}

/**
 * The latest navigation notification, as the notification listener last saw it. Kept in memory only (never stored or
 * sent anywhere but the driver's own question), and only ever for a maps app: nothing else is looked at.
 */
object NavNotifications {
    @Volatile var latest: NavSnapshot? = null
        private set
    @Volatile private var latestKey: String? = null

    /** A maps or navigation app, under its real package name or a patched/renamed one (ReVanced keeps the `.maps` ending). */
    fun isNavigationApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.endsWith(".maps") || p.contains(".maps.") || p == "com.waze" || p.endsWith(".waze")
    }

    fun posted(key: String, pkg: String, lines: List<String>, nowMs: Long) {
        if (!isNavigationApp(pkg) || lines.isEmpty()) return
        latest = NavSnapshot(pkg, lines, nowMs)
        latestKey = key
    }

    /** The notification went away: navigation ended. */
    fun removed(key: String) {
        if (key == latestKey) {
            latest = null
            latestKey = null
        }
    }

    fun clear() {
        latest = null
        latestKey = null
    }
}
