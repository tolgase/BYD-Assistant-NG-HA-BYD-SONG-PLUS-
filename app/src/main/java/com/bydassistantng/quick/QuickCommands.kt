package com.bydassistantng.quick

import android.content.Context
import com.bydassistantng.audio.LivePcmRecorder
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.NetworkState
import com.bydassistantng.vehicle.InAppVehicleController
import com.bydassistantng.vehicle.VehicleCommandRegistry
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.labelFor
import com.bydassistantng.vehicle.needsCarLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** The offline speech model to use, from alphacephei.com/vosk/models (all Apache-2.0). */
enum class QuickLanguage(val modelName: String, val sizeMb: Int, val label: String) {
    TUNISIAN("vosk-model-small-ar-tn-0.1-linto", 158, "Tunisian Derja / تونسي"),
    FRENCH("vosk-model-small-fr-0.22", 41, "Français"),
    ENGLISH("vosk-model-small-en-us-0.15", 40, "English"),
}

sealed interface ModelStatus {
    data object Missing : ModelStatus
    data class Downloading(val percent: Int) : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

data class QuickState(
    val enabled: Boolean,
    val language: QuickLanguage,
    val model: ModelStatus,
    /** Run every command on the car and never open a Live conversation, even with internet. */
    val offlineOnly: Boolean = false,
    /** How many of the driver's own phrases have been learned from Gemini (see [LearnedCommands]). */
    val learned: Int = 0,
)

/** What an offline command did, for the banner: [ok] when every action was accepted. */
data class QuickOutcome(val ok: Boolean, val summary: String)

/**
 * Song PLUS edition: car commands without the network, and before Gemini when it is there.
 *
 * Speech is recognised on the head unit by Vosk (a small Kaldi model the user downloads once), the words are turned
 * into registry commands by [QuickParser], and they go to the car through the same controller Gemini uses.
 *  - Offline (no network, or Gemini cannot be reached): [listenOnce] is the whole turn; the banner shows what was done.
 *  - Online: the first thing the driver says in a Live conversation is also heard here ([beginTap]/[onLiveChunk]); a car
 *    command is done at once, and when Gemini then calls the same command, [wasJustDone] makes it answer "done"
 *    instead of sending it again. The tap closes as soon as the assistant starts speaking, so its own voice is never
 *    taken for a command.
 */
object QuickCommands {
    private const val TAG = "QuickCommands"
    private const val PREFS = "quick_commands"
    private const val SAMPLE_RATE = 16_000f
    private const val BASE_URL = "https://alphacephei.com/vosk/models/"
    private const val LEDGER_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelLock = Mutex()
    private var model: Model? = null
    private var modelLanguage: QuickLanguage? = null

    private val _state = MutableStateFlow(QuickState(false, QuickLanguage.TUNISIAN, ModelStatus.Missing))
    val state: StateFlow<QuickState> = _state

    // ── Settings ────────────────────────────────────────────────────────────────────────────────

    fun refresh(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val language = QuickLanguage.entries.find { it.name == p.getString("language", null) } ?: QuickLanguage.TUNISIAN
        val downloading = _state.value.model is ModelStatus.Downloading && _state.value.language == language
        _state.value = QuickState(
            enabled = p.getBoolean("enabled", false),
            language = language,
            model = when {
                downloading -> _state.value.model
                modelDir(context, language) != null -> ModelStatus.Ready
                else -> ModelStatus.Missing
            },
            offlineOnly = p.getBoolean("offline_only", false),
            learned = learnedList(context).size,
        )
    }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        refresh(context)
        if (on) warmUp(context)
    }

    fun setLanguage(context: Context, language: QuickLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", language.name).apply()
        refresh(context)
        if (_state.value.enabled) warmUp(context)
    }

    /** Switches the car off from the network for this feature: every command is recognised and done on the car. */
    fun setOfflineOnly(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("offline_only", on).apply()
        refresh(context)
        if (on) warmUp(context)
    }

    /** On and with its model downloaded: offline commands can run. */
    fun isReady(context: Context): Boolean {
        refresh(context)
        return _state.value.enabled && _state.value.model == ModelStatus.Ready
    }

    /**
     * Whether this press should be done on the car rather than opening a Live conversation.
     *
     * True when offline commands are ready and either the user asked for them always, or there is no usable network.
     * Android reports a network as validated only once something has actually been reached through it, so a Wi-Fi
     * dongle that is connected but has no route out reads as no network here — which is the case worth getting right,
     * since trying Gemini on a dead link costs the driver the connection timeout before anything happens.
     *
     * Checked before the attempt, not after it fails: the fallback in [com.bydassistantng.service.WheelKeyService]
     * still catches a link that looked fine and was not.
     */
    fun shouldRunOffline(context: Context): Boolean {
        if (!isReady(context)) return false
        return _state.value.offlineOnly || !NetworkState.isOnline(context)
    }

    /** Loads the model in the background, so the first command does not wait for it. */
    fun warmUp(context: Context) {
        if (!isReady(context)) return
        scope.launch { loadModel(context) }
    }

    // ── Model files ─────────────────────────────────────────────────────────────────────────────

    private fun root(context: Context) = File(context.filesDir, "vosk")

    /** The unpacked model folder (the one holding `am/` or `conf/`), or null when it is not downloaded. */
    private fun modelDir(context: Context, language: QuickLanguage): File? {
        val base = File(root(context), language.name)
        if (!base.isDirectory) return null
        return base.walkTopDown().maxDepth(3).firstOrNull { dir ->
            dir.isDirectory && (File(dir, "am").isDirectory || File(dir, "conf/model.conf").isFile)
        }
    }

    fun deleteModel(context: Context) {
        val language = _state.value.language
        scope.launch {
            modelLock.withLock {
                if (modelLanguage == language) {
                    runCatching { model?.close() }
                    model = null
                    modelLanguage = null
                }
                File(root(context), language.name).deleteRecursively()
            }
            refresh(context)
        }
    }

    /** Downloads and unpacks the chosen language's model; progress and errors go to [state]. */
    fun download(context: Context) {
        val language = _state.value.language
        if (_state.value.model is ModelStatus.Downloading) return
        _state.value = _state.value.copy(model = ModelStatus.Downloading(0))
        scope.launch(Dispatchers.IO) {
            val zip = File(context.cacheDir, "${language.modelName}.zip")
            val target = File(root(context), language.name)
            val temp = File(root(context), language.name + ".part")
            try {
                val conn = (URL(BASE_URL + language.modelName + ".zip").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                if (conn.responseCode !in 200..299) error("server answered ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: (language.sizeMb * 1_048_576L)
                conn.inputStream.use { input ->
                    zip.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = (done * 100 / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = _state.value.copy(model = ModelStatus.Downloading(pct))
                            }
                        }
                    }
                }
                temp.deleteRecursively()
                unzip(zip, temp)
                target.deleteRecursively()
                if (!temp.renameTo(target)) error("could not move the model into place")
                AppLogger.log(TAG, "Model ${language.modelName} ready")
                _state.value = _state.value.copy(model = ModelStatus.Ready)
                warmUp(context)
            } catch (t: Throwable) {
                AppLogger.logError(TAG, "Model download failed", t)
                temp.deleteRecursively()
                _state.value = _state.value.copy(model = ModelStatus.Failed(t.message ?: t.javaClass.simpleName))
            } finally {
                zip.delete()
            }
        }
    }

    private fun unzip(zip: File, into: File) {
        val canonicalRoot = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(into, entry.name)
                if (!out.canonicalPath.startsWith(canonicalRoot)) error("bad entry in the model archive: ${entry.name}")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    private suspend fun loadModel(context: Context): Model? = modelLock.withLock {
        val language = _state.value.language
        if (model != null && modelLanguage == language) return@withLock model
        val dir = modelDir(context, language) ?: return@withLock null
        withContext(Dispatchers.IO) {
            try {
                runCatching { model?.close() }
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val started = System.currentTimeMillis()
                Model(dir.absolutePath).also {
                    model = it
                    modelLanguage = language
                    AppLogger.log(TAG, "Loaded ${language.modelName} in ${System.currentTimeMillis() - started} ms")
                }
            } catch (t: Throwable) {
                AppLogger.logError(TAG, "Could not load the offline speech model", t)
                model = null
                null
            }
        }
    }

    private fun textOf(json: String?): String =
        runCatching { JSONObject(json ?: "{}").optString("text") }.getOrDefault("").trim()

    // ── Offline turn ────────────────────────────────────────────────────────────────────────────

    /** Listens for one phrase (up to [timeoutMs]) and returns what was said, or null when nothing was understood. */
    suspend fun listenOnce(context: Context, recorder: LivePcmRecorder, timeoutMs: Long = 7_000): String? {
        val m = loadModel(context) ?: return null
        val recognizer = Recognizer(m, SAMPLE_RATE)
        val heard = CompletableDeferred<String>()
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val worker = scope.launch {
            for (chunk in chunks) {
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    val text = textOf(recognizer.result)
                    if (text.isNotEmpty()) {
                        heard.complete(text)
                        break
                    }
                }
            }
        }
        if (!recorder.start { chunks.trySend(it) }) {
            worker.cancel()
            recognizer.close()
            return null
        }
        val text = withTimeoutOrNull(timeoutMs) { heard.await() }
        recorder.stop()
        chunks.close()
        worker.join()
        val final = text ?: textOf(recognizer.finalResult).takeIf { it.isNotEmpty() }
        recognizer.close()
        AppLogger.log(TAG, "Offline heard: ${final ?: "(nothing)"}")
        return final
    }

    // ── Online tap (the first phrase of a Live conversation) ────────────────────────────────────────

    @Volatile private var tap: Channel<ByteArray>? = null
    private var tapJob: Job? = null

    fun beginTap(context: Context, controller: VehicleController) {
        endTap()
        clearPending()
        if (!isReady(context)) return
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        tap = chunks
        tapJob = scope.launch {
            val m = loadModel(context) ?: return@launch endTap()
            val recognizer = Recognizer(m, SAMPLE_RATE)
            try {
                for (chunk in chunks) {
                    if (!recognizer.acceptWaveForm(chunk, chunk.size)) continue
                    val text = textOf(recognizer.result)
                    if (text.isEmpty()) continue
                    // Only the first phrase: after it, the conversation is Gemini's.
                    endTap()
                    // The phrase book first, then what was learned from Gemini before: either runs at once.
                    val plan = QuickParser.parse(text) ?: learnedPlan(context, text)
                    AppLogger.log(TAG, "Live first phrase heard offline: \"$text\" -> ${plan?.let { it.scene?.id ?: it.actions } ?: "not a car command"}")
                    // Not understood here: whatever car command Gemini runs for it is learned for next time — except
                    // "again", which must keep meaning "the last command" rather than whatever it happened to repeat once.
                    if (plan != null) execute(context, plan, controller) else if (!QuickParser.isRepeat(text)) expectLearning(text)
                    break
                }
            } finally {
                recognizer.close()
            }
        }
    }

    /** Called for every mic chunk of a Live conversation; free when no tap is open. */
    fun onLiveChunk(chunk: ByteArray) {
        tap?.trySend(chunk)
    }

    /** The assistant started talking: stop listening here, so its voice is never taken for a command. */
    fun assistantSpeaking() {
        if (tap != null) endTap()
    }

    fun endTap() {
        tap?.close()
        tap = null
    }

    // ── Doing it ────────────────────────────────────────────────────────────────────────────────

    private val ledger = HashMap<String, Long>()

    /** The last offline plan that was run, so "again"/"عاود" can repeat it without the driver saying it all over. */
    @Volatile private var lastPlan: QuickPlan? = null

    /** The last plan run offline, or null when nothing has been done yet this run. */
    fun lastPlan(): QuickPlan? = lastPlan

    // ── Learned from Gemini (see [LearnedCommands]) ─────────────────────────────────────────────────

    // A Gemini command only belongs to the phrase if it comes soon after it, in the same exchange: the answer to a
    // follow-up question ("which window?") is not what the first words meant on their own.
    private const val LEARN_WINDOW_MS = 8_000L

    private class Pending(val phrase: String, val atMs: Long, val turn: Long) {
        /** The user turn whose commands were learned for this phrase; later turns are not this phrase's. */
        var learnedTurn: Long? = null
    }

    @Volatile private var learned: List<LearnedCommand>? = null
    private var pending: Pending? = null

    // Counts the user's turns in a Live conversation (each "user stopped speaking"), to tell one exchange from the next.
    @Volatile private var turnId = 0L

    @Synchronized
    private fun learnedList(context: Context): List<LearnedCommand> =
        learned ?: LearnedCommands.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("learned", null))
            .also { learned = it }

    @Synchronized
    private fun saveLearned(context: Context, list: List<LearnedCommand>) {
        learned = list
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("learned", LearnedCommands.encode(list)).apply()
    }

    /** What the driver taught for [heard] before, or null. Counts the use. */
    fun learnedPlan(context: Context, heard: String): QuickPlan? {
        val list = learnedList(context)
        val entry = LearnedCommands.match(heard, list) ?: return null
        val plan = LearnedCommands.plan(entry) ?: return null
        saveLearned(context, LearnedCommands.markUsed(list, entry.phrase))
        AppLogger.log(TAG, "Learned phrase matched: \"$heard\" ~ \"${entry.phrase}\" -> ${entry.scene ?: entry.actions}")
        return plan
    }

    /** Forgets every phrase learned from Gemini (Settings), for when one was learned wrong. */
    fun clearLearned(context: Context) {
        saveLearned(context, emptyList())
        clearPending()
        refresh(context)
    }

    @Synchronized
    private fun clearPending() {
        pending = null
    }

    @Synchronized
    private fun expectLearning(heard: String) {
        pending = Pending(heard, now(), turnId)
    }

    /** The user finished a turn of a Live conversation. */
    fun onUserTurnEnded() {
        turnId++
    }

    /**
     * Gemini ran [commandId] = [value] (or the scene [scene]): if the first phrase of this conversation was heard on the car
     * and not understood there, the two are kept together so the phrase works offline from now on.
     */
    @Synchronized
    fun learnFromGemini(context: Context, commandId: String?, value: String?, scene: String?) {
        val p = pending ?: return
        val turn = turnId
        // Too late, or a later exchange: not what the phrase meant.
        if (now() - p.atMs > LEARN_WINDOW_MS || turn - p.turn > 1 || (p.learnedTurn != null && p.learnedTurn != turn)) {
            pending = null
            return
        }
        val action = if (commandId != null && value != null) commandId to value else null
        val before = learnedList(context)
        val after = LearnedCommands.learn(before, p.phrase, action, scene, System.currentTimeMillis(), replace = p.learnedTurn == null)
        if (after === before) return
        p.learnedTurn = turn
        saveLearned(context, after)
        AppLogger.log(TAG, "Learned for offline: \"${p.phrase}\" -> ${scene ?: action}")
        _state.value = _state.value.copy(learned = after.size)
    }

    private fun now() = System.nanoTime() / 1_000_000

    /** Whether this exact command was done here a moment ago (Gemini is then told it is done, not sent again). */
    @Synchronized
    fun wasJustDone(commandId: String, value: Int): Boolean {
        val at = ledger["$commandId=$value"] ?: return false
        return now() - at in 0..LEDGER_MS
    }

    @Synchronized
    private fun remember(commandId: String, value: Int) {
        val t = now()
        ledger.entries.removeAll { t - it.value > LEDGER_MS }
        ledger["$commandId=$value"] = t
    }

    /** What [plan] will do, in the app's language, for the banner before it is done: "Window (driver): open". */
    fun describe(context: Context, plan: QuickPlan): String {
        plan.scene?.let { return it.label }
        val lang = runCatching { AppLanguage.locale(context).language }.getOrDefault("en")
        return plan.actions.mapNotNull { (id, word) ->
            VehicleCommandRegistry.byId(id)?.let { "${it.labelFor(lang)}: ${word.replace('_', ' ')}" }
        }.joinToString("  ·  ")
    }

    /**
     * Sends every action of [plan] to the car; says what happened in one line for the banner.
     *
     * [force] runs each action even if it was done a moment ago (the dedupe ledger is skipped): a repeat ("again")
     * is a deliberate ask to do the same thing once more, not the broker redelivering the same command.
     */
    suspend fun execute(context: Context, plan: QuickPlan, controller: VehicleController, force: Boolean = false): QuickOutcome {
        val lang = runCatching { AppLanguage.locale(context).language }.getOrDefault("en")
        if (plan.allActions.isNotEmpty()) lastPlan = plan
        var allOk = true
        val parts = mutableListOf<String>()
        for ((id, word) in plan.allActions) {
            val command = VehicleCommandRegistry.byId(id) ?: continue
            val value = VehicleCommandRegistry.resolveValue(command, word) ?: continue
            if (!force && wasJustDone(id, value)) continue
            val result = try {
                (if (!command.needsCarLink) InAppVehicleController(context) else controller).dispatch(command, value)
            } catch (t: Throwable) {
                VehicleDispatchResult.Blocked(t.message ?: "blocked")
            }
            val ok = result is VehicleDispatchResult.Success
            if (ok) remember(id, value) else allOk = false
            AppLogger.log(TAG, "quick $id=$word -> $result")
            if (plan.scene == null) parts += "${if (ok) "✓" else "✗"} ${command.labelFor(lang)}: ${word.replace('_', ' ')}"
        }
        val summary = plan.scene?.let { "${if (allOk) "✓" else "⚠"} ${it.label}" } ?: parts.joinToString("  ")
        return QuickOutcome(allOk, summary.ifEmpty { "✓" })
    }
}
