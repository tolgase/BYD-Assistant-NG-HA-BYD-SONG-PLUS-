package com.bydassistantng.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.bydassistantng.R
import com.bydassistantng.data.AssistantIdentity

/** Song PLUS edition: the three name fields (assistant, car, driver), used by setup, Settings and the one-time ask on Home. */
@Composable
fun NamesForm(value: AssistantIdentity, onChange: (AssistantIdentity) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NameField(value.assistant, stringResource(R.string.names_assistant_label), stringResource(R.string.names_assistant_example)) { onChange(value.copy(assistant = it)) }
        NameField(value.car, stringResource(R.string.names_car_label), stringResource(R.string.names_car_example)) { onChange(value.copy(car = it)) }
        NameField(value.driver, stringResource(R.string.names_driver_label), stringResource(R.string.names_driver_example)) { onChange(value.copy(driver = it)) }
        Text(stringResource(R.string.names_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NameField(value: String, label: String, example: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(AssistantIdentity.MAX_CHARS)) },
        label = { Text(label) },
        placeholder = { Text(example) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The names in a dialog: Save keeps them, the other button closes without changing anything. */
@Composable
fun NamesDialog(
    initial: AssistantIdentity,
    title: String,
    dismissLabel: String,
    onSave: (AssistantIdentity) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { NamesForm(draft, { draft = it }) },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
