package com.conreo.couchytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.ParentalControls
import com.conreo.couchytv.data.ParentalState
import com.conreo.couchytv.data.PinResult
import com.conreo.couchytv.data.validPin
import kotlinx.coroutines.launch

internal data class PinPrompt(
    val title: Int,
    val confirm: Boolean = false,
    val verify: suspend (String) -> PinResult,
    val onAccepted: () -> Unit = {},
)

@Composable
internal fun ParentalSettings(state: ParentalState, controls: ParentalControls) {
    var prompt by remember { mutableStateOf<PinPrompt?>(null) }
    val focus = initialFocus()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.parental_controls), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.parental_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val action = when {
            !state.hasPin -> R.string.parental_set_pin
            state.enabled -> R.string.parental_disable
            else -> R.string.parental_enable
        }
        SettingsItem(
            modifier = Modifier.focusRequester(focus),
            onClick = {
                prompt = if (!state.hasPin) PinPrompt(action, confirm = true, verify = controls::setup)
                else PinPrompt(action, verify = { controls.enable(it, !state.enabled) })
            },
            headlineContent = { Text(stringResource(action)) },
            leadingContent = { Icon(AppIcons.Lock, contentDescription = null) },
        )
        if (state.hasPin) {
            SettingsItem(
                onClick = { prompt = PinPrompt(R.string.parental_delete, verify = controls::delete) },
                headlineContent = { Text(stringResource(R.string.parental_delete)) },
                leadingContent = { Icon(AppIcons.Delete, contentDescription = null) },
            )
        }
    }
    prompt?.let { PinDialog(it) { prompt = null } }
}

@Composable
internal fun PinDialog(prompt: PinPrompt, onDismiss: () -> Unit) {
    var digits by remember(prompt) { mutableStateOf("") }
    var first by remember(prompt) { mutableStateOf<String?>(null) }
    var error by remember(prompt) { mutableStateOf<Int?>(null) }
    var busy by remember(prompt) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = initialFocus()

    fun digit(value: String) {
        if (!busy && digits.length < 8) {
            digits += value
            error = null
        }
    }
    fun erase() {
        if (!busy) digits = digits.dropLast(1)
    }
    fun submit() {
        if (busy || !validPin(digits)) return
        val pin = digits
        digits = ""
        if (prompt.confirm && first == null) {
            first = pin
            return
        }
        if (prompt.confirm && first != pin) {
            first = null
            error = R.string.parental_pin_mismatch
            return
        }
        busy = true
        scope.launch {
            val result = prompt.verify(pin)
            busy = false
            if (result == PinResult.Accepted) {
                onDismiss()
                prompt.onAccepted()
            } else {
                first = null
                error = when (result) {
                    PinResult.Incorrect -> R.string.parental_pin_incorrect
                    PinResult.Wait -> R.string.parental_pin_wait
                    else -> R.string.parental_pin_unavailable
                }
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(
                Modifier.width(340.dp).padding(18.dp).onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent.keyCode
                    if (event.type != KeyEventType.KeyDown) false
                    else when (key) {
                        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                            digit((key - android.view.KeyEvent.KEYCODE_0).toString())
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DEL -> { erase(); true }
                        else -> false
                    }
                },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(prompt.title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(if (first != null) R.string.parental_confirm_pin else R.string.parental_pin_hint))
                Text(
                    "●".repeat(digits.length) + "○".repeat(8 - digits.length),
                    style = MaterialTheme.typography.titleMedium,
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (number in row) {
                            Button(
                                modifier = if (number == 1) Modifier.focusRequester(focus) else Modifier,
                                onClick = { digit(number.toString()) },
                            ) { Text(number.toString()) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::erase) { Text(stringResource(R.string.parental_pin_erase)) }
                    Button(onClick = { digit("0") }) { Text("0") }
                    Button(onClick = ::submit, enabled = validPin(digits) && !busy) { Text(stringResource(R.string.done)) }
                }
            }
        }
    }
}
