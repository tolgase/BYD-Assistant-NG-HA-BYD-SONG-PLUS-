package com.bydassistantng.vehicle

/**
 * The controls this car names but does not have.
 *
 * A car's feature table is written for a model line, not for one car: a Song PLUS names the fridge, the rear seat heating and
 * the mirror that tilts in reverse whether or not that car was built with them. [NameResolution] cannot tell the difference,
 * because the name is there — so without this check the app offers controls that quietly do nothing, which is worse than not
 * offering them: the user is told the car took a command it never had.
 *
 * The car itself says which: a state feature that exists in the table but is not wired to anything reads [NOT_FITTED]. So the
 * helper reads each command's state once, while it is starting, and the ones that answer that way are withheld for this car.
 * The answer travels to the app in the helper's ready line, like the names the table lacks.
 *
 * Only a command with a state can be checked. One without is still offered: nothing here guesses, and a control whose result
 * the car does not report was never verified by its read-back anyway.
 */
internal object NotFittedCheck {
    /** What a state reads when the car names the feature but has no hardware behind it. */
    const val NOT_FITTED = 65535

    /**
     * The ids of [commands] whose state this car answers with [NOT_FITTED].
     *
     * [calls] is the car's own service, which the helper may read as the shell. A command whose state cannot be read at all —
     * the service does not answer, or answers an error — is left alone: not answering is not the same as not being fitted, and
     * a car that is merely busy must not lose its controls for a session.
     */
    fun read(commands: List<VehicleCommand>, calls: NativeCalls?): Set<String> {
        if (calls == null) return emptySet()
        val absent = LinkedHashSet<String>()
        for (command in commands) {
            val device = command.deviceType ?: continue
            val state = command.stateFeatureId ?: continue
            val reply = runCatching { calls.getInt(device, state) }.getOrNull() ?: continue
            if (reply.status == 0 && reply.value == NOT_FITTED) absent += command.id
        }
        return absent
    }
}
