package com.conreo.couchytv.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.LocalAdb
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

private val timeoutMinutes = listOf(15, 20, 30, 60, 180, 360, 720, 1440, -1)
private const val SLEEP_TIMEOUT = "sleep_timeout"

@Composable
internal fun AndroidPowerTimeoutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    val units = remember(locale) { MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE) }
    val focus = initialFocus()
    val currentTimeout = remember {
        runCatching { Settings.Secure.getInt(context.contentResolver, SLEEP_TIMEOUT) }.getOrNull()
    }
    var selected by remember {
        mutableIntStateOf(timeoutMinutes.indexOfFirst { timeoutMillis(it) == currentTimeout })
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.width(440.dp).heightIn(max = 500.dp).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
            ) {
                Text(
                    stringResource(R.string.shortcut_power_timeout),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                error?.let { Text(stringResource(it), modifier = Modifier.padding(8.dp)) }
                if (error == R.string.adb_enable_required) {
                    SettingsItem(
                        onClick = { Actions.openDebuggingSettings(context) },
                        headlineContent = { Text(stringResource(R.string.home_open_debugging)) },
                        leadingContent = { Icon(AppIcons.Gear, contentDescription = null) },
                    )
                }
                timeoutMinutes.forEachIndexed { index, minutes ->
                    val label = when {
                        minutes < 0 -> stringResource(R.string.power_timeout_never)
                        minutes < 60 -> units.format(Measure(minutes, MeasureUnit.MINUTE))
                        else -> units.format(Measure(minutes / 60, MeasureUnit.HOUR))
                    }
                    SettingsItem(
                        modifier = if (index == selected.coerceAtLeast(0)) Modifier.focusRequester(focus) else Modifier,
                        enabled = !busy,
                        headlineContent = { Text(label) },
                        trailingContent = { CheckMark(checked = index == selected) },
                        onClick = {
                            if (!busy) {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val value = timeoutMillis(minutes)
                                        val result = LocalAdb(context).shell(
                                            "settings --user current put secure $SLEEP_TIMEOUT $value && " +
                                                "settings --user current get secure $SLEEP_TIMEOUT",
                                        )
                                        if (result.exitCode == 0 && result.output.trim().toIntOrNull() == value) {
                                            selected = index
                                            onDismiss()
                                        } else error = R.string.toast_config_save_failed
                                    } catch (_: TimeoutCancellationException) {
                                        error = R.string.adb_enable_required
                                    } catch (_: IOException) {
                                        error = R.string.adb_enable_required
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun timeoutMillis(minutes: Int): Int = if (minutes < 0) -1 else minutes * 60_000
