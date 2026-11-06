package com.bydassistantng.quick

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydassistantng.ui.settings.SettingButton
import com.bydassistantng.ui.settings.SettingRow
import com.bydassistantng.ui.settings.SettingsSection
import com.bydassistantng.ui.settings.SwitchSetting

/** Song PLUS edition: the "Offline instant commands" settings (switch, language, speech model download). */
@Composable
fun QuickCommandsSettings() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { QuickCommands.refresh(context) }
    val state by QuickCommands.state.collectAsState()

    SettingsSection("Offline instant commands") {
        row {
            SwitchSetting(
                title = "Instant car commands",
                subtitle = "Windows, A/C, seats, lights and scenes are recognised on the car itself: done at once, " +
                    "and still working without internet. Gemini answers everything else.",
                checked = state.enabled,
                onCheckedChange = { QuickCommands.setEnabled(context, it) },
            )
        }
        row {
            val status = when (val m = state.model) {
                ModelStatus.Missing -> "Not downloaded (${state.language.sizeMb} MB, once)"
                is ModelStatus.Downloading -> "Downloading… ${m.percent} %"
                ModelStatus.Ready -> "Ready on this car"
                is ModelStatus.Failed -> "Download failed: ${m.message}"
            }
            SettingRow(
                title = "Speech model: ${state.language.label}",
                subtitle = status,
                trailing = {
                    when (state.model) {
                        ModelStatus.Ready -> SettingButton(label = "Delete", onClick = { QuickCommands.deleteModel(context) })
                        is ModelStatus.Downloading -> SettingButton(label = "Downloading…", enabled = false, onClick = {})
                        else -> SettingButton(label = "Download", primary = true, onClick = { QuickCommands.download(context) })
                    }
                },
                below = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        QuickLanguage.entries.forEach { lang ->
                            SettingButton(
                                label = lang.label,
                                primary = lang == state.language,
                                enabled = state.model !is ModelStatus.Downloading,
                                onClick = { QuickCommands.setLanguage(context, lang) },
                            )
                        }
                    }
                    Text(
                        "Examples: \"سكّر الشبابك\", \"شعّل الكليم اثنين و عشرين\", \"ferme la fenêtre\", \"clim vingt-deux\", " +
                            "\"j'ai chaud\", \"ambiance bleue\", \"close everything\". Scenes: cool down, warm up, defrost, " +
                            "close everything, air out, climate off.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                },
            )
        }
    }
}
