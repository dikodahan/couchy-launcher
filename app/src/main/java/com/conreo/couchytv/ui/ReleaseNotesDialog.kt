package com.conreo.couchytv.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.AppUpdate
import kotlinx.coroutines.launch

@Composable
internal fun ReleaseNotesDialog(update: AppUpdate, onClose: () -> Unit) {
    val lines = remember(update.releaseNotes) { update.releaseNotes.lines() }
    val hasNotes = update.releaseNotes.isNotBlank()
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var bodyFocused by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val initial = key(LocalWindowInfo.current.isWindowFocused) { initialFocus() }
        Surface(
            modifier = Modifier.padding(horizontal = 32.dp).widthIn(max = 820.dp)
                .fillMaxWidth().fillMaxHeight(0.82f),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SettingsControlGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.update_release_notes),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(update.version, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasNotes) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .settingsFieldFrame(bodyFocused)
                            .focusRequester(initial)
                            .onFocusChanged { bodyFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val direction = when (event.key) {
                                    Key.DirectionDown -> if (scroll.canScrollForward) 1 else 0
                                    Key.DirectionUp -> if (scroll.canScrollBackward) -1 else 0
                                    else -> 0
                                }
                                if (direction == 0) return@onPreviewKeyEvent false
                                scope.launch {
                                    scroll.scrollBy(direction * scroll.layoutInfo.viewportSize.height * 0.4f)
                                }
                                true
                            }
                            .focusable(),
                        state = scroll,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(lines) { line -> ReleaseNoteLine(line) }
                    }
                } else {
                    Text(stringResource(R.string.update_release_notes_missing), modifier = Modifier.weight(1f))
                }
                SettingsButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.End).then(if (hasNotes) Modifier else Modifier.focusRequester(initial)),
                ) {
                    Text(stringResource(R.string.menu_close))
                }
            }
        }
    }
}

private val heading = Regex("^#{1,6}\\s+")
private val bullet = Regex("^[-*+]\\s+")
private val inlineNote = Regex("(\\*\\*|`)(.+?)\\1")

@Composable
private fun ReleaseNoteLine(line: String) {
    if (line.isBlank()) {
        Spacer(Modifier.height(5.dp))
        return
    }
    val title = heading.containsMatchIn(line)
    val text = remember(line) {
        val content = line.replaceFirst(heading, "").replaceFirst(bullet, "• ")
        buildAnnotatedString {
            var offset = 0
            for (match in inlineNote.findAll(content)) {
                append(content.substring(offset, match.range.first))
                val style = if (match.groupValues[1] == "**") SpanStyle(fontWeight = FontWeight.Bold)
                else SpanStyle(fontFamily = FontFamily.Monospace)
                withStyle(style) { append(match.groupValues[2]) }
                offset = match.range.last + 1
            }
            append(content.substring(offset))
        }
    }
    Text(
        text,
        style = if (title) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
    )
}
