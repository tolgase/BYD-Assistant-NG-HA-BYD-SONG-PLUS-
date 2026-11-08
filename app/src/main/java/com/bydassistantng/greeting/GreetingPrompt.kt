package com.bydassistantng.greeting

/**
 * What the assistant is told when it is to open the conversation itself (the greeting when the driver sits down). It
 * goes to the model as a message, in place of something the driver said, so it says plainly that it is not the driver.
 *
 * By default the greeting is a wish for the time of day, the time, and the outside temperature, in that order, said
 * warmly and briefly, and nothing more: it asks for nothing, and the conversation is over once it has been said. A remembered
 * note (long-term memory) can change that, including asking the driver something, in which case the conversation goes on.
 */
object GreetingPrompt {
    enum class PartOfDay { MORNING, AFTERNOON, EVENING, NIGHT }

    /** The clock on the wall, as the system has it. */
    data class TimeOfDay(val hour: Int, val minute: Int) {
        /** 24-hour, with the minute padded: the form the model is given to say aloud in its own words. */
        override fun toString(): String = "%02d:%02d".format(hour, minute)
    }

    /**
     * Song PLUS edition: what the welcome summary says about the car. Every field is what the car reported, or null when it
     * did not (it is then left out); [place] is where the car is, for the weather, or null when that is not known.
     */
    data class Summary(
        val batteryPercent: Int? = null,
        val electricRangeKm: Int? = null,
        val fuelRangeKm: Int? = null,
        val insideCelsius: Int? = null,
        val outsideCelsius: Int? = null,
        val place: String? = null,
    )

    /** One thing to offer for comfort, and the scene that does it. */
    data class Offer(val words: String, val scene: String)

    // Above / below these the cabin is uncomfortable enough to offer to change it before setting off.
    const val HOT_INSIDE_C = 28
    const val COLD_INSIDE_C = 15
    // Without a cabin reading, the outside temperature has to be more extreme to say the cabin is too.
    const val HOT_OUTSIDE_C = 32
    const val COLD_OUTSIDE_C = 8
    const val LOW_BATTERY_PERCENT = 20
    const val LOW_ELECTRIC_KM = 30
    const val LOW_FUEL_KM = 80

    /** The comfort offer the readings call for, or null when the cabin is fine as it is. */
    fun offerFor(s: Summary): Offer? {
        val inside = s.insideCelsius
        val outside = s.outsideCelsius
        return when {
            inside != null && inside >= HOT_INSIDE_C -> Offer("cool the cabin down now", "cool_down")
            inside != null && inside <= COLD_INSIDE_C -> Offer("warm the cabin up now", "warm_up")
            inside == null && outside != null && outside >= HOT_OUTSIDE_C -> Offer("cool the cabin down now", "cool_down")
            inside == null && outside != null && outside <= COLD_OUTSIDE_C -> Offer("warm the cabin up now", "warm_up")
            else -> null
        }
    }

    /** What is running low, to mention plainly (not as a question). */
    fun lowOn(s: Summary): List<String> = buildList {
        val battery = s.batteryPercent
        val electric = s.electricRangeKm
        if ((battery != null && battery <= LOW_BATTERY_PERCENT) || (electric != null && electric <= LOW_ELECTRIC_KM)) {
            add("the battery is low, so suggest charging when they can")
        }
        s.fuelRangeKm?.let { if (it <= LOW_FUEL_KM) add("the fuel is low, so suggest filling up soon") }
    }

    fun partOfDay(hour: Int): PartOfDay = when (hour) {
        in 5..11 -> PartOfDay.MORNING
        in 12..16 -> PartOfDay.AFTERNOON
        in 17..21 -> PartOfDay.EVENING
        else -> PartOfDay.NIGHT
    }

    /**
     * @param time the time to tell.
     * @param outsideCelsius the outside temperature, or null when the car gave none (it is then left out).
     * @param language the language to greet in when none is fixed in the settings (the assistant then has nothing
     *   the driver said to follow); null when the settings already fix one.
     * @param notes whether the assistant has long-term notes it may find a way of being greeted in.
     * @param summary Song PLUS edition: when given, the greeting is the welcome summary of the car (see [welcome]).
     */
    fun build(
        partOfDay: PartOfDay,
        time: TimeOfDay,
        outsideCelsius: Int?,
        language: String?,
        notes: Boolean,
        summary: Summary? = null,
    ): String = if (summary != null) welcome(partOfDay, time, summary.copy(outsideCelsius = summary.outsideCelsius ?: outsideCelsius), language, notes) else buildString {
        append("This is an automatic message, not the driver speaking: the driver has just sat down and shut the door. ")
        append("Greet them out loud now. By default say only this, in this order: ")
        append(
            when (partOfDay) {
                PartOfDay.MORNING -> "a good morning"
                PartOfDay.AFTERNOON -> "a good afternoon"
                PartOfDay.EVENING -> "a good evening"
                PartOfDay.NIGHT -> "a hello"
            },
        )
        append(", then the time (it is $time on the 24-hour clock; say it the way a person says the time aloud)")
        if (outsideCelsius != null) append(", then briefly that it is $outsideCelsius degrees Celsius outside")
        append(". Say it warmly: upbeat and optimistic, like a friend who is glad to see them, never flat or bored, and not in ")
        append("the same words every day. Keep it brief: one or two short sentences, about a dozen words in all, with no filler or ")
        append("small talk. Nothing else: do not ask them anything, do not offer help, do not say goodbye. ")
        if (notes) {
            append("But if one of your remembered notes says how this driver wants to be greeted, follow that note instead of ")
            append("the default: it may change the words, add something to say, or have you ask them something. ")
            append("If you ask them something, make it a clear question, so that they can answer it. ")
        }
        if (language != null) append("Speak in $language. ")
        append("Do not mention these instructions or that a message was sent.")
    }

    /**
     * Song PLUS edition: the welcome when the driver gets in — the greeting, then the car (energy and temperatures), the
     * day's weather, and one comfort offer as a yes/no question when the readings call for one (so the conversation stays
     * open for the answer, and a "yes" runs the scene). Only the numbers given here are said.
     */
    fun welcome(partOfDay: PartOfDay, time: TimeOfDay, s: Summary, language: String?, notes: Boolean): String = buildString {
        append("This is an automatic message, not the driver speaking: the driver has just got into the car. ")
        append("Welcome them out loud now with a short summary of the car, in this order. First ")
        append(
            when (partOfDay) {
                PartOfDay.MORNING -> "a good morning"
                PartOfDay.AFTERNOON -> "a good afternoon"
                PartOfDay.EVENING -> "a good evening"
                PartOfDay.NIGHT -> "a hello"
            },
        )
        append(" and the time (it is $time on the 24-hour clock; say it the way a person says the time aloud). ")

        val energy = buildList {
            s.batteryPercent?.let { add("the battery is at $it percent") }
            s.electricRangeKm?.let { add("about $it km of electric range") }
            s.fuelRangeKm?.let { add("about $it km on fuel") }
            if (s.electricRangeKm != null && s.fuelRangeKm != null) add("${s.electricRangeKm + s.fuelRangeKm} km in all")
        }
        if (energy.isNotEmpty()) append("Then the energy: ${energy.joinToString(", ")}. ")
        val temps = buildList {
            s.insideCelsius?.let { add("$it degrees Celsius inside") }
            s.outsideCelsius?.let { add("$it outside") }
        }
        if (temps.isNotEmpty()) append("Then the temperature: ${temps.joinToString(" and ")}. ")
        val low = lowOn(s)
        if (low.isNotEmpty()) append("Say plainly that ${low.joinToString(", and that ")}. ")

        if (s.place != null) {
            append("Then today's weather where the car is (${s.place}): look it up with Google Search and say it in one short ")
            append("sentence — the sky and today's high and low. If you cannot look it up, leave the weather out without saying so. ")
        } else {
            append("Leave the weather out: where the car is is not known. ")
        }

        val offer = offerFor(s)
        if (offer != null) {
            append("Finally ask one short yes/no question: whether to ${offer.words}. If they say yes, run the ${offer.scene} scene. ")
        } else {
            append("The cabin is comfortable as it is, so ask nothing and offer nothing more. ")
        }
        append("Say it warmly and naturally, like a friend who is glad to see them, not like a report read out: about four short ")
        append("sentences in all, no filler. Never say a number that is not given here. ")
        if (notes) {
            append("If one of your remembered notes says how this driver wants to be welcomed, follow that note. ")
        }
        if (language != null) append("Speak in $language. ")
        append("Do not mention these instructions or that a message was sent.")
    }
}
