package com.conreo.couchytv.ui

import android.provider.Settings
import java.io.IOException
import java.net.ConnectException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.conreo.couchytv.data.LocalAdb
import com.conreo.couchytv.data.StockHomeStillActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun HomeLauncherScreen(onHomeSetupComplete: () -> Unit) {
    var showHomeSetup by rememberSaveable { mutableStateOf(false) }
    SettingsPage {
        SettingsTitle(stringResource(R.string.home_launcher_title))
        HomeLauncherControls(onFirstHomeSetup = { showHomeSetup = true })
    }
    if (showHomeSetup) {
        val close = { showHomeSetup = false }
        Dialog(
            onDismissRequest = close,
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            FillDialogWindow()
            HomeSetupStep(onBack = close) { pressHome ->
                close()
                if (pressHome) onHomeSetupComplete()
            }
        }
    }
}

/** Shared by first-run setup and Launcher settings; no network connection until a confirmed action. */
@Composable
internal fun HomeLauncherControls(
    wizardBack: (() -> Unit)? = null,
    wizardSaving: Boolean = false,
    onWizardSettings: ((String) -> Unit)? = null,
    onWizardDone: ((pressHome: Boolean) -> Unit)? = null,
    onFirstHomeSetup: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val manager = remember { HomeLauncherManager.get(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf<HomeStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var adbAvailable by remember { mutableStateOf<Boolean?>(null) }
    var pendingPause by remember { mutableStateOf<HomeChoice?>(null) }
    var confirmRecovery by remember { mutableStateOf(false) }
    BackHandler(enabled = wizardBack != null) {
        if (!busy && !wizardSaving) wizardBack?.invoke()
    }
    fun couchyIsHome() {
        onWizardDone?.invoke(true)
    }
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
    suspend fun refreshStatus() {
        status = manager.status()
        adbAvailable = LocalAdb.isNetworkDebuggingAvailable()
    }
    val inWizard = onWizardDone != null
    LaunchedEffect(manager, lifecycle, inWizard) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refreshStatus()
            while (inWizard) {
                delay(1_500)
                adbAvailable = LocalAdb.isNetworkDebuggingAvailable()
            }
        }
    }
    fun change(choice: HomeChoice? = null, action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            message = context.getString(R.string.home_connecting)
            try {
                val target = choice?.component?.flattenToShortString() ?: "stock recovery"
                HomeDiagnostics.record(context, "Home action; target=$target; previous=${status?.currentPackage}")
                action()
                refreshStatus()
                HomeDiagnostics.record(context, "Home action verified; active=${status?.currentPackage}; paused=${status?.pausedPackages}")
                message = context.getString(R.string.home_change_done)
                if (choice?.component?.packageName == context.packageName &&
                    status?.currentPackage == context.packageName
                ) couchyIsHome()
            } catch (override: StockHomeStillActive) {
                refreshStatus()
                HomeDiagnostics.record(context, "Stock Home still active: ${override.activeHome.flattenToShortString()}")
                if (onFirstHomeSetup != null) {
                    message = null
                    onFirstHomeSetup()
                } else {
                    pendingPause = choice
                    message = context.getString(R.string.home_stock_still_active)
                }
            } catch (unavailable: ConnectException) {
                HomeDiagnostics.record(context, "Local ADB connection failed", unavailable)
                message = context.getString(R.string.adb_enable_required)
                refreshStatus()
            } catch (timedOut: TimeoutCancellationException) {
                HomeDiagnostics.record(context, "Home action timed out", timedOut)
                message = context.getString(R.string.home_connection_timeout)
                refreshStatus()
            } catch (failed: IOException) {
                HomeDiagnostics.record(context, "Local ADB connection failed", failed)
                message = context.getString(R.string.adb_enable_required)
                refreshStatus()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                HomeDiagnostics.record(context, "Home action failed", error)
                message = context.getString(R.string.home_change_failed) + "\n" + error.message.orEmpty().take(300)
                refreshStatus()
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
    Column(verticalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
        Text(stringResource(R.string.home_current, bidi.unicodeWrap(currentLabel)), style = MaterialTheme.typography.bodyMedium, color = Color.White,
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp))
        val couchy = status?.choices?.firstOrNull { it.component.packageName == context.packageName }
        if (onWizardDone != null) {
            val needsDebugSetup = adbAvailable == false
            val canUseCouchy = adbAvailable == true && couchy != null
            val wizardReady = canUseCouchy && !busy && !wizardSaving
            val focus = key(needsDebugSetup, canUseCouchy) { initialFocus() }
            if (needsDebugSetup) {
                SettingsItem(
                    onClick = { onWizardSettings?.invoke(Settings.ACTION_DEVICE_INFO_SETTINGS) },
                    modifier = Modifier.focusRequester(focus),
                    headlineContent = { Text(stringResource(R.string.wizard_developer_options)) },
                    supportingContent = { Text(stringResource(R.string.wizard_developer_steps)) },
                    leadingContent = { Icon(AppIcons.Info, contentDescription = null) },
                )
                SettingsItem(
                    onClick = { onWizardSettings?.invoke(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                    headlineContent = { Text(stringResource(R.string.wizard_enable_adb)) },
                    supportingContent = { Text(stringResource(R.string.wizard_adb_steps)) },
                    leadingContent = { Icon(AppIcons.Gear, contentDescription = null) },
                )
            } else if (adbAvailable == true) {
                Text(
                    stringResource(R.string.home_adb_enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            SettingsItem(
                onClick = {
                    if (status?.currentPackage == context.packageName) couchyIsHome()
                    else if (couchy != null) change(choice = couchy) {
                        manager.switchTo(couchy, pauseStock = false)
                    }
                },
                enabled = wizardReady,
                modifier = (if (canUseCouchy) Modifier.focusRequester(focus) else Modifier)
                    .focusProperties { canFocus = wizardReady },
                headlineContent = { Text(stringResource(R.string.home_use_couchy)) },
                leadingContent = {
                    Icon(painterResource(R.drawable.brand_silhouette), contentDescription = null, modifier = Modifier.size(24.dp))
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(SettingsControlGap, Alignment.CenterHorizontally),
            ) {
                SettingsButton(
                    onClick = { onWizardDone(false) },
                    enabled = !busy && !wizardSaving,
                    modifier = if (!needsDebugSetup && !canUseCouchy) Modifier.focusRequester(focus) else Modifier,
                ) { Text(stringResource(R.string.home_keep_current)) }
                if (wizardBack != null) {
                    SettingsButton(onClick = wizardBack, enabled = !busy && !wizardSaving) {
                        Text(stringResource(R.string.back))
                    }
                }
            }
        } else {
            val couchyIsCurrent = status?.currentPackage == context.packageName
            val target = if (couchyIsCurrent) status?.stockHome else couchy
            val label = target?.label?.takeIf { it.isNotBlank() }
                ?: stringResource(if (couchyIsCurrent) R.string.home_stock_launcher else R.string.app_name)
            val first = if (target != null) initialFocus() else null
            SettingsItem(
                modifier = if (first != null) Modifier.focusRequester(first) else Modifier,
                onClick = {
                    if (target != null) {
                        if (!couchyIsCurrent && status?.couchyConfigured == false) onFirstHomeSetup?.invoke()
                        else change(target) {
                            if (couchyIsCurrent) manager.switchTo(target, pauseStock = false)
                            else manager.switchToConfiguredCouchy(target)
                        }
                    }
                },
                enabled = !busy && target != null,
                headlineContent = { Text(stringResource(R.string.home_switch_to, bidi.unicodeWrap(label))) },
                leadingContent = { Icon(AppIcons.Apps, contentDescription = null) },
            )
            if (status?.pausedPackages?.isNotEmpty() == true) {
                SettingsItem(
                    onClick = { confirmRecovery = true },
                    enabled = !busy,
                    headlineContent = { Text(stringResource(R.string.home_restore_stock)) },
                    supportingContent = { Text(stringResource(R.string.home_restore_stock_sub)) },
                    leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
                )
            }
            SettingsItem(
                onClick = ::exportLog,
                enabled = !busy,
                headlineContent = { Text(stringResource(R.string.home_debug_export)) },
                supportingContent = { Text(stringResource(R.string.home_debug_export_sub)) },
                leadingContent = { Icon(AppIcons.Save, contentDescription = null) },
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.padding(8.dp)) }
        if (onWizardDone == null && adbAvailable != null) {
            if (adbAvailable == true) {
                Text(
                    stringResource(R.string.home_adb_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            } else {
                SettingsItem(
                    onClick = { Actions.openDebuggingSettings(context) },
                    enabled = !busy,
                    headlineContent = { Text(stringResource(R.string.home_adb_disabled)) },
                    supportingContent = { Text(stringResource(R.string.home_open_debugging)) },
                    leadingContent = { Icon(AppIcons.Gear, contentDescription = null) },
                )
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
    if (confirmRecovery) HomeChangeConfirmation(
        title = stringResource(R.string.home_restore_stock), body = stringResource(R.string.home_restore_stock_sub),
        onCancel = { confirmRecovery = false }, onConfirm = {
            confirmRecovery = false
            change { manager.restorePausedPackages() }
        },
    )
}

@Composable
internal fun PressHomeDialog(waitForHome: Boolean = false, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !waitForHome, dismissOnClickOutside = !waitForHome),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.widthIn(max = 440.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.home_press_home_title), style = MaterialTheme.typography.titleMedium)
                if (!waitForHome) {
                    Text(stringResource(R.string.home_press_home_body), style = MaterialTheme.typography.bodyMedium)
                    val focus = initialFocus()
                    SettingsButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
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
                Column(verticalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
                    SettingsItem(
                        onClick = onCancel,
                        modifier = Modifier.focusRequester(cancelFocus),
                        headlineContent = { Text(stringResource(R.string.cancel)) },
                    )
                    SettingsItem(
                        onClick = onConfirm,
                        headlineContent = { Text(stringResource(R.string.home_confirm_action)) },
                    )
                }
            }
        }
    }
}
