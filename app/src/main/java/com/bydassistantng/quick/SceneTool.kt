package com.bydassistantng.quick

import android.content.Context
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import com.bydassistantng.vehicle.VehicleController
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Song PLUS edition: `run_scene`, several car settings in one call (see [Scenes]). Only offered with vehicle control on. */
object SceneTool {
    const val FUNCTION_NAME = "run_scene"

    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Apply several car settings at once. " +
            Scenes.all.joinToString(" ") { "scene=${it.id}: ${it.description}." } +
            " Use it when the user says they are hot or cold, wants the glass cleared, everything closed, the car aired " +
            "out, or the climate switched off; for one single setting use that setting's own function instead.",
        parameters = GeminiSchema(
            type = "OBJECT",
            properties = mapOf(
                "scene" to GeminiSchema(type = "STRING", description = "Which scene.", enum = Scenes.all.map { it.id }),
            ),
            required = listOf("scene"),
        ),
    )

    suspend fun handle(
        context: Context,
        call: GeminiFunctionCall,
        controller: VehicleController,
        vehicleControlEnabled: Boolean,
        onDispatching: suspend (String) -> Unit,
    ): JsonObject {
        if (!vehicleControlEnabled) return buildJsonObject {
            put("status", "error")
            put("error", "Vehicle features are switched off in the app's settings.")
        }
        val scene = Scenes.byId((call.args["scene"] as? JsonPrimitive)?.content) ?: return buildJsonObject {
            put("status", "error")
            put("error", "Unknown scene. Use one of: ${Scenes.all.joinToString { it.id }}.")
        }
        onDispatching(scene.label)
        val outcome = QuickCommands.execute(context, QuickPlan(emptyList(), scene), controller)
        return buildJsonObject {
            put("status", if (outcome.ok) "ok" else "partly")
            put(
                "note",
                if (outcome.ok) "Done: ${scene.description}. Do not send these again; tell the user it is done."
                else "Some of the settings were not accepted by the car; tell the user which part may not have worked.",
            )
        }
    }
}
