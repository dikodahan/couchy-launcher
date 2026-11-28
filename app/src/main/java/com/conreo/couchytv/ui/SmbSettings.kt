package com.conreo.couchytv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.BetaDiagnostics
import com.conreo.couchytv.data.SmbConnection
import com.conreo.couchytv.data.SmbProfile
import com.conreo.couchytv.data.SmbProfiles
import com.conreo.couchytv.data.SmbSetup
import jcifs.smb.SmbAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SmbSettings(setup: SmbSetup?, profile: SmbProfile?, onChanged: suspend (SmbProfile?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fields = listOf(R.string.smb_server, R.string.smb_name, R.string.smb_username, R.string.smb_password)
    var values by remember(setup, profile) {
        mutableStateOf(listOf(setup?.server.orEmpty(), setup?.name.orEmpty(),
            setup?.username.orEmpty(), profile?.password.orEmpty()))
    }
    var editing by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableIntStateOf(0) }

    SettingsPage {
        SettingsTitle(stringResource(R.string.smb_setup))
        if (setup != null && profile == null) Text(stringResource(R.string.smb_restore_password))
        if (profile?.verified == false && status == 0) Text(stringResource(R.string.smb_unverified))
        val focus = initialFocus()
        for ((index, label) in fields.withIndex()) {
            val password = label == R.string.smb_password
            SettingsItem(
                modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                enabled = !busy,
                onClick = { editing = index },
                headlineContent = { Text(stringResource(label)) },
                supportingContent = {
                    Text(when {
                        password && values[index].isNotEmpty() -> "••••••••"
                        values[index].isNotEmpty() -> values[index]
                        index == 0 -> stringResource(R.string.smb_server_hint)
                        else -> stringResource(R.string.smb_enter_value)
                    })
                },
                leadingContent = { Icon(if (password) AppIcons.Lock else AppIcons.Pencil, contentDescription = null) },
            )
        }
        val details = SmbSetup(values[0], values[1], values[2])
        SettingsItem(
            enabled = !busy && details.isStorable(),
            onClick = {
                val candidate = SmbProfile(details, values[3], verified = false)
                busy = true
                status = R.string.smb_checking
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { SmbProfiles.save(context, candidate) }
                        onChanged(candidate)
                        status = if (!details.isValid()) {
                            R.string.smb_server_hint
                        } else {
                            try {
                                val verified = withContext(Dispatchers.IO) {
                                    SmbConnection(candidate).use { it.validate() }
                                    ensureActive()
                                    SmbProfile(details, candidate.password, verified = true)
                                        .also { SmbProfiles.save(context, it) }
                                }
                                onChanged(verified)
                                BetaDiagnostics.event("SMB setup connection verified")
                                R.string.smb_connected
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: SmbAuthException) {
                                BetaDiagnostics.event("SMB server rejected sign-in status=0x${error.ntStatus.toUInt().toString(16)}")
                                R.string.smb_auth_failed
                            } catch (error: Exception) {
                                BetaDiagnostics.failure("SMB setup validation", error)
                                if (error.cause is jcifs.CIFSUnsupportedCryptoException) {
                                    BetaDiagnostics.failure("SMB authentication provider", checkNotNull(error.cause))
                                }
                                R.string.smb_connection_failed
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        BetaDiagnostics.failure("SMB setup write", error)
                        status = R.string.toast_config_save_failed
                    } finally {
                        busy = false
                    }
                }
            },
            headlineContent = { Text(stringResource(R.string.smb_test_save)) },
            supportingContent = if (status == 0) null else ({ Text(stringResource(status)) }),
            leadingContent = { Icon(AppIcons.Wifi, contentDescription = null) },
        )
        if (setup != null) {
            SettingsItem(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { SmbProfiles.remove(context) }
                        onChanged(null)
                        values = List(4) { "" }
                        status = 0
                    }
                },
                headlineContent = { Text(stringResource(R.string.smb_remove)) },
                leadingContent = { Icon(AppIcons.Delete, contentDescription = null) },
            )
        }
    }

    if (editing in fields.indices) {
        val field = editing
        TextEntryDialog(
            title = stringResource(fields[field]),
            initial = values[field],
            onSave = { value ->
                values = values.toMutableList().apply { this[field] = value }
                status = 0
                editing = -1
            },
            onDismiss = { editing = -1 },
            positionAboveKeyboard = true,
            secretInput = field == 3,
            allowEmpty = true,
            trimInput = field != 3,
        )
    }
}
