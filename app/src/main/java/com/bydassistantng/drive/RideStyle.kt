package com.bydassistantng.drive

/**
 * Song PLUS edition: how long the car has been on the move. A stop longer than [breakMs] (a coffee, a fuel stop) ends
 * the ride, so the next one starts from zero. Fed with every drive sample; pure, unit-tested.
 */
class RideTracker(private val breakMs: Long = BREAK_MS) {
    private var startMs: Long? = null
    private var lastMovingMs: Long? = null

    @Synchronized
    fun update(atMs: Long, speedKmh: Double?) {
        if (speedKmh == null || speedKmh < DriveAnalysis.MOVING_KMH) return
        val last = lastMovingMs
        if (startMs == null || last == null || atMs < last || atMs - last > breakMs) startMs = atMs
        lastMovingMs = atMs
    }

    /** Minutes on the move since the ride began; 0 when not riding (never moved, or stopped longer than a break). */
    @Synchronized
    fun minutes(nowMs: Long): Int {
        val start = startMs ?: return 0
        val last = lastMovingMs ?: return 0
        if (nowMs - last > breakMs) return 0
        return ((last - start) / 60_000L).toInt()
    }

    companion object {
        const val BREAK_MS = 10 * 60_000L
    }
}

/** Who is in the car and where the ride stands, when a conversation starts. Null = the car did not say. */
data class RideContext(
    val rideMinutes: Int = 0,
    /** Minutes left to the destination, from the maps app's navigation. */
    val minutesToGo: Int? = null,
    /** Where passengers sit ("front passenger", "rear left"…); empty = the driver is alone; null = unknown. */
    val passengers: List<String>? = null,
)

/** The assistant's manner for the ride — never chosen by the user, never named to them. */
enum class RideMode {
    /** Short trips: quick commands get a word or two and the conversation ends; questions a few sentences. */
    STANDARD,

    /** A long ride: a warm travel companion, more conversational, looking after everyone on board. */
    LONG,
}

/**
 * Song PLUS edition: the three hidden manners of the assistant, chosen automatically.
 *  - A quick car command (a "shortcut") — two or three words, then the conversation ends (not on a long ride).
 *  - A question — one to three short spoken sentences.
 *  - A long ride ([LONG_RIDE_MIN] on the move, or [LONG_TRIP_TO_GO_MIN] still to go) — a travel companion, fuller and
 *    warmer, offering the passengers something now and then once the driver's request is done.
 * Added to the system prompt at the start of each conversation. Pure, unit-tested.
 */
object RideStyle {
    const val LONG_RIDE_MIN = 30
    const val LONG_TRIP_TO_GO_MIN = 45

    fun mode(c: RideContext): RideMode =
        if (c.rideMinutes >= LONG_RIDE_MIN || (c.minutesToGo ?: 0) >= LONG_TRIP_TO_GO_MIN) RideMode.LONG else RideMode.STANDARD

    fun instruction(c: RideContext): String = buildString {
        val mode = mode(c)
        append(" Match the length of every answer to what was asked, without ever mentioning it: a quick car command ")
        append("(open, close, switch on or off, a temperature, a scene) gets two or three words, such as \"Done.\" or ")
        append("\"Window open.\", and nothing more")
        if (mode == RideMode.STANDARD) append(" — and if that was all they wanted, call end_conversation right after")
        append("; a question gets one to three short spoken sentences.")

        if (mode == RideMode.LONG) {
            val facts = buildList {
                if (c.rideMinutes > 0) add("they have been on the road for about ${c.rideMinutes} minutes")
                c.minutesToGo?.let { if (it > 0) add("about $it minutes are still to go") }
            }
            append(" This is a long ride")
            if (facts.isNotEmpty()) append(" (${facts.joinToString(", and ")})")
            append(". Be a warm, easy-going travel companion: answers to questions may be a little fuller and more ")
            append("conversational than usual, and after a request is done you may add one friendly, relevant remark — ")
            append("the destination, the weather ahead, music for the road — but at most one, and drop it if the driver ")
            append("sounds busy or terse. If they have been driving for about two hours, suggest a short break.")
        }

        val p = c.passengers
        when {
            p == null -> {
                append(" You cannot tell who is in the car: a voice that is not the driver's may be a passenger — help them ")
                append("as you would the driver, and greet them warmly.")
            }
            p.isEmpty() -> append(" The driver is alone in the car: talk to them only.")
            else -> {
                val who = if (p.size == 1) "a passenger (${p.single()})" else "${p.size} passengers (${p.joinToString(", ")})"
                append(" There is $who on board. When the driver's request is done, ")
                append(if (mode == RideMode.LONG) "now and then" else "only occasionally")
                append(" — never after every request — turn to the passengers warmly by where they sit (\"and for you in the ")
                append("passenger seat…\") and offer one thing for their comfort: their seat heating or ventilation, their ")
                append("window, the temperature, the music, or a short interesting fact about where you are. Make everyone ")
                append("feel welcome. A passenger may speak to you directly: help them as you would the driver. The driver's ")
                append("requests always come first.")
            }
        }
    }
}
