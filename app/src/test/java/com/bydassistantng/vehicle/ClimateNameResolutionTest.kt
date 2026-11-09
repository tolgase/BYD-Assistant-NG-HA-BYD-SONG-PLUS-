package com.bydassistantng.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The climate controls reach the car through the A/C's own binder property, but the numbers that carry the request and
 * read the result back are still the car's own, and a Song PLUS does not number them the way the car this registry was
 * first written on does. So they carry the car's name for them, like every other control: the name is what is the same
 * from car to car, and the number is only what one car happened to call it.
 *
 * Two things follow, and both are tested here: a car that numbers the climate differently is followed rather than
 * written to at the wrong number, and a car whose table does not name it at all still gets the verified number instead
 * of losing the control. Climate is never withheld for a missing name, because its binder property works without one.
 */
class ClimateNameResolutionTest {
    private val base = VehicleCommandRegistry.commandsFor(DiLinkPlatform.UNKNOWN).known
    private val climate = base.filter { it.invocation is VehicleInvocation.AcBinderProperty }

    private fun airSource(table: Map<String, Int>? = null) =
        VehicleCommandRegistry.commandsFor(DiLinkPlatform.UNKNOWN, table).known.find { it.id == "ac.airSource" }

    @Test
    fun theRegistryHasClimateCommands() {
        assertTrue("no climate commands in the registry", climate.isNotEmpty())
    }

    /** No number in the file is the only thing standing between the app and the car's hardware. */
    @Test
    fun everyClimateNumberHasAName() {
        for (command in climate) {
            assertEquals(
                "${command.id}: a number without a name",
                FeatureNameCheck.numbersUsedBy(command),
                FeatureNameCheck.referencesOf(command).map { it.number }.distinct(),
            )
        }
    }

    @Test
    fun aCarThatNumbersTheClimateDifferentlyIsFollowed() {
        val verified = checkNotNull(airSource()) { "ac.airSource missing" }
        val name = checkNotNull(verified.featureName) { "ac.airSource has no name" }
        val onCar = checkNotNull(airSource(mapOf(name to 4242))) { "ac.airSource was withheld" }
        assertEquals("the car's own number is the one used", 4242, onCar.featureId)
    }

    @Test
    fun aCarThatDoesNotNameTheClimateKeepsTheVerifiedNumber() {
        val verified = checkNotNull(airSource()) { "ac.airSource missing" }
        val onCar = airSource(mapOf("A_NAME_NO_CAR_USES" to 1))
        assertNotNull("climate must never be withheld for a missing name", onCar)
        assertEquals(verified.featureId, onCar!!.featureId)
    }
}
