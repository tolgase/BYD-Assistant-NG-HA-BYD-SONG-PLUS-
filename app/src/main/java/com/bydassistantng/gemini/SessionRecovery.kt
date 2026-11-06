package com.bydassistantng.gemini

/**
 * Song PLUS edition: whether a Live session that broke mid-conversation should be picked up again.
 *
 * The reply is streamed over one WebSocket, so a link that wobbles for a second takes the sentence with it. Until
 * now any failure ended the conversation and said the connection was lost, which on a Wi-Fi dongle in a moving car
 * is both common and useless: the driver asked for something, heard half an answer, and had to start again.
 *
 * A failure is worth another session when it says nothing about whether a next attempt would work — a reset, a
 * timeout, a socket that simply went away — and the car still has a usable network. A rejected key, a model that
 * refused the request or an exhausted quota all say the opposite, and retrying them only wastes the driver's time.
 *
 * The budget is what keeps this from becoming a loop: a genuinely broken link fails the same way every time, so
 * after [maxAttempts] inside [windowMs] the conversation ends and the driver is told. Each conversation starts with
 * a clean budget ([reset]).
 */
internal class SessionRecovery(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val windowMs: Long = WINDOW_MS,
) {
    private val attempts = ArrayDeque<Long>()

    /**
     * Whether to open a new session for [kind] now, counting this against the budget when it says yes.
     *
     * [networkUsable] is the car's own view of its connection: a dongle with no route out should fail fast and
     * honestly rather than retry twice first. [nowMs] is a monotonic clock.
     */
    fun allow(kind: FailureKind, networkUsable: Boolean, nowMs: Long): Boolean {
        if (!worthRetrying(kind)) return false
        if (!networkUsable) return false
        while (attempts.isNotEmpty() && nowMs - attempts.first() > windowMs) attempts.removeFirst()
        if (attempts.size >= maxAttempts) return false
        attempts.addLast(nowMs)
        return true
    }

    /** A new conversation, or a turn that completed: the budget is for a run of failures, not for the day. */
    fun reset() = attempts.clear()

    /** How many recoveries have been spent in the current window — for the log line. */
    val spent: Int get() = attempts.size

    companion object {
        const val MAX_ATTEMPTS = 2
        const val WINDOW_MS = 60_000L

        /** A failure that says nothing about the next attempt. The others are answers, not accidents. */
        fun worthRetrying(kind: FailureKind): Boolean = when (kind) {
            FailureKind.NO_NETWORK, FailureKind.OTHER -> true
            FailureKind.API_KEY_REJECTED, FailureKind.BUSY, FailureKind.SERVER_REJECTED -> false
        }
    }
}
