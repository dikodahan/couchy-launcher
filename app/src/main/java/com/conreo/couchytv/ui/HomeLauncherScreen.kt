package com.conreo.couchytv.ui

import android.app.Activity
import android.content.Intent
import java.io.IOException
import java.net.ConnectException
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.*
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.HomeChoice
import com.conreo.couchytv.data.HomeDiagnostics
import com.conreo.couchytv.data.HomeLauncherManager
import com.conreo.couchytv.data.HomeStatus
import com.conreo.couchytv.data.StockHomeStillActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HomeLauncherScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_launcher_title), style = MaterialTheme.typography.titleLarge)
        HomeLauncherControls()
    }
}

/** Shared by first-run setup and Launcher settings; no network connection until a confirmed action. */
@Composable
internal fun HomeLauncherControls(wizardContinue: (() -> Unit)? = null) {
    val context = LocalContext.current
    val manager = remember { HomeLauncherManager.get(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf<HomeStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var pauseStock by remember { mutableStateOf(false) }
    var pendingPause by remember { mutableStateOf<HomeChoice?>(null) }
    var pending by remember { mutableStateOf<HomeChoice?>(null) }
    var chooseOther by remember { mutableStateOf(false) }
    var showHomeCheck by rememberSaveable { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    val logDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val saved = HomeDiagnostics.writeDocument(context, uri)
            message = context.getString(if (saved) R.string.home_debug_exported else R.string.home_debug_export_failed)
        }
    }
    fun exportLog() {
        scope.launch {
            HomeDiagnostics.record(context, "Requested log export; current Home=${manager.currentHome()?.flattenToShortString()}")
            val name = HomeDiagnostics.publishToDownloads(context)
            if (name != null) message = context.getString(R.string.home_debug_downloads, name)
            else runCatching { logDocument.launch(HomeDiagnostics.FILE_NAME) }
                .onFailure { message = context.getString(R.string.home_debug_export_failed) }
        }
    }
    LaunchedEffect(manager, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { status = manager.status() }
    }
    LaunchedEffect(status?.pausedPackages) {
        status?.let { pauseStock = it.pausedPackages.isNotEmpty() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            status = manager.status()
            HomeDiagnostics.record(context, "Android Home picker returned; active=${status?.currentPackage}")
            HomeDiagnostics.publishToDownloads(context)
        }
    }
    val rolePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            status = manager.status()
            HomeDiagnostics.record(context, "Android Home role result=${result.resultCode}; active=${status?.currentPackage}")
            HomeDiagnostics.publishToDownloads(context)
            if (status?.currentPackage == context.packageName) showHomeCheck = true
            else if (result.resultCode == Activity.RESULT_OK) message = context.getString(R.string.home_wizard_debug_needed)
        }
    }
    fun change(choice: HomeChoice? = null, allowRoleFallback: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            message = context.getString(R.string.home_connecting)
            try {
                val target = choice?.component?.flattenToShortString() ?: "stock recovery"
                HomeDiagnostics.record(context, "Home action; target=$target; previous=${status?.currentPackage}")
                action()
                status = manager.status()
                HomeDiagnostics.record(context, "Home action verified; active=${status?.currentPackage}; paused=${status?.pausedPackages}")
                message = context.getString(R.string.home_change_done)
                if (choice?.component?.packageName == context.packageName) showHomeCheck = true
            } catch (override: StockHomeStillActive) {
                status = manager.status()
                HomeDiagnostics.record(context, "Stock Home still active: ${override.activeHome.flattenToShortString()}")
                pendingPause = choice
                message = context.getString(R.string.home_stock_still_active)
            } catch (unavailable: ConnectException) {
                HomeDiagnostics.record(context, "Local ADB connection failed", unavailable)
                val role = if (allowRoleFallback) manager.couchyRoleIntent() else null
                if (role != null && runCatching { rolePicker.launch(role) }.isSuccess) {
                    message = null
                } else {
                    message = context.getString(R.string.adb_enable_required)
                }
            } catch (timedOut: TimeoutCancellationException) {
                HomeDiagnostics.record(context, "Home action timed out", timedOut)
                message = context.getString(R.string.home_connection_timeout)
                status = manager.status()
            } catch (failed: IOException) {
                HomeDiagnostics.record(context, "Local ADB connection failed", failed)
                message = context.getString(R.string.adb_enable_required)
                status = manager.status()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                HomeDiagnostics.record(context, "Home action failed", error)
                message = context.getString(R.string.home_change_failed) + "\n" + error.message.orEmpty().take(300)
                status = manager.status()
            } finally {
                try {
                    val name = HomeDiagnostics.publishToDownloads(context)
                    if (name != null) message = message?.let {
                        it + "\n" + context.getString(R.string.home_debug_downloads, name)
                    }
                } finally { busy = false }
            }
        }
    }
    val bidi = android.text.BidiFormatter.getInstance(context.resources.configuration.locales[0])
    val currentLabel = status?.currentLabel?.ifBlank { null } ?: stringResource(R.string.home_not_selected)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.home_current, bidi.unicodeWrap(currentLabel)), style = MaterialTheme.typography.bodyMedium, color = Color.White,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
        val couchy = status?.choices?.firstOrNull { it.component.packageName == context.packageName }
        if (wizardContinue != null) {
            val keepFocus = initialFocus()
            Row(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = wizardContinue,
                    enabled = !busy,
                    modifier = Modifier.focusRequester(keepFocus),
                ) { Text(stringResource(R.string.home_keep_current)) }
                Button(
                    onClick = {
                        if (status?.currentPackage == context.packageName) showHomeCheck = true
                        else if (couchy != null) change(choice = couchy, allowRoleFallback = true) {
                            manager.switchTo(couchy, pauseStock = false)
                        }
                    },
                    enabled = !busy && couchy != null,
                ) { Text(stringResource(R.string.home_use_couchy)) }
            }
        } else {
            val first = initialFocus()
            HomeControl(
                onClick = {
                    if (status?.pausedPackages?.isNotEmpty() == true) change {
                        manager.restorePausedPackages()
                        picker.launch(manager.systemPickerIntent())
                    } else runCatching { picker.launch(manager.systemPickerIntent()) }
                        .onFailure { Actions.toast(context, context.getString(R.string.home_picker_unavailable)) }
                },
                enabled = !busy,
                modifier = Modifier.focusRequester(first),
                title = stringResource(R.string.home_system_picker),
                detail = stringResource(R.string.home_system_picker_sub),
            )
            HomeControl(
                onClick = { advanced = !advanced },
                enabled = !busy,
                title = stringResource(R.string.home_debugging_controls),
                detail = stringResource(R.string.home_debugging_controls_sub),
                checked = advanced,
            )
            if (advanced) {
                Text(stringResource(R.string.home_debugging_help), style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(8.dp))
                HomeControl(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                            .onFailure { Actions.openSystemSettings(context) }
                    },
                    enabled = !busy,
                    title = stringResource(R.string.home_open_debugging),
                )
                HomeControl(
                    onClick = { pending = couchy },
                    enabled = !busy && couchy != null,
                    title = stringResource(R.string.home_use_couchy),
                    detail = stringResource(R.string.home_use_couchy_sub),
                )
                HomeControl(
                    onClick = { pauseStock = !pauseStock },
                    enabled = !busy,
                    title = stringResource(R.string.home_pause_stock),
                    detail = stringResource(R.string.home_pause_stock_sub),
                    checked = pauseStock,
                )
                HomeControl(
                    onClick = { chooseOther = true },
                    enabled = !busy && status?.choices?.any { it.component.packageName != context.packageName } == true,
                    title = stringResource(R.string.home_choose_other),
                    detail = stringResource(R.string.home_choose_other_sub),
                )
                if (status?.pausedPackages?.isNotEmpty() == true) {
                    HomeControl(
                        onClick = { confirmRecovery = true },
                        enabled = !busy,
                        title = stringResource(R.string.home_restore_stock),
                        detail = stringResource(R.string.home_restore_stock_sub),
                    )
                }
            }
            HomeControl(
                onClick = ::exportLog,
                enabled = !busy,
                title = stringResource(R.string.home_debug_export),
                detail = stringResource(R.string.home_debug_export_sub),
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(8.dp)) }
    }
    if (chooseOther) {
        Dialog(onDismissRequest = { chooseOther = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.home_choose_other), style = MaterialTheme.typography.titleMedium)
                    val first = initialFocus()
                    status?.choices.orEmpty().filter { it.component.packageName != context.packageName }
                        .forEachIndexed { index, choice ->
                            HomeControl(
                                title = choice.label,
                                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                onClick = { chooseOther = false; pending = choice },
                            )
                        }
                    Button(onClick = { chooseOther = false }) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
    pendingPause?.let { choice ->
        HomeChangeConfirmation(
            title = stringResource(R.string.home_stock_still_active),
            body = stringResource(R.string.home_confirm_pause),
            onCancel = { pendingPause = null },
            onConfirm = {
                pendingPause = null
                change(choice) { manager.switchTo(choice, pauseStock = true) }
            },
        )
    }
    pending?.let { choice ->
        val pause = pauseStock && choice.component.packageName == context.packageName
        HomeChangeConfirmation(
            title = stringResource(R.string.home_confirm_title, bidi.unicodeWrap(choice.label)),
            body = stringResource(if (pause) R.string.home_confirm_pause else R.string.home_confirm_switch),
            onCancel = { pending = null }, onConfirm = {
                pending = null
                change(choice) { manager.switchTo(choice, pause) }
            },
        )
    }
    if (showHomeCheck) {
        val finish = {
            showHomeCheck = false
            wizardContinue?.invoke()
            Unit
        }
        Dialog(onDismissRequest = finish) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.widthIn(max = 440.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.home_press_home_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_press_home_body), style = MaterialTheme.typography.bodyMedium)
                    val focus = initialFocus()
                    Button(onClick = finish, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(if (wizardContinue != null) R.string.next else R.string.done))
                    }
                }
            }
        }
    }
    if (confirmRecovery) HomeChangeConfirmation(
        title = stringResource(R.string.home_restore_stock), body = stringResource(R.string.home_restore_stock_sub),
        onCancel = { confirmRecovery = false }, onConfirm = {
            confirmRecovery = false
            change { manager.restorePausedPackages() }
        },
    )
}

/** The copy stays readable while focus highlights only the trailing control. */
@Composable
private fun HomeControl(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detail: String? = null,
    checked: Boolean? = null,
) {
    val checkState = checked?.let { stringResource(if (it) R.string.home_control_on else R.string.off) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.semantics {
                contentDescription = title
                if (checkState != null) stateDescription = checkState
            },
            scale = ButtonDefaults.scale(focusedScale = 1f),
        ) {
            if (checked == null) Text(stringResource(R.string.home_control_open))
            else CheckMark(checked, uncheckedColor = LocalContentColor.current.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun HomeChangeConfirmation(title: String, body: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(20.dp), colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.widthIn(max = 520.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                val cancelFocus = initialFocus()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = onConfirm) { Text(stringResource(R.string.home_confirm_action)) }
                }
            }
        }
    }
}
