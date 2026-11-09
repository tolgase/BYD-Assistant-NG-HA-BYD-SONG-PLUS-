package com.bydassistantng.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.annotation.StringRes
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bydassistantng.audio.AudioRecorder
import com.bydassistantng.audio.Earcon
import com.bydassistantng.audio.Earcons
import com.bydassistantng.audio.LivePcmRecorder
import com.bydassistantng.quick.QuickCommands
import com.bydassistantng.quick.QuickParser
import com.bydassistantng.drive.DriveMonitor
import com.bydassistantng.drive.DriveTipPrompt
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import com.bydassistantng.vehicle.ShellHelperVehicleController
import com.bydassistantng.audio.VoiceSpeaker
import com.bydassistantng.audio.TextToSpeechEngine
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.gemini.ConversationController
import com.bydassistantng.gemini.LiveConversationController
import com.bydassistantng.gemini.StartFailure
import com.bydassistantng.gemini.TurnStatus
import com.bydassistantng.greeting.GreetResult
import com.bydassistantng.greeting.GreetingHost
import com.bydassistantng.ui.EXTRA_REQUEST_MIC_PERMISSION
import com.bydassistantng.ui.MainActivity
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.util.WheelKeys
import com.bydassistantng.util.hasMicPermission
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TAG = "WheelKeyService"
private const val RECORDED_AUDIO_MIME_TYPE = "audio/m4a"
private const val CLASSIC_ANSWER_SHOWN_MS = 8_000L
private const val OFFLINE_SHOWN_MS = 8_000L

/**
 * Starts and stops a voice turn from the steering-wheel mic button, and hosts that turn.
 *
 * This is how DiLink button-mapper apps do it: an accessibility service that asks for `flagRequestFilterKeyEvents`, so the system hands it
 * every hardware key before any app sees it. Returning true from [onKeyEvent] consumes the key.
 * Being an accessibility service also means the system binds it by itself at boot and keeps it
 * alive, so there is no foreground service, boot receiver or overlay permission to maintain — and
 * the platform lets accessibility services capture the mic while another app is in front.
 *
 * Which key triggers is a preference (default: the mic button's short press); see [WheelKeys].
 * Anything else listening on the same key (e.g. a button-mapper's own mapping) still fires too —
 * accessibility services are all notified — so that mapping has to be removed there.
 */
@AndroidEntryPoint
class WheelKeyService : AccessibilityService(), GreetingHost {

    @Inject lateinit var preferencesRepository: PreferencesRepository
    @Inject lateinit var audioRecorder: AudioRecorder
    @Inject lateinit var conversationController: ConversationController
    @Inject lateinit var liveConversationController: LiveConversationController
    @Inject lateinit var ttsEngine: TextToSpeechEngine
    @Inject lateinit var earcons: Earcons
    // Song PLUS edition: offline instant commands.
    @Inject lateinit var quickRecorder: LivePcmRecorder
    @Inject lateinit var vehicleController: ShellHelperVehicleController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    internal val banner by lazy { StatusBanner(this) }
    private var startJob: Job? = null

    @Volatile private var triggerKeyCode = WheelKeys.MIC_SHORT_PRESS

    // The status banner and the messages follow the app language, which can differ from the device's.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /** A string in the app language as it is now (it may have been changed since this service started). */
    private fun text(@StringRes id: Int, vararg args: Any): String = AppLanguage.string(this, id, *args)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        scope.launch { preferencesRepository.triggerKeyCode.collect { triggerKeyCode = it } }
        // Load the offline speech model now, so the first command does not wait for it.
        QuickCommands.warmUp(this)
        // Song PLUS edition: follow the drive for the drive coach (in memory only; skipped during a conversation).
        DriveMonitor.start(
            scope = scope,
            enabled = { preferencesRepository.driveCoach.first() && preferencesRepository.vehicleControlEnabled.first() },
            // Nor during an offline command: a status read in front of it would make the car do it late.
            busy = { liveConversationController.inConversation || offlineTurnActive },
            read = { (vehicleController.query(VehicleQuery.CAR_STATUS) as? VehicleDispatchResult.Success)?.note },
            tipsEnabled = { preferencesRepository.driveTips.first() },
            // Said like the greeting: same speaker (the driver's headrest where the car has one), same language.
            speak = { tip ->
                val wanted = preferencesRepository.greetingSpeaker.first()
                val speaker = if (wanted == VoiceSpeaker.HEADREST && preferencesRepository.headrestSpeakerPresent.first() == false) {
                    VoiceSpeaker.CAR
                } else {
                    wanted
                }
                val language = if (preferencesRepository.assistantLanguage.first() != AssistantLanguage.AUTO) null else AppLanguage.spokenName(this)
                greet(DriveTipPrompt.build(tip, language), speaker) == GreetResult.STARTED
            },
        )
        AppLogger.log(TAG, "Connected, watching key $triggerKeyCode")
    }

    // Key filtering is all this service is for; it has no use for window/content events.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        keyCaptureListener?.let { listener ->
            // "Press the button you want to use": takes the next key instead of acting on it.
            if (event.action == KeyEvent.ACTION_DOWN) {
                keyCaptureListener = null
                AppLogger.log(TAG, "Captured key ${event.keyCode} for the trigger setting")
                listener(event.keyCode)
            }
            return true
        }

        if (event.keyCode != triggerKeyCode) return false
        // Consumed on both down and up so nothing else reacts to half of the press; only the
        // initial down starts something (holding the button would otherwise auto-repeat).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            AppLogger.log(TAG, "Trigger key ${event.keyCode} pressed")
            onTriggerPressed()
        }
        return true
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        keyCaptureListener = null
        handler.removeCallbacksAndMessages(null)
        banner.release()
        audioRecorder.cancel()
        liveConversationController.close()
        DriveMonitor.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ── Greeting ─────────────────────────────────────────────────────────────────────────────

    override val busy: Boolean
        get() = liveConversationController.inConversation || audioRecorder.isRecording || startJob?.isActive == true

    /**
     * Opens a conversation with the assistant speaking first (see [com.bydassistantng.greeting.GreetingCoordinator]).
     * Nobody asked for it, so a failure is only logged: no error tone, no banner left behind.
     */
    override suspend fun greet(opening: String, speaker: VoiceSpeaker): GreetResult = withContext(Dispatchers.Main) {
        if (busy) return@withContext GreetResult.NO
        if (!hasMicPermission()) {
            AppLogger.log(TAG, "Greeting skipped: the microphone permission is off")
            return@withContext GreetResult.NO
        }
        if (!preferencesRepository.onboardingCompleted.first()) return@withContext GreetResult.NO
        banner.show(BannerMode.CONNECTING)
        // Registered as the turn being started, so a press while it connects is ignored as for any other start.
        val start = scope.async { liveConversationController.start(opening, speaker) { status -> handleStatus(status) } }
        startJob = start
        if (start.await()) return@withContext GreetResult.STARTED
        banner.hide()
        when (liveConversationController.lastStartFailure) {
            StartFailure.NO_API_KEY, StartFailure.API_KEY_REJECTED -> GreetResult.NO
            else -> GreetResult.LATER
        }
    }

    // ── Voice turn ───────────────────────────────────────────────────────────────────────────

    /**
     * A press starts a conversation, or ends the one in progress — whatever the assistant is doing at
     * that moment (listening, thinking or speaking). Decided from what is actually running rather than
     * a separately tracked flag, since a conversation can also end on its own (silence, "stop").
     */
    private fun onTriggerPressed() {
        when {
            liveConversationController.inConversation -> liveConversationController.endConversation()
            audioRecorder.isRecording -> {
                val file = audioRecorder.stop()
                if (file == null) signalFailure(text(R.string.err_no_audio)) else runClassicTurn(file)
            }
            // Connecting takes up to a few seconds; a second press in that window must not start a
            // second session on top of the first.
            startJob?.isActive == true -> AppLogger.log(TAG, "Press ignored: a turn is still connecting")
            else -> startNewTurn()
        }
    }

    private fun startNewTurn() {
        // A service can't show a runtime-permission dialog, so if the grant has lapsed (a one-time
        // "Only this time" grant auto-revokes — seen on a head unit) the only thing that can
        // fix it is the app's own screen. Say so and try to open it, rather than failing with a
        // vague "could not start recording".
        if (!hasMicPermission()) {
            signalFailure(text(R.string.err_mic_off))
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(EXTRA_REQUEST_MIC_PERMISSION, true)
                    },
                )
            }.onFailure { AppLogger.logError(TAG, "Could not open the app to request the mic permission", it) }
            return
        }

        // Straight away, so the press is acknowledged before the connection (up to a few seconds) is up.
        banner.show(BannerMode.CONNECTING)
        startJob = scope.launch {
            if (!preferencesRepository.onboardingCompleted.first()) {
                signalFailure(text(R.string.err_finish_setup))
                return@launch
            }

            // Song PLUS edition: no point asking Gemini first when the car is on its own, or when the user has asked
            // for every command to be done on the car. Trying would cost the connection timeout before the window
            // even opens, which on a Wi-Fi dongle with no route out is several seconds of nothing happening.
            if (runOfflineTurnIfChosen()) return@launch

            val liveStarted = liveConversationController.start { status -> handleStatus(status) }
            if (liveStarted) {
                // The Live controller plays the listening tone itself (and the others).
                banner.show(BannerMode.LISTENING)
                return@launch
            }

            // Song PLUS edition: no Gemini (no internet, or it could not be reached) — car commands still work offline.
            if (QuickCommands.isReady(this@WheelKeyService) && preferencesRepository.vehicleControlEnabled.first()) {
                AppLogger.log(TAG, "Live unavailable (${liveConversationController.lastStartFailure}) — offline instant commands")
                runOfflineTurn()
                return@launch
            }

            // Some failures have an answer the classic pipeline can't improve on — it needs the same
            // internet and key, and would first record 15 seconds of the user talking before saying so.
            val failure = liveConversationController.lastStartFailure
            if (failure != StartFailure.OTHER && failure != StartFailure.NONE) {
                AppLogger.log(TAG, "Live unavailable ($failure) — telling the user instead of falling back")
                signalFailure(liveConversationController.lastStartMessage, offline = failure == StartFailure.NO_NETWORK)
                return@launch
            }

            // The older mode answers in text, which is only heard through the platform's speech engine. A unit without one (this
            // head unit has none) would record the driver for several seconds and then say nothing: say it could not connect.
            if (!ttsEngine.canSpeak()) {
                AppLogger.log(TAG, "Live unavailable ($failure) and this unit cannot speak a text — telling the driver instead of recording")
                signalFailure(liveConversationController.lastStartMessage)
                return@launch
            }

            AppLogger.log(TAG, "Live unavailable this turn, falling back to the classic pipeline")
            val file = audioRecorder.start(onMaxDurationReached = { onTriggerPressed() }, onSilenceDetected = { onTriggerPressed() })
            if (file == null) {
                signalFailure(text(R.string.err_cant_record))
                return@launch
            }
            earcons.play(Earcon.LISTENING)
            banner.show(BannerMode.LISTENING, labelOverride = text(R.string.banner_listening_press_to_stop))
        }
    }

    /**
     * Song PLUS edition: runs this turn on the car when that is what it should be, and says whether it did.
     *
     * The same decision for the wheel button and for the app's own microphone, so the two never behave differently —
     * which matters most when the user has switched the feature to always use the car, and is sitting in the app
     * wondering why a tap still waits for Gemini. The banner is the service's, so it is shown over the app as well.
     */
    suspend fun runOfflineTurnIfChosen(): Boolean {
        if (!QuickCommands.shouldRunOffline(this)) return false
        if (!preferencesRepository.vehicleControlEnabled.first()) return false
        AppLogger.log(TAG, "Offline instant commands chosen without trying Live")
        runOfflineTurn()
        return true
    }

    /** One offline car command: listen, recognise on the car, do it, and show what was done on the banner. */
    @Volatile private var offlineTurnActive = false

    private suspend fun runOfflineTurn() {
        offlineTurnActive = true
        try {
            runOfflineTurnInner()
        } finally {
            offlineTurnActive = false
        }
    }

    private suspend fun runOfflineTurnInner() {
        // Start the car link while the driver is still speaking, so the command goes out the moment it is understood
        // instead of waiting a few seconds for the helper to start.
        vehicleController.warmUp()
        earcons.play(Earcon.LISTENING)
        banner.show(BannerMode.LISTENING, labelOverride = text(R.string.quick_listening))
        val heard = QuickCommands.listenOnce(this, quickRecorder)
        if (heard.isNullOrBlank()) {
            earcons.play(Earcon.ERROR)
            banner.show(BannerMode.ERROR, labelOverride = text(R.string.quick_not_heard), shownMs = 4_000)
            return
        }
        // "Again"/"عاود" repeats the last command without the driver saying it all over; force past the dedupe ledger.
        val repeat = QuickParser.isRepeat(heard)
        // The phrase book, then "again", then the driver's own phrases learned from Gemini online.
        val plan = QuickParser.parse(heard)
            ?: (if (repeat) QuickCommands.lastPlan() else null)
            ?: (if (repeat) null else QuickCommands.learnedPlan(this, heard))
        if (plan == null) {
            earcons.play(Earcon.OFFLINE)
            val label = if (repeat) text(R.string.quick_nothing_to_repeat) else text(R.string.quick_not_command, heard)
            banner.show(BannerMode.ERROR, labelOverride = label, shownMs = 5_000)
            return
        }
        // Confirm what was understood before doing it: what was heard, and what the car is about to do.
        val doing = QuickCommands.describe(this, plan)
        AppLogger.log(TAG, "Offline: \"$heard\" -> $doing")
        banner.show(BannerMode.THINKING, labelOverride = "« $heard » → $doing")
        // The driver asked for it just now, so it is always sent: the 20-second duplicate check is only for Gemini
        // repeating a command the car already did, and here it would silently skip a retry of one that did not work.
        val outcome = QuickCommands.execute(this, plan, vehicleController, force = true)
        earcons.play(if (outcome.ok) Earcon.DONE else Earcon.ERROR)
        banner.show(if (outcome.ok) BannerMode.SPEAKING else BannerMode.ERROR, labelOverride = "« $heard »  ${outcome.summary}", shownMs = 6_000)
    }

    private fun runClassicTurn(file: File) {
        earcons.play(Earcon.THINKING)
        banner.show(BannerMode.THINKING)
        scope.launch {
            // Speaking happens AFTER runTurn() returns, never from inside its callback: the
            // callback runs while ConversationController's turn mutex is still held, and a hung TTS
            // engine must never be able to block every subsequent voice turn behind it.
            var toSpeak: TurnStatus.Speaking? = null
            conversationController.runTurn(file, RECORDED_AUDIO_MIME_TYPE) { status ->
                handleStatus(status)
                if (status is TurnStatus.Speaking) toSpeak = status
            }
            file.delete()
            // The classic path has no "done" event: leave the answer up long enough to read, then clear it.
            handler.postDelayed({ banner.hide() }, CLASSIC_ANSWER_SHOWN_MS)
            // The classic path only ever gets text back — Live produces real audio output itself,
            // so only this fallback path still needs a local TTS call.
            toSpeak?.let { ttsEngine.speak(it.text, it.language) }
        }
    }

    /** Mirrors what the assistant is doing onto the banner. Called from background threads. */
    private fun handleStatus(status: TurnStatus) {
        when (status) {
            TurnStatus.Listening -> banner.show(BannerMode.LISTENING)
            TurnStatus.Thinking -> banner.show(BannerMode.THINKING)
            is TurnStatus.DispatchingCommand -> banner.show(BannerMode.THINKING, labelOverride = status.displayName)
            is TurnStatus.Speaking -> banner.show(BannerMode.SPEAKING, detail = status.text)
            // The controller already played its error tone for a Live failure.
            is TurnStatus.Failed -> banner.show(BannerMode.ERROR, detail = status.message)
            TurnStatus.Done -> banner.hide()
        }
    }

    // A driver can't be expected to look at the screen, so failures get a tone as well as the banner.
    private fun signalFailure(message: String, offline: Boolean = false) {
        earcons.play(if (offline) Earcon.OFFLINE else Earcon.ERROR)
        // Longer when it says what to do about it: a driver glances at this, and has to read two languages.
        banner.show(BannerMode.ERROR, detail = message, shownMs = if (offline) OFFLINE_SHOWN_MS else null)
    }

    companion object {
        @Volatile var instance: WheelKeyService? = null
            private set

        @Volatile private var keyCaptureListener: ((Int) -> Unit)? = null

        /** Hands [onKey] the next hardware key pressed (and swallows it) instead of triggering a
         * turn — the "press the button you want to use" flow in Settings. Runs on the main thread.
         * Returns false if the service isn't running, in which case no key can be seen at all. */
        fun beginKeyCapture(onKey: (Int) -> Unit): Boolean {
            if (instance == null) return false
            keyCaptureListener = onKey
            return true
        }

        fun cancelKeyCapture() {
            keyCaptureListener = null
        }
    }
}
