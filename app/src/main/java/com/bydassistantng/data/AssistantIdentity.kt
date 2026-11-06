package com.bydassistantng.data

/**
 * Song PLUS edition: the names the user gave during setup. The assistant answers to its name and introduces itself
 * with it, calls the car by the name the driver uses, and greets the driver by name. Every field may be blank: a blank
 * name means "not given" and the prompt says nothing about it.
 */
data class AssistantIdentity(val assistant: String = "", val car: String = "", val driver: String = "") {

    /** Trimmed, single-spaced, and short enough to say in one breath (a name, not a sentence). */
    fun cleaned(): AssistantIdentity = AssistantIdentity(clean(assistant), clean(car), clean(driver))

    /** The sentences that open the system prompt. */
    fun promptOpening(): String {
        val c = cleaned()
        return buildString {
            append(if (c.assistant.isNotEmpty()) "You are ${c.assistant}, a concise voice assistant" else "You are a concise voice assistant")
            append(" built into the head unit of a BYD Song PLUS")
            if (c.car.isNotEmpty()) append(" that its driver calls \"${c.car}\"")
            append(".")
            if (c.assistant.isNotEmpty()) {
                append(" Your name is ${c.assistant}: when you introduce yourself or are asked your name, use it. When the driver ")
                append("starts a sentence with your name, they are just addressing you; it is not part of the request.")
            }
            if (c.car.isNotEmpty()) append(" When the driver says \"${c.car}\", they mean this car.")
            if (c.driver.isNotEmpty()) append(" The driver's name is ${c.driver}; use it now and then, naturally (in a greeting, for example), not in every reply.")
        }
    }

    companion object {
        const val MAX_CHARS = 24
        private fun clean(s: String): String =
            s.replace(Regex("[\\p{Cntrl}\"<>{}\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_CHARS).trim()
    }
}
