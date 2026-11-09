package com.bydassistantng.gemini

import android.content.Context
import com.bydassistantng.R
import com.bydassistantng.apps.AppLauncherTool
import com.bydassistantng.apps.CloseAppTool
import com.bydassistantng.audio.PlaySoundOutsideTool
import com.bydassistantng.audio.SoundEffect
import com.bydassistantng.audio.SpeakOutsideTool
import com.bydassistantng.memory.ForgetMemoryTool
import com.bydassistantng.memory.MemoryContext
import com.bydassistantng.memory.MemoryFacts
import com.bydassistantng.memory.RecentExchanges
import com.bydassistantng.memory.RememberTool
import com.bydassistantng.data.ArabicDialect
import com.bydassistantng.data.AssistantIdentity
import com.bydassistantng.data.AssistantLanguage
import com.bydassistantng.media.MediaControlTool
import com.bydassistantng.media.PlayMediaTool
import com.bydassistantng.navigation.NavigationTool
import com.bydassistantng.navigation.ReadNavigationTool
import com.bydassistantng.location.CarLocationTool
import com.bydassistantng.drive.DriveReportTool
import com.bydassistantng.drive.RideContext
import com.bydassistantng.drive.RideStyle
import com.bydassistantng.quick.QuickCommands
import com.bydassistantng.quick.SceneTool
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.util.AppLogger
import com.bydassistantng.vehicle.LoggingVehicleController
import com.bydassistantng.vehicle.VolumeTool
import com.bydassistantng.passenger.PassengerScreenTool
import com.bydassistantng.vehicle.InAppVehicleController
import com.bydassistantng.vehicle.ReadCarTool
import com.bydassistantng.vehicle.ConsumptionHistory
import com.bydassistantng.vehicle.needsCarLink
import com.bydassistantng.vehicle.SetDriveModeTool
import com.bydassistantng.cluster.ClusterOptions
import com.bydassistantng.cluster.ClusterScreenTool
import com.bydassistantng.vehicle.CommandAnswers
import com.bydassistantng.vehicle.CommandLookup
import com.bydassistantng.vehicle.VehicleCommandRegistry
import com.bydassistantng.vehicle.valuesText
import com.bydassistantng.vehicle.VehicleController
import com.bydassistantng.vehicle.VehicleDispatchError
import com.bydassistantng.vehicle.VehicleDispatchResult
import com.bydassistantng.vehicle.VehicleQuery
import com.bydassistantng.vehicle.VehicleRepeatGuard
import com.bydassistantng.vehicle.labelFor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "VehicleDispatch"

/** The media action as the banner words it, in the app language (the model's own word if unknown). */
private fun mediaActionText(context: Context, action: String): String = when (action) {
    "play" -> AppLanguage.string(context, R.string.media_play)
    "pause" -> AppLanguage.string(context, R.string.media_pause)
    "next" -> AppLanguage.string(context, R.string.media_next)
    "previous" -> AppLanguage.string(context, R.string.media_previous)
    "stop" -> AppLanguage.string(context, R.string.media_stop)
    else -> action
}

/** The functions the model may call this turn. Navigation, opening apps and media keys are always offered
 * (they only open an app or press a media key); the vehicle commands only when the user has explicitly
 * armed vehicle control — without that, the model structurally cannot emit a call to one. With
 * [webSearch] the model may also look things up on Google (a Live API tool, answered by the server).
 * [exteriorSpeaker] offers speaking through the car's exterior speaker, in a conversation with vehicle
 * control armed and only in a build that has one; [soundEffects] are the ready-made sounds it can play.
 * [memoryTools] offers remembering and forgetting facts, in a conversation with long-term memory on.
 * [driveModes] offers changing the car's drive mode, like the exterior speaker only in a build that can.
 * [clusterScreen] offers putting an app on the instrument cluster (experimental: a car that has it, with its
 * switch on). */
fun assistantTools(
    vehicleControlEnabled: Boolean,
    conversation: Boolean = false,
    webSearch: Boolean = false,
    exteriorSpeaker: Boolean = false,
    soundEffects: List<SoundEffect> = emptyList(),
    memoryTools: Boolean = false,
    driveModes: Boolean = false,
    clusterScreen: Boolean = false,
): List<GeminiTool> {
    val declarations = buildList {
        add(NavigationTool.declaration)
        // Reads the maps app's own route (distance left, arrival): like navigation, it never touches the car.
        add(ReadNavigationTool.declaration)
        // Where the car is, for the weather and "near me": never touches the car either.
        add(CarLocationTool.declaration)
        add(AppLauncherTool.declaration)
        add(CloseAppTool.declaration)
        add(MediaControlTool.declaration)
        add(PlayMediaTool.declaration)
        add(VolumeTool.declaration)
        if (conversation) add(endConversationDeclaration)
        // Only when the user has long-term memory switched on, in a conversation.
        if (conversation && memoryTools) {
            add(RememberTool.declaration)
            add(ForgetMemoryTool.declaration)
        }
        // The commands that are plain Android calls (the media volume) are offered whatever the switch says; the
        // ones that reach the car's own systems through the ADB helper only when vehicle control is armed.
        addAll(VehicleCommandRegistry.functionDeclarations(carLink = vehicleControlEnabled))
        if (vehicleControlEnabled) {
            add(ReadCarTool.declaration)
            // The drive as the car reported it while it was on: the same switch as reading the car.
            add(DriveReportTool.declaration)
            add(SceneTool.declaration)
            add(PassengerScreenTool.declaration)
            // Needs a build that can switch the exterior speaker, and counts as controlling the car.
            if (conversation && exteriorSpeaker) {
                add(SpeakOutsideTool.declaration)
                if (soundEffects.isNotEmpty()) add(PlaySoundOutsideTool.declaration(soundEffects))
            }
            // Needs a build that can ask the car for a drive mode, and counts as controlling the car.
            if (conversation && driveModes) add(SetDriveModeTool.declaration)
            // An experimental feature with its Advanced settings switch on; it turns BYD Maps on and off by itself.
            if (conversation && clusterScreen) add(ClusterScreenTool.declaration)
        }
    }
    return buildList {
        add(GeminiTool(functionDeclarations = declarations))
        if (webSearch) add(GeminiTool(googleSearch = JsonObject(emptyMap())))
    }
}

/** Shared between [ConversationController] (classic request/response) and
 * [LiveConversationController] (WebSocket session) — both need to resolve a Gemini function call
 * and dispatch it identically; only how they get the call and send back the response differs by
 * protocol. Navigation is handled here; everything else is a
 * [com.bydassistantng.vehicle.VehicleCommand]. */
object VehicleFunctionDispatcher {
    // The model sometimes repeats a command it was told was accepted; the car gets it once (see VehicleRepeatGuard).
    private val repeatGuard = VehicleRepeatGuard()

    /** The user asked something new: asking for the same command again is on purpose, not a repeat. */
    fun newUserTurn() {
        repeatGuard.reset()
        // Song PLUS edition: lets the offline layer tell this exchange's commands from the next one's.
        QuickCommands.onUserTurnEnded()
    }

    suspend fun dispatch(
        functionCall: GeminiFunctionCall,
        vehicleControlEnabled: Boolean,
        reflectionController: VehicleController,
        context: Context,
        cluster: ClusterOptions = ClusterOptions.OFF,
        onDispatching: suspend (displayName: String) -> Unit,
    ): JsonObject {
        if (functionCall.name == NavigationTool.FUNCTION_NAME) {
            return NavigationTool.handle(context, functionCall, onDispatching)
        }

        if (functionCall.name == AppLauncherTool.FUNCTION_NAME) {
            // An app that is on the instrument cluster has its gauges put back before it is taken off.
            return AppLauncherTool.handle(
                context, functionCall, onDispatching,
                beforeLaunch = { pkg -> ClusterScreenTool.leaving(reflectionController, pkg) },
            )
        }
        if (functionCall.name == CloseAppTool.FUNCTION_NAME) {
            return CloseAppTool.handle(
                context, functionCall, onDispatching,
                closedElsewhere = { pkg -> ClusterScreenTool.closeIfOnCluster(reflectionController, pkg, cluster) },
            )
        }
        if (functionCall.name == PlayMediaTool.FUNCTION_NAME) {
            return PlayMediaTool.handle(context, functionCall, onDispatching)
        }
        if (functionCall.name == MediaControlTool.FUNCTION_NAME) {
            val action = (functionCall.args["action"] as? JsonPrimitive)?.content.orEmpty()
            onDispatching(AppLanguage.string(context, R.string.banner_media, mediaActionText(context, action)))
            return MediaControlTool.handle(context, functionCall)
        }

        if (functionCall.name == SceneTool.FUNCTION_NAME) {
            return SceneTool.handle(context, functionCall, reflectionController, vehicleControlEnabled, onDispatching).also { answer ->
                // Song PLUS edition: a scene Gemini ran for words the car did not understand is learned for offline use.
                if ((answer["status"] as? JsonPrimitive)?.content == "ok") {
                    (functionCall.args["scene"] as? JsonPrimitive)?.content?.let { QuickCommands.learnFromGemini(context, null, null, it) }
                }
            }
        }

        if (functionCall.name == ReadNavigationTool.FUNCTION_NAME) {
            return ReadNavigationTool.handle(context, onDispatching)
        }
        if (functionCall.name == CarLocationTool.FUNCTION_NAME) {
            return CarLocationTool.handle(context, onDispatching)
        }
        if (functionCall.name == DriveReportTool.FUNCTION_NAME) {
            if (!vehicleControlEnabled) {
                return buildJsonObject {
                    put("status", "error")
                    put("error", "Vehicle features are switched off in the app's settings.")
                }
            }
            return DriveReportTool.handle(context, functionCall, onDispatching)
        }

        if (functionCall.name == ReadCarTool.FUNCTION_NAME) {
            return readCar(functionCall, vehicleControlEnabled, reflectionController, context, onDispatching)
        }

        if (functionCall.name == PassengerScreenTool.FUNCTION_NAME) {
            return PassengerScreenTool.handle(
                context, functionCall, reflectionController, vehicleControlEnabled, onDispatching,
                beforeLaunch = { pkg -> ClusterScreenTool.leaving(reflectionController, pkg) },
            )
        }
        if (functionCall.name == ClusterScreenTool.FUNCTION_NAME) {
            return ClusterScreenTool.handle(context, functionCall, reflectionController, vehicleControlEnabled, cluster, onDispatching)
        }
        if (functionCall.name == VolumeTool.FUNCTION_NAME) {
            onDispatching(AppLanguage.string(context, R.string.banner_volume))
            return VolumeTool.read(context)
        }
        // A vehicle control is a family function (`seat`, `window`, …) naming one of its settings.
        val setting = (functionCall.args["setting"] as? JsonPrimitive)?.content
        val command = when (val lookup = VehicleCommandRegistry.resolve(functionCall.name, setting, vehicleControlEnabled)) {
            is CommandLookup.Found -> lookup.command
            CommandLookup.UnknownFunction -> return buildJsonObject { put("error", "Unknown command '${functionCall.name}'") }
            is CommandLookup.UnknownSetting -> return buildJsonObject {
                put("status", "error")
                put("error", "INVALID_ARGUMENT")
                val available = lookup.settings.joinToString(", ")
                put(
                    "detail",
                    when {
                        lookup.settings.isEmpty() -> "${functionCall.name} is not available."
                        setting.isNullOrBlank() -> "${functionCall.name} needs a setting: $available."
                        else -> "Unknown setting '$setting' for ${functionCall.name}. Settings: $available."
                    },
                )
            }
        }

        onDispatching(command.labelFor(AppLanguage.locale(context).language))

        val rawValue = (functionCall.args["value"] as? JsonPrimitive)?.content
        val resolvedValue = VehicleCommandRegistry.resolveValue(command, rawValue)
        val result = if (resolvedValue == null) {
            VehicleDispatchResult.Failure(
                VehicleDispatchError.INVALID_ARGUMENT,
                "Could not resolve value '$rawValue' for ${command.id}. Use: ${command.valuesText()}.",
            )
        } else {
            // A functionCall reaching here at all already implies vehicleControlEnabled was true
            // when tools were declared — this check is a second, independent gate against that
            // same fact, the same "two layers, not one" posture VehicleSafety itself documents,
            // rather than a currently-reachable branch.
            val controller = when {
                // A plain Android call: no ADB link and no vehicle-control switch involved.
                !command.needsCarLink -> InAppVehicleController(context)
                vehicleControlEnabled -> reflectionController
                else -> LoggingVehicleController
            }
            // Song PLUS edition: the offline layer may already have done this, from the same words, a moment ago.
            if (QuickCommands.wasJustDone(command.id, resolvedValue)) {
                VehicleDispatchResult.Success("Already done instantly from the driver's words. Do not send it again; tell the user it is done in two or three words.")
            } else repeatGuard.dispatch(command.id, resolvedValue) {
                try {
                    controller.dispatch(command, resolvedValue)
                } catch (e: IllegalStateException) {
                    // VehicleSafety.assertDispatchAllowed rejecting a blocked domain — unreachable in
                    // practice since blocked domains are never in the registry Gemini was given
                    // functions for, but the hard gate stays authoritative over that assumption.
                    VehicleDispatchResult.Blocked(e.message ?: "blocked")
                }
            }
        }
        AppLogger.log(TAG, "dispatch ${command.id}(value=$rawValue -> $resolvedValue) -> $result")
        // Song PLUS edition: a command the car itself accepted, for words it did not understand offline, is learned.
        if (result is VehicleDispatchResult.Success && resolvedValue != null && rawValue != null) {
            QuickCommands.learnFromGemini(context, command.id, rawValue, null)
        }
        return result.toResponseJson()
    }

    /** Reading the car's sensors is gated by the same switch as control: the helper is the same ADB-shell power. */
    private suspend fun readCar(
        call: GeminiFunctionCall,
        vehicleControlEnabled: Boolean,
        controller: VehicleController,
        context: Context,
        onDispatching: suspend (String) -> Unit,
    ): JsonObject {
        val what = ReadCarTool.what(call)
            ?: return buildJsonObject {
                put("status", "error")
                put("error", "Unknown reading. Use one of: ${ReadCarTool.What.entries.joinToString { it.word }}.")
            }
        val banner = when (what) {
            ReadCarTool.What.TYRES -> R.string.banner_tyres
            ReadCarTool.What.OUTSIDE_TEMPERATURE -> R.string.banner_outside_temperature
            ReadCarTool.What.INSIDE_TEMPERATURE -> R.string.banner_inside_temperature
            ReadCarTool.What.AIR_QUALITY -> R.string.banner_air_quality
            ReadCarTool.What.STATUS -> R.string.banner_car_status
        }
        onDispatching(AppLanguage.string(context, banner))
        if (!vehicleControlEnabled) {
            return buildJsonObject {
                put("status", "error")
                put("error", "Vehicle features are switched off in the app's settings.")
            }
        }
        return when (val result = controller.query(what.query)) {
            is VehicleDispatchResult.Success -> {
                AppLogger.log(TAG, "read_car ${what.word} -> ${result.note}")
                // Song PLUS edition: the status carries the earlier energy readings too, for consumption questions.
                val note = if (what == ReadCarTool.What.STATUS) ConsumptionHistory.attach(context, result.note) else result.note
                ReadCarTool.report(what, note) ?: buildJsonObject {
                    put("status", "error")
                    put("error", "The car's answer could not be read.")
                }
            }
            else -> {
                AppLogger.log(TAG, "read_car ${what.word} -> $result")
                result.toResponseJson()
            }
        }
    }

    private fun VehicleDispatchResult.toResponseJson(): JsonObject = buildJsonObject {
        when (this@toResponseJson) {
            is VehicleDispatchResult.Success -> {
                put("status", "ok")
                CommandAnswers.forModel(note)?.let { put("note", it) }
            }
            is VehicleDispatchResult.Blocked -> {
                put("status", "blocked")
                put("reason", reason)
            }
            is VehicleDispatchResult.Failure -> {
                put("status", "error")
                put("error", error.name)
                detail?.let { put("detail", it) }
            }
        }
    }
}

/** Called by the model when the user says they're finished, to end a continuing conversation. */
const val END_CONVERSATION_FUNCTION = "end_conversation"

private val endConversationDeclaration = GeminiFunctionDeclaration(
    name = END_CONVERSATION_FUNCTION,
    description = "End the voice conversation: stop listening. Call this when the user says they are finished — " +
        "e.g. stop, that's all, thanks, goodbye, cancel — in English, Arabic, Russian, Polish or Indonesian (e.g. sudah, cukup, itu saja, terima kasih, selesai, batal).",
)

/** Shared system prompt for both the classic and Live conversation paths. [conversation] is the Live
 * path, where the assistant keeps listening after every reply until told to stop. [dialect] only matters
 * when the assistant answers in Arabic; [webSearch] says Google Search is available this session. */
fun voiceAssistantSystemPrompt(
    language: AssistantLanguage,
    conversation: Boolean = false,
    dialect: ArabicDialect = ArabicDialect.MATCH,
    webSearch: Boolean = false,
    memory: MemoryContext = MemoryContext(),
    musicHint: Boolean = false,
    identity: AssistantIdentity = AssistantIdentity(),
    // Song PLUS edition: the ride when the conversation starts, for the assistant's hidden manners (see [RideStyle]).
    ride: RideContext? = null,
): String {
    // A fixed language is a firm rule and is said twice (here and again at the very end): a model that listens in many
    // languages, and was told only once, tends to answer in whichever it heard, which an unclear word can make the wrong one.
    val languageInstruction = when (language) {
        AssistantLanguage.AUTO -> "Reply in whichever language the user spoke — English, French, Arabic (including Tunisian Derja), Russian, Polish or Indonesian."
        AssistantLanguage.ENGLISH ->
            "Always reply in English. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(Arabic, Russian, Polish, Indonesian, Spanish, anything else), your reply is in English."
        AssistantLanguage.ARABIC ->
            "Always reply in Arabic. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(English, Russian, Polish, Indonesian, Spanish, anything else), your reply is in Arabic."
        AssistantLanguage.RUSSIAN ->
            "Always reply in Russian. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(English, Arabic, Polish, Indonesian, Spanish, anything else), your reply is in Russian."
        AssistantLanguage.POLISH ->
            "Always reply in Polish. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(English, Arabic, Russian, French, Indonesian, Spanish, anything else), your reply is in Polish."
        AssistantLanguage.INDONESIAN ->
            "Always reply in Indonesian. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(English, Arabic, Russian, Polish, Malay, anything else), your reply is in Indonesian."
        AssistantLanguage.FRENCH ->
            "Always reply in French. This is a firm rule: whatever language the user speaks, or seems to speak " +
                "(English, Arabic, Tunisian Derja, Russian, anything else), your reply is in French."
    }
    val languageReminder = when (language) {
        AssistantLanguage.AUTO -> ""
        AssistantLanguage.ENGLISH ->
            " Remember: every reply is in English, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in English to say it again."
        AssistantLanguage.ARABIC ->
            " Remember: every reply is in Arabic, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in Arabic to say it again."
        AssistantLanguage.RUSSIAN ->
            " Remember: every reply is in Russian, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in Russian to say it again."
        AssistantLanguage.POLISH ->
            " Remember: every reply is in Polish, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in Polish to say it again."
        AssistantLanguage.INDONESIAN ->
            " Remember: every reply is in Indonesian, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in Indonesian to say it again."
        AssistantLanguage.FRENCH ->
            " Remember: every reply is in French, whatever language you think you heard. If you could not make out " +
                "what was said, ask the user in French to say it again."
    }
    // Only said when it can matter: an assistant fixed to English or Russian never speaks Arabic.
    val dialectInstruction = dialect.promptName
        ?.takeIf { language == AssistantLanguage.AUTO || language == AssistantLanguage.ARABIC }
        ?.let { " When you reply in Arabic, speak $it — not another dialect." }
        .orEmpty()
    // Indonesian read word for word from English sounds stiff in a car; say how it should sound. Only said when the
    // assistant may answer in it.
    val indonesianInstruction = if (language == AssistantLanguage.AUTO || language == AssistantLanguage.INDONESIAN) {
        " When you reply in Indonesian, speak natural, everyday Bahasa Indonesia, polite but not stiff: address the " +
            "user as \"Anda\", use the words Indonesian drivers use (AC, jok, jendela, kaca, setir, ban, lampu, " +
            "bagasi) and do not translate word for word from English. Understand informal Indonesian and Jakarta slang " +
            "too (e.g. \"nyalain\", \"matiin\", \"gedein\", \"kecilin\", \"dong\", \"ya\"), and regional accents."
    } else ""
    // The speech recognizer behind the Live API can't be told which language to expect (the native-audio
    // models choose it themselves), so say it here: what this user speaks, and what they mostly ask about,
    // so an unclear word is heard as the car command it most likely was.
    val listeningHint = " The user speaks Tunisian Arabic (Derja), French, standard Arabic and English, and often mixes " +
        "them in one sentence the Tunisian way (e.g. \"sakker el fenêtre\", \"cha3el el clim\", \"7ell el coffre\"): " +
        "sakker/سكّر = close, 7ell/حلّ = open, cha3el/شعّل = switch on, tafi/طفّي = switch off, clim/climatiseur = A/C, " +
        "bellar/blar/بلّار = window glass, coffre/كوفر = trunk, karhba/كرهبة = car, kursi/كرسي = seat. " +
        "Most requests are about the car — windows, seats (heating, ventilation), the A/C, ambient light, tyre pressure, " +
        "range and battery, navigation, music — so when a word is unclear, prefer the reading that is a car or assistant " +
        "request over an unrelated phrase. For several car settings at once (\"I'm hot\", \"close everything\"), " +
        "use run_scene when a scene fits. For any question about the car's current state (what is open, battery, " +
        "range, A/C, doors), call read_car with what=status before answering. For questions about fuel or energy " +
        "consumption or how to drive more economically, call read_car with what=status and use its trip_computer and " +
        "previous_readings. For \"how far is it\", \"when do we arrive\" or \"do I have enough battery/fuel to get there\", " +
        "call read_navigation (and read_car for the range). For \"how am I driving\" or how to save fuel or battery on this " +
        "drive, call drive_report (15 minutes unless they say 30). For the weather or anything nearby, call car_location " +
        "first, then look it up. A plain goodbye just ends the conversation; only when the " +
        "user says they are leaving or parking the car, or asks to close up, run the goodbye scene (it tidies the " +
        "cabin and closes the glass; it never switches the car off)."
    val searchInstruction = if (webSearch) {
        " For questions about current information — news, weather, sports results, prices, opening hours — " +
            "use Google Search, then answer in one or two spoken sentences without reading out links or sources."
    } else ""
    // Memory, when the user has it on: what it knows, how to add to it, and what was just said.
    val memoryInstruction = buildString {
        if (memory.tools) {
            append(
                " You have a memory. When the user asks you to remember something, call remember with one short " +
                    "sentence; when they ask you to forget something, call forget_memory. At most once in a " +
                    "conversation, and only for a clearly lasting preference or fact (never a one-off request), " +
                    "you may offer in one short sentence to remember it, and call remember only if they agree.",
            )
        }
        MemoryFacts.promptSection(memory.facts)?.let { append(" $it") }
        RecentExchanges.promptSection(memory.recent)?.let { append(" $it") }
    }
    // Opt-in: music with singing can reach the microphone, and the model takes sung words for someone speaking to it.
    val musicInstruction = if (musicHint) {
        " Music may be playing in the car. Sung words and other music are not the driver speaking to you: ignore them, and " +
            "answer only when a person speaks to you."
    } else ""
    return "${identity.promptOpening()} $languageInstruction$dialectInstruction$indonesianInstruction " +
        "Keep replies short — they will be read aloud.$listeningHint When the user asks to be taken somewhere, call " +
        "navigate_to with the place name instead of describing the route. When the user asks to open an app, call " +
        "open_app, and to close or quit an app, call close_app; for play, pause, next or previous on the music, " +
        "call media_control. When the user asks you to " +
        "control something in the car and a matching function is available, call that function instead " +
        "of describing the action in words. If no matching function is available, say so plainly rather " +
        "than pretending to have done it.$searchInstruction$memoryInstruction$musicInstruction" +
        (
            if (conversation) {
                " This is an ongoing conversation: after you answer, the user may keep talking, so answer " +
                    "and wait. When the user says they are finished, call end_conversation and say a very short goodbye."
            } else {
                ""
            }
            ) +
        ride?.let { RideStyle.instruction(it) }.orEmpty() +
        languageReminder
}

/**
 * Whether a spoken reply (as its transcript) is in the language the user fixed, as far as its letters say: a heuristic,
 * for the log only. Null when there is nothing to say (the language is automatic, or the reply has no letters).
 */
object ReplyLanguage {
    fun matches(language: AssistantLanguage, reply: String): Boolean? {
        val letters = reply.filter { it.isLetter() }
        if (letters.isEmpty()) return null
        val arabic = letters.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }
        val cyrillic = letters.count { it in '\u0400'..'\u052F' }
        return when (language) {
            AssistantLanguage.AUTO -> null
            // Names and a few English words inside an Arabic reply are fine; a reply with no Arabic at all is not.
            AssistantLanguage.ARABIC -> arabic > 0
            // Any Arabic, other scripts, or accented letters (Spanish, French...) are not English.
            AssistantLanguage.ENGLISH -> letters.all { it.code < 0x80 }
            // Names and a few English words inside a Russian reply are fine; a reply with no Cyrillic at all is not.
            AssistantLanguage.RUSSIAN -> cyrillic > 0
            // Polish is written in the Latin script, like English and Spanish, so its letters cannot prove it (a short
            // reply may have no accented letter at all): only a reply in another script is certainly not Polish.
            AssistantLanguage.POLISH -> arabic == 0 && cyrillic == 0
            // Indonesian is written in plain Latin letters, so, as with Polish, only another script rules it out.
            AssistantLanguage.INDONESIAN -> arabic == 0 && cyrillic == 0
            // French is Latin script too: only a reply in another script is certainly not French.
            AssistantLanguage.FRENCH -> arabic == 0 && cyrillic == 0
        }
    }
}
