package com.conreo.couchytv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.BuildConfig
import com.conreo.couchytv.R
import com.conreo.couchytv.data.AppUpdate
import com.conreo.couchytv.data.AppUpdates
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class UpdateCheckStatus(val message: Int) {
    Checking(R.string.update_checking),
    UpToDate(R.string.update_up_to_date),
    Failed(R.string.update_check_failed),
}

@Stable
internal class AppUpdatePrompt(
    context: Context,
    private val repository: AppUpdates,
    private val scope: CoroutineScope,
) {
    private val preferences = context.applicationContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private var deferred by mutableStateOf(preferences.getString("deferred_version", null))
    private var inFlight: Deferred<Result<AppUpdate?>>? = null
    var checkStatus by mutableStateOf<UpdateCheckStatus?>(null)
        private set
    var available by mutableStateOf<AppUpdate?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    val shouldOffer: Boolean
        get() = available?.version?.let { it != deferred } == true && !visible

    fun open() { if (available != null) visible = true }

    fun checkNow() {
        if (checkStatus == UpdateCheckStatus.Checking) return
        checkStatus = UpdateCheckStatus.Checking
        scope.launch {
            checkOnline().fold(
                onSuccess = { update ->
                    available = update
                    checkStatus = if (update == null) UpdateCheckStatus.UpToDate else null
                    open()
                },
                onFailure = { checkStatus = UpdateCheckStatus.Failed },
            )
        }
    }

    suspend fun checkPeriodically() {
        checkOnline().onSuccess { update ->
            if (!visible && checkStatus != UpdateCheckStatus.Checking) {
                available = update
                if (update != null) checkStatus = null
            }
        }
    }

    private suspend fun checkOnline(): Result<AppUpdate?> {
        // If a periodic check is already running, give the manual check its result too.
        val lookup = inFlight?.takeUnless { it.isCompleted } ?: scope.async {
            try {
                Result.success(repository.check())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }.also { inFlight = it }
        return lookup.await()
    }

    fun later() {
        deferred = available?.version
        preferences.edit().putString("deferred_version", deferred).apply()
        visible = false
    }
}

private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val INITIAL_DELAY_MS = 6_000L

@Composable
internal fun rememberAppUpdatePrompt(connected: Boolean, homeIdle: Boolean): AppUpdatePrompt? {
    if (!BuildConfig.GITHUB_UPDATES) return null
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focused = LocalWindowInfo.current.isWindowFocused
    val repository = remember(context) { AppUpdates(context) }
    val scope = rememberCoroutineScope()
    val prompt = remember(context, repository, scope) { AppUpdatePrompt(context, repository, scope) }
    val supported by produceState(false, repository) {
        value = withContext(Dispatchers.IO) { repository.isSupported() }
    }
    val checkClock = remember { longArrayOf(0L) }
    LaunchedEffect(supported, connected, lifecycle) {
        if (!supported || !connected) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val remaining = if (checkClock[0] == 0L) INITIAL_DELAY_MS
                else (checkClock[0] + CHECK_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                delay(remaining)
                checkClock[0] = SystemClock.elapsedRealtime()
                try {
                    prompt.checkPeriodically()
                } catch (cancelled: CancellationException) {
                    checkClock[0] = 0L
                    throw cancelled
                }
            }
        }
    }
    LaunchedEffect(prompt.available, focused, homeIdle) {
        if (focused && homeIdle && prompt.shouldOffer) prompt.open()
    }
    return prompt.takeIf { supported }
}

@Composable
internal fun AppUpdateDialog(prompt: AppUpdatePrompt) {
    val update = prompt.available ?: return
    if (!prompt.visible) return
    val context = LocalContext.current
    val repository = remember(context) { AppUpdates(context) }
    val scope = rememberCoroutineScope()
    var status by remember(update) { mutableStateOf(UpdateStatus.Ready) }
    var progress by remember(update) { mutableStateOf(0) }
    val locale = context.resources.configuration.locales[0]
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale) }

    fun downloadAndInstall() {
        status = UpdateStatus.Downloading
        scope.launch {
            try {
                val apk = repository.download(update) { progress = it }
                context.startActivity(repository.installerIntent(apk))
                prompt.later()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = UpdateStatus.Failed
            }
        }
    }

    val unknownSourceSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) downloadAndInstall()
        else status = UpdateStatus.PermissionRequired
    }
    fun requestUpdate() {
        if (context.packageManager.canRequestPackageInstalls()) {
            downloadAndInstall()
        } else {
            status = UpdateStatus.PermissionRequired
            try {
                unknownSourceSettings.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                )
            } catch (_: Exception) {
                // On TVs without that settings activity, the system installer owns the permission prompt.
                downloadAndInstall()
            }
        }
    }

    Dialog(onDismissRequest = prompt::later) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.width(390.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.update_available, update.version, Formatter.formatShortFileSize(context, update.size)))
                val statusText = when (status) {
                    UpdateStatus.Ready -> null
                    UpdateStatus.Downloading -> stringResource(R.string.update_downloading, percentFormat.format(progress / 100.0))
                    UpdateStatus.PermissionRequired -> stringResource(R.string.update_permission)
                    UpdateStatus.Failed -> stringResource(R.string.update_failed)
                }
                if (statusText != null) Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val focus = initialFocus()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = prompt::later, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(R.string.update_later))
                    }
                    Button(onClick = ::requestUpdate, enabled = status != UpdateStatus.Downloading) {
                        Text(stringResource(R.string.update_action))
                    }
                }
            }
        }
    }
}

private enum class UpdateStatus { Ready, Downloading, PermissionRequired, Failed }
