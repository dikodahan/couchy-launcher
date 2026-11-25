package com.conreo.couchytv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.AdbShellResult
import com.conreo.couchytv.data.LocalAdb
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RecommendedCommandDialog(name: String, commands: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<AdbShellResult>?>(null) }
    val preview = remember(commands) {
        if (commands.size == 1) commands.single()
        else commands.mapIndexed { index, command -> "${index + 1}.\n$command" }.joinToString("\n\n")
    }
    var error by remember { mutableStateOf<Int?>(null) }
    val finished = result != null || error != null

    Dialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = !running),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.widthIn(max = 520.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                val completed = result
                val failure = error
                when {
                    completed != null -> {
                        val exitCode = completed.last().exitCode
                        val status = if (exitCode == 0) stringResource(R.string.recommended_adb_completed)
                        else stringResource(R.string.recommended_adb_exit_code, exitCode)
                        Text(if (commands.size > 1 && exitCode != 0) "${completed.size}/${commands.size} · $status" else status)
                        val output = completed.mapIndexedNotNull { index, step ->
                            val value = step.output.filter {
                                (!it.isISOControl() || it == '\n' || it == '\t') &&
                                    Character.getType(it) != Character.FORMAT.toInt()
                            }.take(if (commands.size > 1) 1_024 else 4_096)
                            if (value.isBlank()) null else if (commands.size == 1) value else "${index + 1}.\n$value"
                        }.joinToString("\n\n")
                        if (output.isNotBlank()) CommandText(output)
                    }
                    failure != null -> Text(stringResource(failure))
                    else -> {
                        Text(stringResource(if (running) R.string.home_connecting else R.string.recommended_adb_review))
                        CommandText(preview)
                    }
                }

                if (finished) {
                    val doneFocus = initialFocus()
                    Button(onClick = onDismiss, modifier = Modifier.focusRequester(doneFocus)) {
                        Text(stringResource(R.string.done))
                    }
                } else {
                    val cancelFocus = initialFocus()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDismiss, enabled = !running, modifier = Modifier.focusRequester(cancelFocus)) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = {
                                if (!running) {
                                    running = true
                                    scope.launch {
                                        try {
                                            if (!LocalAdb.isNetworkDebuggingAvailable()) {
                                                error = R.string.adb_enable_required
                                            } else {
                                                result = LocalAdb(context).shellSequence(commands)
                                            }
                                        } catch (_: TimeoutCancellationException) {
                                            error = R.string.home_connection_timeout
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: ConnectException) {
                                            error = R.string.adb_enable_required
                                        } catch (_: SocketTimeoutException) {
                                            error = R.string.home_connection_timeout
                                        } catch (_: Exception) {
                                            error = R.string.recommended_action_failed
                                        } finally {
                                            running = false
                                        }
                                    }
                                }
                            },
                            enabled = !running,
                        ) { Text(stringResource(R.string.recommended_adb_run)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandText(text: String) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val to = when (event.key) {
                    Key.DirectionDown -> if (scroll.canScrollForward) scroll.value + scroll.viewportSize / 2 else null
                    Key.DirectionUp -> if (scroll.canScrollBackward) scroll.value - scroll.viewportSize / 2 else null
                    else -> null
                } ?: return@onPreviewKeyEvent false
                scope.launch { scroll.animateScrollTo(to.coerceIn(0, scroll.maxValue)) }
                true
            }
            .focusable(enabled = scroll.canScrollForward || scroll.canScrollBackward)
            .verticalScroll(scroll)
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
    )
}
