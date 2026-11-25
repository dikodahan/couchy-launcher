package com.conreo.couchytv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.TelegramAuthUi
import com.conreo.couchytv.data.TelegramCloudBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Telegram device-link on the TV: show a QR the phone app scans
 * (Settings → Devices → Link Desktop Device). If the account has two-step
 * verification, a password dialog follows.
 */
@Composable
fun TelegramAuthDialog(
    backup: TelegramCloudBackup,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ui by backup.authUi.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    val waiting = stringResource(R.string.cloud_auth_waiting)
    val genericFail = stringResource(R.string.cloud_auth_fail)
    val unsupported = stringResource(R.string.cloud_auth_unsupported)
    val wrongPw = stringResource(R.string.cloud_auth_2fa_wrong)

    LaunchedEffect(Unit) {
        backup.beginLogin()
        runCatching { backup.awaitLoggedIn() }
            .onSuccess { onSuccess() }
    }

    DisposableEffect(backup) {
        onDispose { backup.endLogin() }
    }

    val qrLink = (ui as? TelegramAuthUi.Qr)?.link
    val qr by produceState<ImageBitmap?>(initialValue = null, qrLink) {
        value = qrLink?.let { link -> withContext(Dispatchers.Default) { encodeQrBitmap(link) } }
    }

    val status = when (val s = ui) {
        is TelegramAuthUi.Working -> waiting
        is TelegramAuthUi.Qr -> waiting
        is TelegramAuthUi.Password -> s.error?.let { wrongPw } ?: ""
        is TelegramAuthUi.Failed ->
            if (s.message.startsWith("authorizationStateWait")) unsupported else genericFail
        else -> ""
    }

    val passwordPrompt = ui as? TelegramAuthUi.Password
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (passwordPrompt != null) PositionDialogAboveKeyboard()
        val contentScroll = if (passwordPrompt != null) {
            Modifier.verticalScroll(rememberScrollState())
        } else Modifier
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier
                    .width(if (passwordPrompt != null) 400.dp else 520.dp)
                    .then(contentScroll)
                    .padding(if (passwordPrompt != null) 18.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (passwordPrompt != null) 8.dp else 12.dp),
            ) {
                Text(
                    stringResource(
                        if (passwordPrompt != null) R.string.cloud_auth_password_title
                        else R.string.cloud_auth_title,
                    ),
                    style = if (passwordPrompt != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(
                    stringResource(
                        if (passwordPrompt != null) R.string.cloud_auth_password_body
                        else R.string.cloud_auth_body,
                    ),
                    style = if (passwordPrompt != null) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
                if (passwordPrompt != null) {
                    if (passwordPrompt.hint.isNotBlank()) {
                        Text(
                            stringResource(R.string.cloud_auth_password_hint, passwordPrompt.hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f),
                        )
                    }
                    var fieldFocused by remember { mutableStateOf(false) }
                    val fieldFocus = initialFocus()
                    val submitFocus = remember { FocusRequester() }
                    fun submitPassword() {
                        if (password.isNotBlank()) scope.launch { backup.submitPassword(password) }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .settingsFieldFrame(fieldFocused)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        BasicTextField(
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus).onFocusChanged { fieldFocused = it.isFocused }.onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                    submitFocus.requestFocus()
                                    true
                                } else false
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(SettingsControlGap)) {
                        SettingsButton(
                            onClick = ::submitPassword,
                            modifier = Modifier.focusRequester(submitFocus),
                        ) { Text(stringResource(R.string.cloud_auth_password_submit)) }
                        SettingsButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    }
                } else {
                    val bitmap = qr
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = stringResource(R.string.cloud_auth_qr_cd),
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .size(240.dp)
                                .background(Color.White, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                        )
                    }
                    val cancelFocus = remember { FocusRequester() }
                    LaunchedEffect(qr) {
                        runCatching { cancelFocus.requestFocus() }
                    }
                    SettingsButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .focusRequester(cancelFocus),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
