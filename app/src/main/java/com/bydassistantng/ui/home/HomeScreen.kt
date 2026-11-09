package com.bydassistantng.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.bydassistantng.ui.theme.LocalNeonLook
import androidx.hilt.navigation.compose.hiltViewModel
import com.bydassistantng.BuildConfig
import com.bydassistantng.R
import com.bydassistantng.update.UpdateState
import com.bydassistantng.util.hasMicPermission

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenUpdates: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val vehicleControlEnabled by viewModel.vehicleControlEnabled.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val justUpdatedTo by viewModel.justUpdatedTo.collectAsState()
    val askNames by viewModel.askNames.collectAsState()
    if (askNames) {
        com.bydassistantng.ui.NamesDialog(
            initial = com.bydassistantng.data.AssistantIdentity(),
            title = stringResource(R.string.names_title),
            dismissLabel = stringResource(R.string.names_later),
            onSave = { viewModel.saveNames(it) },
            onDismiss = { viewModel.skipNames() },
        )
    }

    // Re-checked on every tap rather than remembered: an "only this time" mic grant can lapse
    // while this screen stays composed.
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.onMicTapped()
    }

    val look = LocalNeonLook.current
    val talk = {
        if (context.hasMicPermission()) viewModel.onMicTapped()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // fillMaxWidth, not fillMaxSize: a topBar slot that fills the whole height leaves the
            // content below it with zero height, which hid the mic button and status text entirely.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                if (vehicleControlEnabled) {
                    GlassPill(modifier = Modifier.padding(end = 10.dp)) {
                        Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = look.accent, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.home_vehicle_armed),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(14.dp),
                    color = look.glass,
                    border = if (look.neon) BorderStroke(1.dp, look.edge) else null,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // A soft pool of the accent colour behind the orb, so the page is not flat black (Neon only).
            if (look.neon) {
                Box(
                    modifier = Modifier.fillMaxSize().drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                listOf(look.accent.copy(alpha = 0.10f), look.accent2.copy(alpha = 0.04f), Color.Transparent),
                                center = Offset(size.width / 2, size.height * 0.42f),
                                radius = size.minDimension * 0.75f,
                            ),
                        )
                    },
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VoiceOrb(
                    mode = uiState.orbMode(),
                    contentDescription = stringResource(R.string.home_talk),
                    onClick = talk,
                    size = 220.dp,
                )
                Text(
                    text = statusText(uiState),
                    style = if (uiState is HomeUiState.Speaking) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = if (uiState is HomeUiState.Speaking) FontWeight.Normal else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    color = if (uiState is HomeUiState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 20.dp).widthIn(max = 560.dp),
                )
                if (uiState is HomeUiState.Idle) {
                    Text(
                        stringResource(R.string.home_hint_wheel),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).widthIn(max = 480.dp),
                    )
                }
            }
            // Song PLUS edition: the installed version, always in sight, so what is on the car can be told at a glance
            // (and matched against what the update server has). Tapping it opens Updates.
            TextButton(
                onClick = onOpenUpdates,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                Text(
                    stringResource(R.string.about_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            UpdateNote(
                availableVersion = (updateState as? UpdateState.Available)?.release?.version?.toString(),
                justUpdatedTo = justUpdatedTo,
                onView = onOpenUpdates,
                onDismissUpdated = { viewModel.dismissJustUpdated() },
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp),
            )
        }
    }
}

/** A small rounded label on glass: the "car control on" note in the top bar. */
@Composable
internal fun GlassPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val look = LocalNeonLook.current
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = look.glass,
        border = if (look.neon) BorderStroke(1.dp, look.edge) else null,
        modifier = modifier,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** A quiet strip at the top of Home: a new version waiting, or the one just installed. */
@Composable
private fun UpdateNote(
    availableVersion: String?,
    justUpdatedTo: String?,
    onView: () -> Unit,
    onDismissUpdated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when {
        justUpdatedTo != null -> stringResource(R.string.updates_banner_updated, justUpdatedTo)
        availableVersion != null -> stringResource(R.string.updates_banner_available, availableVersion)
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (justUpdatedTo != null) {
                TextButton(onClick = onDismissUpdated) { Text(stringResource(R.string.action_dismiss)) }
            } else {
                TextButton(onClick = onView) { Text(stringResource(R.string.updates_banner_view)) }
            }
        }
    }
}

@Composable
private fun statusText(state: HomeUiState): String = when (state) {
    HomeUiState.Idle -> stringResource(R.string.home_tap_to_talk)
    HomeUiState.Connecting -> stringResource(R.string.home_connecting)
    HomeUiState.Listening -> stringResource(R.string.home_listening)
    HomeUiState.Thinking -> stringResource(R.string.home_thinking)
    is HomeUiState.Dispatching -> state.displayName
    is HomeUiState.Speaking -> state.text
    is HomeUiState.Error -> state.message
}
