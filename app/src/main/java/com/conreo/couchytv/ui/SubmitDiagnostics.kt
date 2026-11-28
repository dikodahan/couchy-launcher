package com.conreo.couchytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.NeedTelegramLogin
import com.conreo.couchytv.data.TelegramCloudBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

@Composable
internal fun SubmitDiagnostics(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cloud = remember(context) { TelegramCloudBackup.get(context) }
    var status by remember { mutableStateOf(R.string.submit_log_sending) }
    var signingIn by remember { mutableStateOf(false) }

    LaunchedEffect(signingIn) {
        if (signingIn) return@LaunchedEffect
        status = R.string.submit_log_sending
        try {
            cloud.submitDiagnostics()
            Actions.toast(context, context.getString(R.string.submit_log_sent))
            onDismiss()
        } catch (_: NeedTelegramLogin) {
            signingIn = true
        } catch (_: TimeoutCancellationException) {
            status = R.string.submit_log_failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = R.string.submit_log_failed
        }
    }

    when {
        signingIn -> TelegramAuthDialog(cloud, onSuccess = { signingIn = false }, onDismiss = onDismiss)
        else -> Dialog(onDismissRequest = { if (status == R.string.submit_log_failed) onDismiss() }) {
            Surface(shape = RoundedCornerShape(20.dp)) {
                Column(
                    Modifier.widthIn(max = 470.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.submit_log), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(status))
                    if (status == R.string.submit_log_failed) {
                        val focus = initialFocus()
                        SettingsButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) {
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            }
        }
    }
}
