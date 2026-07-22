package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.formatClaimCode

/**
 * Field widgets shared by the register flow's steps. The web app autosaves rows on each field's
 * `change` event; the Compose equivalents here save text fields when focus leaves a changed,
 * valid field ([BlurSaveField]) and save picker/checkbox changes immediately, matching the web
 * flow's "no explicit save button per row" feel.
 */

/** Text field that calls [onSave] when focus leaves and the (validated) text changed. */
@Composable
internal fun BlurSaveField(
    initial: String,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    validate: (String) -> Boolean = { it.isNotBlank() },
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        singleLine = true,
        isError = !validate(text),
        modifier = modifier.onFocusChanged { state ->
            if (state.isFocused) {
                hadFocus = true
            } else if (hadFocus) {
                hadFocus = false
                if (text != initial && validate(text)) onSave(text)
            }
        },
    )
}

/** Compact dropdown picker rendered as an outlined button (no experimental APIs). */
@Composable
internal fun <T> DropdownPicker(
    options: List<T>,
    selected: T?,
    display: (T) -> String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text(selected?.let(display) ?: placeholder)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ShirtPicker(selected: ShirtSize?, enabled: Boolean, onSelect: (ShirtSize) -> Unit) {
    DropdownPicker(ShirtSize.entries, selected, { it.displayName }, enabled, placeholder = "Shirt…", onSelect = onSelect)
}

/** Gender picker with a placeholder until one is chosen (required before a row can save). */
@Composable
internal fun GenderPicker(selected: Gender?, enabled: Boolean, onSelect: (Gender) -> Unit) {
    DropdownPicker(Gender.entries, selected, { it.displayName }, enabled, placeholder = "Gender…", onSelect = onSelect)
}

@Composable
internal fun LabeledCheckbox(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Claim-code chip — tap to copy (the web version's click-to-copy badge); nothing when codeless. */
@Composable
internal fun ClaimCodeChip(code: String?) {
    if (code.isNullOrBlank()) return
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) {
        delay(1200)
        copied = false
    }
    Text(
        if (copied) "Copied!" else formatClaimCode(code),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .clickable {
                clipboard.setText(AnnotatedString(formatClaimCode(code)))
                copied = true
            }
            .padding(4.dp),
    )
}

/** A pending confirmation — rendered by [ConfirmDialogHost] as an AlertDialog. */
internal class ConfirmRequest(val message: String, val confirmLabel: String, val onConfirm: () -> Unit)

@Composable
internal fun ConfirmDialogHost(request: ConfirmRequest?, onDismiss: () -> Unit) {
    if (request == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(request.message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                request.onConfirm()
            }) { Text(request.confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
