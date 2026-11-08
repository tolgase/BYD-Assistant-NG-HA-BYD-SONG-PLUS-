package com.bydassistantng.vehicle

import android.content.Context
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLogger
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ShellHelperVehicle"
private const val HELPER_CLASS = "com.bydassistantng.vehicle.VehicleShellHelper"
private const val HELPER_PATTERN = "com.bydassistantng.vehicle.[V]ehicleShellHelper"
/** A reply that fails sooner than this after sending means the helper had already gone (it never read the line). */
private const val DEAD_STREAM_MS = 1_500L

// Opening the connection and starting the helper JVM: ~1–2s. Generous, since ADB may have to reconnect.
private const val CONNECT_TIMEOUT_MS = 8_000L
private const val START_TIMEOUT_MS = 10_000L
// One command in a running helper: the HAL call, plus up to ~1.5s of A/C read-back polling.
private const val COMMAND_TIMEOUT_MS = 8_000L
// The cluster screen: turn BYD Maps on, wait for it, put the app on the cluster and keep it in front until settled.
private const val CLUSTER_COMMAND_TIMEOUT_MS = 45_000L
// A helper nobody has used for this long is shut down; the next press starts a fresh one.
private const val IDLE_CLOSE_MS = 5 * 60_000L

/** The longer idle time for the headrest speaker's conversations (see [ShellHelperVehicleController.idleCloseMs]). */
const val HEADREST_IDLE_CLOSE_MS = 30 * 60_000L

/** The ordinary one, for the code that puts it back. */
const val DEFAULT_IDLE_CLOSE_MS = IDLE_CLOSE_MS

private val SAFE_COMMAND_ID = Regex("[A-Za-z0-9_.]+")

/**
 * The real vehicle controller: sends each command to a helper process running under the ADB shell
 * identity, since the car's HAL refuses the app's own process (see [VehicleShellHelper] for why).
 *
 * The helper stays alive between commands (`--serve`), reached over one long-lived ADB stream, so a
 * command costs milliseconds rather than the ~1.3s a cold JVM start took. [warmUp] starts it ahead of
 * time — it's called when a voice turn begins, so it's ready by the time the user finishes speaking.
 * If the stream dies it is simply restarted, once, for the command in flight.
 *
 * Success only means the helper reports the call accepted (and, for A/C, confirmed by read-back).
 */
@Singleton
class ShellHelperVehicleController @Inject constructor(
    @ApplicationContext private val context: Context,
) : VehicleController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var session: HelperSession? = null
    private var idleJob: Job? = null

    /**
     * How long a helper nobody has used is kept before it is shut down. Longer while the assistant speaks from the
     * headrest, which needs the helper at the start and the end of every conversation: a helper that has to be started
     * first costs the start of the conversation most of a second.
     */
    @Volatile var idleCloseMs: Long = IDLE_CLOSE_MS

    /** Starts the helper in the background if it isn't running. Never blocks or throws. */
    fun warmUp() {
        scope.launch {
            mutex.withLock {
                runCatching { ensureSession() }.onFailure { AppLogger.logError(TAG, "Helper warm-up failed", it) }
                armIdleClose()
            }
        }
    }

    override suspend fun dispatch(command: VehicleCommand, value: Int): VehicleDispatchResult {
        VehicleSafety.assertDispatchAllowed(command)
        // The id and value end up on a helper's stdin; both come from the fixed registry and an int,
        // but this is cheap insurance against that ever changing.
        if (!SAFE_COMMAND_ID.matches(command.id)) {
            return VehicleDispatchResult.Failure(VehicleDispatchError.INVALID_ARGUMENT, "Unsafe command id '${command.id}'")
        }

        return mutex.withLock {
            try {
                // One retry, and only when the stream was found dead: a command is never re-sent after
                // the helper already answered it.
                send(command, value) ?: run {
                    AppLogger.log(TAG, "Helper connection was lost — restarting it")
                    closeSession()
                    send(command, value)
                } ?: VehicleDispatchResult.Failure(
                    VehicleDispatchError.HELPER_UNAVAILABLE,
                    "Could not reach the head unit's ADB connection — ADB debugging must be on and authorized.",
                )
            } finally {
                armIdleClose()
            }
        }
    }

    override suspend fun query(query: VehicleQuery): VehicleDispatchResult = mutex.withLock {
        try {
            sendLine("query ${query.id}") ?: run {
                AppLogger.log(TAG, "Helper connection was lost — restarting it")
                closeSession()
                sendLine("query ${query.id}")
            } ?: VehicleDispatchResult.Failure(
                VehicleDispatchError.HELPER_UNAVAILABLE,
                "Could not reach the head unit's ADB connection — ADB debugging must be on and authorized.",
            )
        } finally {
            armIdleClose()
        }
    }

    override suspend fun passengerScreen(command: String): VehicleDispatchResult = mutex.withLock {
        val line = "fse $command"
        try {
            sendLine(line) ?: run {
                AppLogger.log(TAG, "Helper connection was lost — restarting it")
                closeSession()
                sendLine(line)
            } ?: VehicleDispatchResult.Failure(
                VehicleDispatchError.HELPER_UNAVAILABLE,
                "Could not reach the head unit's ADB connection — ADB debugging must be on and authorized.",
            )
        } finally {
            armIdleClose()
        }
    }

    /**
     * One instrument-cluster command. Opening takes the helper several seconds (it turns BYD Maps on, waits for it,
     * and keeps the app in front until the map app has settled), so it is given a long answer time of its own.
     */
    override suspend fun clusterScreen(command: String): VehicleDispatchResult = mutex.withLock {
        val line = "cluster $command"
        try {
            sendLine(line, CLUSTER_COMMAND_TIMEOUT_MS) ?: run {
                AppLogger.log(TAG, "Helper connection was lost — restarting it")
                closeSession()
                sendLine(line, CLUSTER_COMMAND_TIMEOUT_MS)
            } ?: VehicleDispatchResult.Failure(
                VehicleDispatchError.HELPER_UNAVAILABLE,
                "Could not reach the head unit's ADB connection — ADB debugging must be on and authorized.",
            )
        } finally {
            armIdleClose()
        }
    }

    /**
     * Asks the helper that is already running for a drive mode ([DriveModeCommand]); null when the helper could
     * not be reached. Not a vehicle command: it has nothing to do with the registry or its denylist.
     */
    internal suspend fun requestDriveMode(value: Int): VehicleDispatchResult? {
        val line = "${DriveModeCommand.NAME} set $value"
        return mutex.withLock {
            try {
                sendLine(line) ?: run {
                    AppLogger.log(TAG, "Helper connection was lost — restarting it")
                    closeSession()
                    sendLine(line)
                }
            } finally {
                armIdleClose()
            }
        }
    }

    /** @return null if the helper couldn't be started or its stream was dead; a result otherwise. */
    private suspend fun send(command: VehicleCommand, value: Int): VehicleDispatchResult? = sendLine("${command.id} $value")

    /**
     * Every exchange with the car runs on an I/O thread, whoever asks: the offline commands are started from the wheel
     * button's main thread, and Android refuses network (the ADB socket) there with NetworkOnMainThreadException.
     */
    private suspend fun sendLine(line: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): VehicleDispatchResult? =
        withContext(Dispatchers.IO) { sendLineOnIo(line, timeoutMs) }

    private suspend fun sendLineOnIo(line: String, timeoutMs: Long): VehicleDispatchResult? {
        val helper = ensureSession() ?: return null
        // The greeting asks about the seat and the door several times a second for a while: it logs what changes itself.
        if (line != "query ${VehicleQuery.DRIVER_PRESENCE.id}") AppLogger.log(TAG, "Helper: $line")
        try {
            helper.write(line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never reached the helper (its stream was closed): safe to start a new one and send it there.
            AppLogger.log(TAG, "Helper stream unusable (${e.javaClass.simpleName}) — starting a new one")
            closeSession()
            return null
        }
        val sentAt = System.currentTimeMillis()
        val reply = try {
            helper.readLineStartingWith(HelperProtocol.RESULT_PREFIX.trim(), timeoutMs)
        } catch (e: CancellationException) {
            closeSession()
            throw e
        } catch (e: Exception) {
            // Anything else from the stream: the reply may still come later, and must never be read as the answer to the
            // next command (every answer after it would be one behind). This session is done; the line is not sent again.
            AppLogger.logError(TAG, "Reading the helper's answer to \"$line\" failed", e)
            closeSession()
            stopStrayHelpers()
            return VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "The car link failed while waiting (${e.javaClass.simpleName}); not sent again.")
        }
        if (reply == null) {
            closeSession()
            // The stream ended at once: the helper had already gone and never read the line, so sending it again is safe.
            if (System.currentTimeMillis() - sentAt < DEAD_STREAM_MS) return null
            // The helper took the line and the car did not answer in time. It may still do it later, so it is never sent
            // again, and the stuck helper is stopped: otherwise every command after it waits in its own stuck helper and
            // they all happen at once when the car's service wakes up.
            AppLogger.log(TAG, "The car did not answer \"$line\" in ${timeoutMs}ms — stopping the stuck helper, not sending it again")
            stopStrayHelpers()
            return VehicleDispatchResult.Failure(VehicleDispatchError.HELPER_UNAVAILABLE, "The car did not answer in time; the command was not sent again.")
        }
        return HelperProtocol.parse(reply)
            ?: VehicleDispatchResult.Failure(VehicleDispatchError.UNKNOWN, "Unreadable helper reply: ${reply.take(200)}")
    }

    /** Stops every helper process of this app on the car (a stuck one keeps a command it may still carry out later). */
    private suspend fun stopStrayHelpers(dadb: Dadb? = null): Unit = withContext(Dispatchers.IO) { stopStrayHelpersOnIo(dadb) }

    private suspend fun stopStrayHelpersOnIo(dadb: Dadb?) {
        val link = dadb ?: AdbHelper.connect(context, CONNECT_TIMEOUT_MS, socketTimeoutMs = 0) ?: return
        try {
            // The [V] keeps the pattern from matching the pkill command's own shell.
            runCatching { link.shell("pkill -f '$HELPER_PATTERN'") }
        } finally {
            if (dadb == null) runCatching { link.close() }
        }
    }

    private suspend fun ensureSession(): HelperSession? = withContext(Dispatchers.IO) { session ?: startSession() }

    private suspend fun startSession(): HelperSession? {
        val started = System.currentTimeMillis()
        val dadb = AdbHelper.connect(context, CONNECT_TIMEOUT_MS, socketTimeoutMs = 0) ?: return null
        // Only one helper at a time: one left over from an earlier session could still be holding a command.
        stopStrayHelpers(dadb)
        val apk = context.applicationInfo.sourceDir
        val helper = try {
            HelperSession(dadb, dadb.openShell("CLASSPATH='$apk' exec app_process /system/bin $HELPER_CLASS --serve 2>&1"))
        } catch (e: Exception) {
            runCatching { dadb.close() }
            AppLogger.logError(TAG, "Could not start the helper", e)
            return null
        }
        val ready = helper.readLineStartingWith(HelperProtocol.READY_LINE, START_TIMEOUT_MS)
        if (ready == null) {
            AppLogger.logError(TAG, "The helper never reported ready")
            helper.close()
            return null
        }
        // The helper says which platform it found the car to run, and what it saw of the car (for the log).
        HelperProtocol.parseReady(ready)?.let { PlatformState.helperReported(context, it) }
        AppLogger.log(TAG, "Helper ready in ${System.currentTimeMillis() - started}ms")
        session = helper
        return helper
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun armIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleCloseMs)
            mutex.withLock {
                AppLogger.log(TAG, "Helper idle — shutting it down")
                closeSession()
            }
        }
    }
}

/** One running helper process and the ADB stream wired to its stdin/stdout. */
internal class HelperSession(private val dadb: Dadb, private val stream: AdbShellStream) {
    private val buffer = StringBuilder()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Sends one line. Anything left unread from before is dropped first, so an old answer is never taken for this one's. */
    fun write(line: String) {
        buffer.setLength(0)
        stream.write(line + "\n")
    }

    /**
     * Reads helper output until a line starting with [prefix] arrives. Returns null if the stream
     * ends, fails, or [timeoutMs] passes. A blocking socket read can't be interrupted, so the timeout
     * works by closing the whole session from a watchdog, which makes the read throw.
     */
    suspend fun readLineStartingWith(prefix: String, timeoutMs: Long): String? {
        val watchdog = watchdogScope.launch {
            delay(timeoutMs)
            close()
        }
        return try {
            withContext(Dispatchers.IO) { runInterruptible { readBlocking(prefix) } }
        } catch (e: IOException) {
            null
        } finally {
            watchdog.cancel()
        }
    }

    private fun readBlocking(prefix: String): String? {
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline >= 0) {
                val line = buffer.substring(0, newline).trim()
                buffer.delete(0, newline + 1)
                if (line.startsWith(prefix)) return line
                continue
            }
            val packet = stream.read()
            if (packet is AdbShellPacket.Exit) return null
            buffer.append(String(packet.payload, Charsets.UTF_8))
        }
    }

    fun close() {
        runCatching { stream.close() }
        runCatching { dadb.close() }
    }
}
