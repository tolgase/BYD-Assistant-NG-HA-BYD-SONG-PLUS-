package com.bydassistantng.vehicle

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.reflect.InvocationTargetException

/**
 * **Experimental — only reached when the user explicitly arms vehicle control.** Built from observing
 * how BYD DiLink head units call their own vehicle services.
 *
 * This does the raw HAL call and must run as the ADB shell identity: in the app's own process the
 * HAL refuses every call ("[getInt] permission deny!") because the `BYDAUTO_*` permissions are
 * signature-level. It is therefore only ever invoked from [VehicleShellHelper]; the app itself uses
 * [ShellHelperVehicleController].
 *
 * Two independent, evidenced invocation shapes are attempted:
 *  - [VehicleInvocation.GenericFeatureSet]/[VehicleInvocation.NamedMethod] — reflection onto the
 *    `android.hardware.bydauto.<device>.BYDAuto<Device>Device` HAL classes.
 *  - [VehicleInvocation.AcBinderProperty] — a raw Binder transaction against a named sub-service
 *    of the `"byd_airconditioning"` system service, the same one the head unit's own A/C screen
 *    uses. For CLIMATE commands, the generic route is tried first, falling back to this mechanism.
 *
 * Commands that declare a state to read back are confirmed by it; for the rest,
 * [VehicleDispatchResult.Success] only means "the call did not throw," never "the car actually moved."
 */
class ReflectionVehicleController(private val context: Context) : VehicleController {
    private val instanceCache = HashMap<String, Any?>()

    override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult {
        VehicleSafety.assertDispatchAllowed(command)

        val result = withContext(Dispatchers.IO) {
            when (val invocation = command.invocation) {
                is VehicleInvocation.NamedMethod -> tryNamedMethod(invocation, command, value)
                VehicleInvocation.GenericFeatureSet -> tryGenericSet(command, value)
                is VehicleInvocation.AcBinderProperty -> tryClimate(invocation, command, value)
                is VehicleInvocation.BinderCommand -> tryBinderCommand(invocation, command, value)
                is VehicleInvocation.AudioStreamVolume -> StreamVolume.set(context, invocation.stream, value)
            }
        }

        return result
    }

    override suspend fun query(query: VehicleQuery): VehicleDispatchResult = withContext(Dispatchers.IO) {
        when (query) {
            VehicleQuery.OUTSIDE_TEMPERATURE -> OutsideTemperatureTool.read(context).fold(
                onSuccess = { VehicleDispatchResult.Success(OutsideTemperatureTool.encode(it)) },
                onFailure = { queryFailure(it) },
            )
            VehicleQuery.INSIDE_TEMPERATURE -> readInsideTemperature()
            VehicleQuery.TYRES -> TyreReader(context).read().fold(
                onSuccess = { VehicleDispatchResult.Success(TyreStatusTool.encode(it)) },
                onFailure = { queryFailure(it) },
            )
            VehicleQuery.AIR_QUALITY -> AirQualityTool.read(context).fold(
                onSuccess = { VehicleDispatchResult.Success(AirQualityTool.encode(it)) },
                onFailure = { queryFailure(it) },
            )
            VehicleQuery.HEADREST_SOUND -> readHeadrestSound()
            VehicleQuery.DRIVER_PRESENCE -> readDriverPresence()
            VehicleQuery.CAR_STATUS -> readCarStatus()
        }
    }

    /** The cabin air temperature from the climate system's inside sensor, and the unit that system shows temperatures in. */
    private fun readInsideTemperature(): VehicleDispatchResult = try {
        val instance = getOrCreateInstance(AC_DEVICE_CLASS)
        if (instance == null) {
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for $AC_DEVICE_CLASS")
        } else {
            val celsius = InsideTemperatureTool.usable(readState(instance, InsideTemperatureTool.DEVICE_TYPE, InsideTemperatureTool.STATE_ID))
            val unit = instance.javaClass.getMethod("getTemperatureUnit").invoke(instance) as? Int
            VehicleDispatchResult.Success(InsideTemperatureTool.encode(RawInsideTemperature(celsius, unit)))
        }
    } catch (t: Throwable) {
        queryFailure(t)
    }

    /** Whether the driver's seat is occupied and the driver's door is shut, as the car reports them (see [DriverPresence]). */
    private fun readDriverPresence(): VehicleDispatchResult = try {
        val bodywork = GENERIC_ROUTE_CLASS_BY_DOMAIN.getValue(VehicleDomain.BODYWORK)
        val seatDevice = getOrCreateInstance(DriverPresence.SEAT_CLASS)
        val doorDevice = getOrCreateInstance(bodywork)
        if (seatDevice == null || doorDevice == null) {
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for ${if (seatDevice == null) DriverPresence.SEAT_CLASS else bodywork}")
        } else {
            val seat = readState(seatDevice, DriverPresence.SEAT_DEVICE_TYPE, DriverPresence.SEAT_STATE_ID)
            val door = readState(doorDevice, DriverPresence.DOOR_DEVICE_TYPE, DriverPresence.DOOR_STATE_ID)
            VehicleDispatchResult.Success(DriverPresence.encode(DriverPresence.State(seat, door)))
        }
    } catch (t: Throwable) {
        queryFailure(t)
    }

    /** The headrest sound mode and the number of headrest speakers, as the car reports them (see [HeadrestSound]). */
    private fun readHeadrestSound(): VehicleDispatchResult = try {
        val className = GENERIC_ROUTE_CLASS_BY_DOMAIN.getValue(VehicleDomain.AUDIO)
        val instance = getOrCreateInstance(className)
        if (instance == null) {
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for $className")
        } else {
            val mode = readState(instance, HeadrestSound.DEVICE_TYPE, HeadrestSound.MODE_STATE_ID)
            val speakers = readState(instance, HeadrestSound.DEVICE_TYPE, HeadrestSound.SPEAKER_CONFIG_STATE_ID)
            VehicleDispatchResult.Success(HeadrestSound.encode(HeadrestSound.State(mode, speakers)))
        }
    } catch (t: Throwable) {
        queryFailure(t)
    }

    /** Song PLUS edition: windows, doors, A/C, seats, battery and range in one answer (see [CarStatus]). */
    private fun readCarStatus(): VehicleDispatchResult = try {
        val devices = mapOf(
            1000 to "android.hardware.bydauto.ac.BYDAutoAcDevice",
            1001 to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            1023 to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        ).mapValues { (_, cls) -> runCatching { getOrCreateInstance(cls) }.getOrNull() }
        val statistic = runCatching { getOrCreateInstance("android.hardware.bydauto.statistic.BYDAutoStatisticDevice") }.getOrNull()
        val encoded = CarStatus.encode(
            read = { type, id -> readState(devices[type], type, id) },
            statistic = { method ->
                statistic?.let { s -> runCatching { s.javaClass.getMethod(method).invoke(s) as? Number }.getOrNull() }
            },
        )
        VehicleDispatchResult.Success(encoded)
    } catch (t: Throwable) {
        queryFailure(t)
    }

    private fun queryFailure(t: Throwable): VehicleDispatchResult {
        val missing = t is ClassNotFoundException || t is NoSuchMethodException
        return VehicleDispatchResult.Failure(
            if (missing) VehicleDispatchError.CLASS_NOT_FOUND else VehicleDispatchError.INVOCATION_FAILED,
            t.toString(),
        )
    }

    /** A typed setter. A command that names a [VehicleCommand.stateFeatureId] and a device type is confirmed
     * by reading that state back, as the generic route is: the method's argument is [value] plus
     * [VehicleCommand.valueOffset], and the state is expected to read [value] plus [VehicleCommand.stateOffset]. */
    private suspend fun tryNamedMethod(invocation: VehicleInvocation.NamedMethod, command: VehicleCommand, value: Int): VehicleDispatchResult {
        val className = "android.hardware.bydauto.${invocation.deviceClass}.BYDAuto${invocation.deviceClass.replaceFirstChar { it.uppercase() }}Device"
        return try {
            val instance = getOrCreateInstance(className) ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for $className"
            )
            val paramTypes = invocation.paramTypes.map { intClassFor(it) }.toTypedArray()
            val args = invocation.argsTemplate.map { it ?: command.signalFor(value) }.toTypedArray()

            val method = findMethod(instance.javaClass, invocation.methodName, *paramTypes)
            val accepted = toResult(method.invoke(instance, *args))
            val deviceType = command.deviceType
            val stateId = command.stateFeatureId
            if (accepted is VehicleDispatchResult.Success && deviceType != null && stateId != null) {
                verifyGenericState(instance, command, deviceType, stateId, value + command.stateOffset, before = null) { it }
            } else {
                accepted
            }
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found: $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, e.message)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Method not found: ${invocation.methodName} on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.METHOD_NOT_FOUND, e.message)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied calling ${invocation.methodName}", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "${invocation.methodName} threw", e.targetException)
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, e.targetException?.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error calling ${invocation.methodName}", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** `AbsBYDAutoDevice.set(deviceType, int[]{featureId}, int[]{value})` — documented `protected`.
     * A command with a [VehicleCommand.stateFeatureId] is confirmed by reading that state back; one
     * with a [VehicleCommand.requires] is refused, untouched, unless the car is in that state. */
    private suspend fun tryGenericSet(command: VehicleCommand, value: Int): VehicleDispatchResult {
        val deviceType = command.deviceType
        val featureId = command.featureId
        if (deviceType == null || featureId == null) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "Missing deviceType/featureId for ${command.id}")
        }

        // The generic route's owning device class isn't recorded per-command since it's shared
        // across a whole service group; resolve by domain instead of a hardcoded class guess.
        // Song PLUS edition: CLIMATE maps to BYDAutoAcDevice, which exists on DiLink 5.0 (the agent reads it).
        val className = GENERIC_ROUTE_CLASS_BY_DOMAIN[command.domain]
        if (className == null && autoService() == null) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "No known HAL class for domain ${command.domain}")
        }

        return try {
            // Song PLUS edition: the SDK device object is optional. On DiLink 5.0 its set() refuses the shell
            // identity, while the car's "autoservice" binder accepts the same (device, feature, value) write.
            val instance = className?.let { runCatching { getOrCreateInstance(it) }.getOrNull() }
            if (instance == null && autoService() == null) {
                return VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, "No usable instance for $className")
            }
            command.requires?.let { requirement ->
                val actual = readState(instance, deviceType, requirement.featureId)
                if (actual != requirement.equals) {
                    Log.i(TAG, "${command.id} refused: state ${requirement.featureId} reads $actual, needs ${requirement.equals}")
                    return VehicleDispatchResult.Failure(VehicleDispatchError.PRECONDITION_NOT_MET, requirement.message)
                }
            }
            val expectedState = command.signalFor(value)
            val override = command.overrides.firstOrNull { it.whenValue == value }
            val controlId = override?.featureId ?: featureId
            val sent = override?.sendValue ?: expectedState
            val stateId = command.stateFeatureId
            val before = if (command.gradual && stateId != null) readState(instance, deviceType, stateId) else null
            if (controlId in 0 until PLACEHOLDER_ID_BELOW) {
                return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "Feature $controlId is a placeholder on this firmware; not written")
            }
            val accepted = writeFeature(instance, deviceType, controlId, sent)
            if (accepted is VehicleDispatchResult.Success && stateId != null) {
                verifyGenericState(instance, command, deviceType, stateId, expectedState, before) { command.valueFor(it) }
            } else {
                accepted
            }
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "Class not found: $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.CLASS_NOT_FOUND, e.message)
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "set() not found on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.METHOD_NOT_FOUND, e.message)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied calling set() on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: InvocationTargetException) {
            Log.w(TAG, "set() threw on $className", e.targetException)
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, e.targetException?.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error calling set() on $className", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** Polls briefly: the car takes a moment to report a new value after accepting it. The user is
     * told values as their screen shows them ([toValue] takes the command's offset, or inversion, back off). For a
     * [VehicleCommand.gradual] state, [before] is the reading at the time of the set, and "has set off
     * toward the target" counts as success too. */
    private suspend fun verifyGenericState(
        instance: Any?,
        command: VehicleCommand,
        deviceType: Int,
        stateId: Int,
        expected: Int,
        before: Int?,
        toValue: (Int) -> Int,
    ): VehicleDispatchResult {
        var last: Int? = null
        repeat(STATE_VERIFY_ATTEMPTS) {
            delay(STATE_VERIFY_INTERVAL_MS)
            val now = readState(instance, deviceType, stateId)
            last = now
            if (now != null) {
                if (kotlin.math.abs(now - expected) <= command.stateTolerance) {
                    return VehicleDispatchResult.Success("confirmed: the car now reports ${toValue(now)}")
                }
                if (command.gradual && before != null && now != before && (now - before) * (expected - before) > 0) {
                    return VehicleDispatchResult.Success("under way: now at ${toValue(now)}, heading for ${toValue(expected)}")
                }
            }
        }
        val actual = last
        // No usable reading is the car's "can't answer", not a value — the set may well have worked.
        // Song PLUS edition: the car accepted the write. On this car several states report late or in another scale
        // than the control (it really changed while a strict check said "failed"), so an accepted write is a success;
        // the note tells the model what the car still showed, without calling it a failure.
        return when {
            actual == null -> VehicleDispatchResult.Success("sent, but the car wouldn't report a value to confirm it")
            else -> VehicleDispatchResult.Success(
                "the car accepted it (its status still read ${toValue(actual)} a moment later; it may take a few seconds)",
            )
        }
    }

    /** `AbsBYDAutoDevice.get(deviceType, stateId)`; null when the car has no answer (an unsupported
     * state reads -10011, or 65535 for some) or the call fails. */
    private fun readState(instance: Any?, deviceType: Int, stateId: Int): Int? {
        val viaSdk = if (instance == null) null else try {
            val get = findMethod(instance.javaClass, "get", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            (get.invoke(instance, deviceType, stateId) as? Int)?.takeIf { it >= 0 && it != NO_READING }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read state $stateId through the SDK", e)
            null
        }
        // The SDK answers "unsupported" for some states that autoservice can read for the shell identity.
        return viaSdk ?: autoGet(deviceType, stateId)?.takeIf { it >= 0 && it != NO_READING }
    }

    // ── Song PLUS edition: the DiLink 5.0 "autoservice" binder ────────────────────────────────────────────
    // Transaction 6 = setInt(device, feature, value) -> status (0 accepted); transaction 5 = getInt(device, feature)
    // -> status, value. The same route the car's own apps use; it accepts the ADB shell identity where the SDK's
    // protected set() is refused.

    private var autoBinder: IBinder? = null
    private var autoDescriptor: String = ""
    private var autoLooked = false

    private fun autoService(): IBinder? {
        if (!autoLooked) {
            autoLooked = true
            autoBinder = try {
                serviceBinder(AUTO_SERVICE_NAME)
            } catch (t: Throwable) {
                Log.w(TAG, "No $AUTO_SERVICE_NAME", t)
                null
            }
            autoDescriptor = try { autoBinder?.interfaceDescriptor.orEmpty() } catch (_: Throwable) { "" }
        }
        return autoBinder
    }

    private fun autoSet(deviceType: Int, featureId: Int, value: Int): Int? {
        val binder = autoService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(autoDescriptor)
            data.writeInt(deviceType)
            data.writeInt(featureId)
            data.writeInt(value)
            if (!binder.transact(TRANSACT_AUTO_SET_INT, data, reply, 0)) null
            else if (reply.dataAvail() >= 4) reply.readInt() else null
        } catch (t: Throwable) {
            Log.w(TAG, "autoservice setInt($deviceType, $featureId, $value) failed", t)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun autoGet(deviceType: Int, featureId: Int): Int? {
        val binder = autoService() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(autoDescriptor)
            data.writeInt(deviceType)
            data.writeInt(featureId)
            if (!binder.transact(TRANSACT_AUTO_GET_INT, data, reply, 0)) return null
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return null
            val value = if (reply.dataAvail() >= 4) reply.readInt() else return null
            if (status == 0) value else null
        } catch (t: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** autoservice first; the SDK's protected set() when autoservice is missing or refuses. */
    private fun writeFeature(instance: Any?, deviceType: Int, featureId: Int, value: Int): VehicleDispatchResult {
        val code = autoSet(deviceType, featureId, value)
        if (code == 0) return VehicleDispatchResult.Success("autoservice accepted it")
        if (instance == null) {
            return VehicleDispatchResult.Failure(
                VehicleDispatchError.INVOCATION_FAILED,
                if (code == null) "autoservice is not reachable" else "autoservice refused it (answer $code)",
            )
        }
        val method = findMethod(instance.javaClass, "set", Int::class.java, IntArray::class.java, IntArray::class.java)
        val viaSdk = toResult(method.invoke(instance, deviceType, intArrayOf(featureId), intArrayOf(value)))
        return if (viaSdk is VehicleDispatchResult.Failure && code != null) {
            VehicleDispatchResult.Failure(viaSdk.error, "autoservice answered $code; ${viaSdk.detail}")
        } else viaSdk
    }

    /**
     * Climate on the Song PLUS: the A/C's own feature table through autoservice (the route the BYD Remote Agent
     * uses successfully on this car), then the original `byd_airconditioning` property route as a fallback.
     */
    private suspend fun tryClimate(
        invocation: VehicleInvocation.AcBinderProperty,
        command: VehicleCommand,
        value: Int,
    ): VehicleDispatchResult {
        if (command.deviceType != null && command.featureId != null && autoService() != null) {
            val direct = tryGenericSet(command, value)
            if (direct is VehicleDispatchResult.Success) return direct
            Log.i(TAG, "${command.id}: autoservice route failed ($direct); trying byd_airconditioning")
            val legacy = tryAcBinderProperty(invocation, command, value)
            return if (legacy is VehicleDispatchResult.Success) legacy else direct
        }
        return tryAcBinderProperty(invocation, command, value)
    }

    /**
     * A/C controls via BYD's `com.byd.ac` service: resolve the `"byd_airconditioning"` system service
     * (hidden `ServiceManager.getService()`, not root-gated), ask it for the named sub-binder
     * (`IBydAcService.getService`, transaction 3), then `IAcAirConditioner.setBydAutoAcValue`
     * (transaction 3) with a `PropertyValue(propertyId, area, value)`.
     *
     * The service accepts a set it doesn't understand without any error, so success is only reported
     * once the value is **read back** (`getBydAutoAcValue`, transaction 2) and matches — this is what
     * turned "says it's done, nothing happened" into an honest failure.
     */
    private suspend fun tryAcBinderProperty(
        invocation: VehicleInvocation.AcBinderProperty,
        command: VehicleCommand,
        value: Int,
    ): VehicleDispatchResult {
        return try {
            val master = getAcServiceBinder() ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.CLASS_NOT_FOUND, "System service '$AC_SERVICE_NAME' not found"
            )
            val subBinder = getSubBinder(master, invocation.subServiceKey) ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.SUB_SERVICE_NOT_FOUND, "Sub-service '${invocation.subServiceKey}' not resolved"
            )
            setAcProperty(subBinder, invocation.interfaceDescriptor, invocation.propertyId, invocation.area, value)
            verifyAcProperty(subBinder, invocation, value)
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied dispatching ${command.id} via AcBinderProperty", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error dispatching ${command.id} via AcBinderProperty", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    /** Polls briefly: the A/C takes a moment to report a new value after accepting it. */
    private suspend fun verifyAcProperty(
        subBinder: IBinder,
        invocation: VehicleInvocation.AcBinderProperty,
        expected: Int,
    ): VehicleDispatchResult {
        var last: Int? = null
        repeat(AC_VERIFY_ATTEMPTS) {
            delay(AC_VERIFY_INTERVAL_MS)
            last = getAcProperty(subBinder, invocation.interfaceDescriptor, invocation.propertyId, invocation.area)
            if (last == expected) return VehicleDispatchResult.Success("confirmed: A/C now reports $expected")
        }
        val actual = last
        // A negative reading is the service's "can't answer" (-10011), not a real value — the set
        // may well have worked, it just can't be confirmed.
        return if (actual == null || actual < 0) {
            VehicleDispatchResult.Success("sent, but the A/C wouldn't report a value to confirm it")
        } else {
            VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "The A/C did not apply it: asked for $expected but it reports $actual")
        }
    }

    /**
     * A command on a system service that answers with a status int: one transaction carrying
     * `(commandCode, value)`. The answer *is* the service's verdict (it knows whether the car is on, the card
     * is present and so on), so there is nothing to read back.
     */
    private fun tryBinderCommand(
        invocation: VehicleInvocation.BinderCommand,
        command: VehicleCommand,
        value: Int,
    ): VehicleDispatchResult {
        return try {
            val binder = serviceBinder(invocation.serviceName) ?: return VehicleDispatchResult.Failure(
                VehicleDispatchError.CLASS_NOT_FOUND, "System service '${invocation.serviceName}' not found",
            )
            val code = transactCommand(binder, invocation, value)
            Log.i(TAG, "${command.id} -> ${invocation.serviceName} answered $code")
            if (code in invocation.successCodes) {
                VehicleDispatchResult.Success("the service accepted it (answer $code)")
            } else {
                VehicleDispatchResult.Failure(
                    VehicleDispatchError.INVOCATION_FAILED,
                    invocation.failureMessages[code] ?: "The service refused it (answer $code).",
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Security denied dispatching ${command.id} via BinderCommand", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.SECURITY_DENIED, e.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error dispatching ${command.id} via BinderCommand", e)
            VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, e.message)
        }
    }

    private fun serviceBinder(name: String): IBinder? {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        return serviceManagerClass.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    }

    private fun transactCommand(binder: IBinder, invocation: VehicleInvocation.BinderCommand, value: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(invocation.interfaceDescriptor)
            data.writeInt(invocation.commandCode)
            data.writeInt(value)
            binder.transact(invocation.transactionCode, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** `android.os.ServiceManager` is `@hide` (not root-gated) — reflection is the only way in. */
    private fun getAcServiceBinder(): IBinder? {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        return serviceManagerClass.getMethod("getService", String::class.java)
            .invoke(null, AC_SERVICE_NAME) as? IBinder
    }

    private fun getSubBinder(master: IBinder, subServiceKey: String): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(AC_MASTER_INTERFACE_DESCRIPTOR)
            data.writeString(subServiceKey)
            master.transact(TRANSACT_GET_SUB, data, reply, 0)
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Confirmed Parcel shape for a "set" call, shared across every `com.byd.ac.*` sub-interface:
     * `writeInterfaceToken, writeInt(count=1), writeInt(id), writeInt(area), writeString(typeName), writeValue(boxed)`. */
    private fun setAcProperty(subBinder: IBinder, interfaceDescriptor: String, id: Int, area: Int, value: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(interfaceDescriptor)
            data.writeInt(1)
            data.writeInt(id)
            data.writeInt(area)
            data.writeString("java.lang.Integer")
            data.writeValue(value)
            subBinder.transact(TRANSACT_SET_PROPERTY, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Looks up [name] on [type] and every superclass, including non-public methods: the generic
     * `set(int, int[], int[])` is `protected` on `AbsBYDAutoDevice`, so `getMethod` and a
     * `getDeclaredMethod` on the concrete device class alone both miss it. */
    private fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException("${type.name}.$name(${parameterTypes.joinToString { it.name }})")
    }

    /** The HAL's setters return an int status. Negative is an error code (a read of an unsupported
     * feature comes back -10011); 0 is "accepted". Accepted is not the same as the car having moved. */
    private fun toResult(returned: Any?): VehicleDispatchResult {
        val code = returned as? Int ?: return VehicleDispatchResult.Success()
        Log.i(TAG, "HAL returned $code")
        return if (code < 0) VehicleDispatchResult.Failure(VehicleDispatchError.INVOCATION_FAILED, "HAL returned error code $code")
        else VehicleDispatchResult.Success("HAL returned $code")
    }

    /** `IAcAirConditioner.getBydAutoAcValue(id, area)`, transaction 2. The reply is the Parcelable
     * PropertyValue: a non-null marker, then `id, area, valueClassName, value`. */
    private fun getAcProperty(subBinder: IBinder, interfaceDescriptor: String, id: Int, area: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(interfaceDescriptor)
            data.writeInt(id)
            data.writeInt(area)
            subBinder.transact(TRANSACT_GET_PROPERTY, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) return null
            reply.readInt() // id
            reply.readInt() // area
            reply.readString() // value class name
            reply.readValue(Int::class.java.classLoader) as? Int
        } catch (e: Exception) {
            Log.w(TAG, "Could not read back A/C property $id", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Tries a static `getInstance(Context)` first, falling back to a no-arg constructor. */
    private fun getOrCreateInstance(className: String): Any? = instanceCache.getOrPut(className) {
        val clazz = Class.forName(className)
        try {
            clazz.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: NoSuchMethodException) {
            try {
                clazz.getMethod("getInstance").invoke(null)
            } catch (_: NoSuchMethodException) {
                clazz.getDeclaredConstructor().newInstance()
            }
        }
    }

    private fun intClassFor(typeName: String): Class<*> = when (typeName) {
        "int" -> Int::class.javaPrimitiveType!!
        else -> Class.forName(typeName)
    }

    companion object {
        private const val TAG = "VehicleController"

        private const val AC_SERVICE_NAME = "byd_airconditioning"

        private const val AUTO_SERVICE_NAME = "autoservice"
        private const val TRANSACT_AUTO_GET_INT = 5
        private const val TRANSACT_AUTO_SET_INT = 6

        /** Real feature ids are hundreds of millions; small ones are placeholders some firmware fills in. */
        private const val PLACEHOLDER_ID_BELOW = 2_000_000
        private const val AC_DEVICE_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"

        // Matches com.byd.ac.IBydAcService.DESCRIPTOR in the car's own /system/framework/com.byd.ac.jar.
        private const val AC_MASTER_INTERFACE_DESCRIPTOR = "com.byd.ac.IBydAcService"

        private const val TRANSACT_GET_SUB = 3
        private const val TRANSACT_SET_PROPERTY = 3
        private const val TRANSACT_GET_PROPERTY = 2

        private const val AC_VERIFY_ATTEMPTS = 12
        private const val AC_VERIFY_INTERVAL_MS = 120L

        // The fridge/seat states follow a set within about a second.
        private const val STATE_VERIFY_ATTEMPTS = 20
        private const val STATE_VERIFY_INTERVAL_MS = 150L
        private const val NO_READING = 65535

        private val GENERIC_ROUTE_CLASS_BY_DOMAIN = mapOf(
            VehicleDomain.BODYWORK to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            VehicleDomain.LIGHT to "android.hardware.bydauto.light.BYDAutoLightDevice",
            VehicleDomain.AUDIO to "android.hardware.bydauto.audio.BYDAutoAudioDevice",
            VehicleDomain.SETTING to "android.hardware.bydauto.setting.BYDAutoSettingDevice",
            VehicleDomain.CLIMATE to "android.hardware.bydauto.ac.BYDAutoAcDevice",
        )
    }
}
