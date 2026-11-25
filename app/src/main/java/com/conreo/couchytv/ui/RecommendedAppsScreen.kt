package com.conreo.couchytv.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.IconLibrary
import com.conreo.couchytv.data.RecommendedApp
import com.conreo.couchytv.data.RecommendedCatalog
import com.conreo.couchytv.data.RecommendedCommand
import com.conreo.couchytv.data.RecommendedApps
import com.conreo.couchytv.data.RecommendedDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun RecommendedAppsScreen(catalogUrl: String, verifiedCatalog: RecommendedCatalog) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember(context, catalogUrl) { RecommendedApps(context, catalogUrl) }
    val scope = rememberCoroutineScope()
    var catalog by remember(repository) { mutableStateOf(verifiedCatalog) }
    val apps = catalog.apps
    val commands = catalog.adbCommands
    val locale = LocalConfiguration.current.locales[0]
    var selectedCommand by remember(repository) { mutableStateOf<RecommendedCommand?>(null) }
    val terminal by produceState<ImageBitmap?>(initialValue = null, commands.isNotEmpty()) {
        value = if (commands.isEmpty()) null else withContext(Dispatchers.IO) {
            IconLibrary.load(context).find("terminal")?.render(96)?.asImageBitmap()
        }
    }
    var downloads by remember { mutableStateOf(emptyMap<String, RecommendedDownload>()) }
    var pending by remember { mutableStateOf(emptySet<String>()) }
    var storageRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var installRequest by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        try {
            catalog = repository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The verified catalog remains available if a refresh fails.
        }
    }
    LaunchedEffect(apps, lifecycle) {
        if (apps.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    downloads = repository.downloads(apps)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A temporary DownloadManager error leaves the last visible state intact.
                }
                delay(if (downloads.values.any { it is RecommendedDownload.Active }) 350 else 1_250)
            }
        }
    }

    fun perform(entry: RecommendedApp, action: suspend () -> Unit) {
        if (entry.key in pending) return
        pending = pending + entry.key
        scope.launch {
            try {
                action()
                downloads = repository.downloads(catalog.apps)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Actions.toast(context, context.getString(R.string.recommended_action_failed))
            } finally {
                pending = pending - entry.key
            }
        }
    }
    fun enqueue(entry: RecommendedApp) = perform(entry) { repository.enqueue(entry) }
    fun install(entry: RecommendedApp) = perform(entry) { context.startActivity(repository.installer(entry)) }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val entry = catalog.apps.firstOrNull { it.key == storageRequest }
        storageRequest = null
        if (granted && entry != null) enqueue(entry)
        else Actions.toast(context, context.getString(R.string.recommended_storage_required))
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val entry = catalog.apps.firstOrNull { it.key == installRequest }
        installRequest = null
        if (context.packageManager.canRequestPackageInstalls() && entry != null) install(entry)
        else Actions.toast(context, context.getString(R.string.update_permission))
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.recommended_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when {
            apps.isEmpty() && commands.isEmpty() -> Text(
                stringResource(R.string.recommended_empty),
                color = MaterialTheme.colorScheme.onSurface,
            )
            else -> {
                val first = initialFocus()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((category, group) in apps.groupBy(RecommendedApp::category)) {
                        item(key = "category:$category") {
                            Text(category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        items(group.size, key = { "app:${group[it].key}" }) { index ->
                            val entry = group[index]
                            val focus = remember { FocusRequester() }
                            RecommendedAppRow(
                                entry = entry,
                                repository = repository,
                                download = if (entry.key in pending && downloads[entry.key] == null) {
                                    RecommendedDownload.Active(null)
                                } else downloads[entry.key],
                                enabled = entry.key !in pending,
                                downloadFocus = if (entry == apps.first()) first else focus,
                                onDownload = {
                                    if (Build.VERSION.SDK_INT == 28 && ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        if (storageRequest == null) {
                                            storageRequest = entry.key
                                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        }
                                    } else enqueue(entry)
                                },
                                onInstall = {
                                    if (context.packageManager.canRequestPackageInstalls()) install(entry)
                                    else if (installRequest == null) {
                                        installRequest = entry.key
                                        try {
                                            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                        } catch (_: Exception) {
                                            installRequest = null
                                            Actions.toast(context, context.getString(R.string.update_permission))
                                        }
                                    }
                                },
                                onDelete = {
                                    val target = if (entry == apps.first()) first else focus
                                    perform(entry) {
                                        repository.delete(entry)
                                        runCatching { target.requestFocus() }
                                    }
                                },
                            )
                        }
                    }
                    if (commands.isNotEmpty()) {
                        item(key = "adb-commands") {
                            Text(
                                stringResource(R.string.recommended_adb_commands),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        items(commands.size, key = { "command:${commands[it].key}" }) { index ->
                            val entry = commands[index]
                            SettingsItem(
                                onClick = { selectedCommand = entry },
                                modifier = if (apps.isEmpty() && index == 0) Modifier.focusRequester(first)
                                    else Modifier,
                                headlineContent = { Text(entry.displayName(locale)) },
                                leadingContent = {
                                    val bitmap = terminal
                                    if (bitmap != null) {
                                        Image(
                                            bitmap,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            colorFilter = ColorFilter.tint(LocalContentColor.current),
                                        )
                                    } else Icon(AppIcons.Apps, contentDescription = null, modifier = Modifier.size(24.dp))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    selectedCommand?.let { entry ->
        RecommendedCommandDialog(entry.displayName(locale), entry.commands) { selectedCommand = null }
    }
}

@Composable
private fun RecommendedAppRow(
    entry: RecommendedApp,
    repository: RecommendedApps,
    download: RecommendedDownload?,
    enabled: Boolean,
    downloadFocus: FocusRequester,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, entry.iconUrl) {
        value = (entry.iconUrl?.let { repository.icon(it) } ?: repository.fallbackIcon(entry))?.asImageBitmap()
    }
    val ready = download == RecommendedDownload.Ready
    val active = download as? RecommendedDownload.Active
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val icon = bitmap
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(38.dp))
        else Icon(AppIcons.Apps, contentDescription = null, modifier = Modifier.size(38.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            if (download == RecommendedDownload.Failed) {
                Text(stringResource(R.string.recommended_download_failed), style = MaterialTheme.typography.bodySmall)
            }
        }
        val label = stringResource(if (ready) R.string.recommended_downloaded else R.string.recommended_download)
        RecommendedButton(
            label = label,
            modifier = Modifier.focusRequester(downloadFocus).semantics {
                if (active != null) {
                    progressBarRangeInfo = active.progress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                        ?: ProgressBarRangeInfo.Indeterminate
                }
            },
            onClick = { if (enabled && !ready && active == null) onDownload() },
        ) {
            if (active != null) DownloadRing(active.progress)
            else RecommendedActionIcon(if (ready) AppIcons.Check else AppIcons.Download)
        }
        RecommendedButton(stringResource(R.string.recommended_install), enabled = ready && enabled, onClick = onInstall) {
            RecommendedActionIcon(AppIcons.Install)
        }
        RecommendedButton(stringResource(R.string.recommended_delete), enabled = ready && enabled, onClick = onDelete) {
            RecommendedActionIcon(AppIcons.Delete)
        }
    }
}

@Composable
private fun RecommendedButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(42.dp).semantics { contentDescription = label },
        contentPadding = PaddingValues(0.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
    ) { content() }
}

@Composable
private fun RecommendedActionIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
}

@Composable
private fun DownloadRing(progress: Float?) {
    val color = LocalContentColor.current
    val start = if (progress != null) -90f else {
        val transition = rememberInfiniteTransition(label = "download")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 950, easing = LinearEasing)),
            label = "downloadAngle",
        )
        angle
    }
    Canvas(Modifier.size(17.dp)) {
        val ring = Stroke(2.dp.toPx())
        drawCircle(color.copy(alpha = 0.25f), style = ring)
        drawArc(color, start, progress?.times(360f) ?: 85f, useCenter = false, style = ring)
    }
}
