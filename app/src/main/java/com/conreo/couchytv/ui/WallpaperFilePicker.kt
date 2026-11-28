package com.conreo.couchytv.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.BetaDiagnostics
import com.conreo.couchytv.data.SmbConnection
import com.conreo.couchytv.data.SmbProfile
import com.conreo.couchytv.data.hasFullWallpaperMediaAccess
import com.conreo.couchytv.data.hasWallpaperMediaAccess
import com.conreo.couchytv.data.indexedWallpaperFiles
import com.conreo.couchytv.data.isSmbWallpaper
import com.conreo.couchytv.data.wallpaperMediaPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class WallpaperPickerMode(val photo: Boolean, val folder: Boolean, val title: Int) {
    Photo(true, false, R.string.pick_photo),
    PhotoFolder(true, true, R.string.pick_video_folder),
    Videos(false, false, R.string.pick_video),
    Folder(false, true, R.string.pick_video_folder),
}

private data class WallpaperPickerEntry(val source: String, val name: String, val folder: Boolean)

@Composable
internal fun WallpaperFilePicker(
    mode: WallpaperPickerMode,
    deviceMediaEnabled: Boolean,
    smbProfile: SmbProfile?,
    selectedFiles: List<String>,
    onSelect: (List<String>) -> Unit,
    onAndroidBrowse: (() -> Unit)?,
    onConfigureSmb: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(selectedFiles.toSet()) }
    var localPath by remember { mutableStateOf<List<String>?>(null) }
    var showSmbStorage by remember { mutableStateOf(false) }
    var smbPath by remember { mutableStateOf<List<String>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val hasAccess = remember(refresh) { deviceMediaEnabled && context.hasWallpaperMediaAccess(mode.photo) }
    val fullAccess = remember(refresh) { deviceMediaEnabled && context.hasFullWallpaperMediaAccess(mode.photo) }

    val remote = smbPath.lastOrNull()
    val local = localPath
    val localFiles by produceState(emptyList(), local != null, hasAccess, refresh) {
        value = if (local == null || !hasAccess) emptyList() else try {
            withContext(Dispatchers.IO) { context.indexedWallpaperFiles(mode.photo) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BetaDiagnostics.failure("Local wallpaper browser", error)
            emptyList()
        }
    }
    val remoteResult = key(remote) {
        produceState<Result<List<WallpaperPickerEntry>>?>(null, refresh) {
            if (remote != null && smbProfile != null) {
                value = try {
                    Result.success(withContext(Dispatchers.IO) {
                        SmbConnection(smbProfile).use { smb ->
                            smb.list(remote, mode.photo).map { WallpaperPickerEntry(it.uri, it.name, it.folder) }
                        }
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    BetaDiagnostics.failure("SMB wallpaper browser", error)
                    Result.failure(error)
                }
            }
        }.value
    }
    val localHere = remember(local, localFiles) {
        if (local == null) emptyList() else localFiles.filter { it.path.take(local.size) == local }
    }
    val entries = remember(local, localHere, remoteResult, mode) {
        val listed = if (local == null) remoteResult?.getOrNull().orEmpty() else {
            val folders = localHere.mapNotNull { it.path.getOrNull(local.size) }.distinct().sortedBy { it.lowercase() }
            folders.map { WallpaperPickerEntry(it, it, true) } +
                localHere.filter { it.path == local }.map { WallpaperPickerEntry(it.source, it.name, false) }
        }
        if (mode.folder) listed.filter { it.folder } else listed
    }
    val selection = if (remote != null) {
        checked.filter { isSmbWallpaper(it) && Uri.parse(it).authority == smbProfile?.id }
    } else {
        localFiles.map { it.source }.filter(checked::contains)
    }
    val folder = when {
        remote != null && remoteResult?.isSuccess == true &&
            (remote != smbProfile?.root || !smbProfile?.share.isNullOrEmpty()) -> remote
        !local.isNullOrEmpty() -> localHere.firstOrNull { it.path == local }?.folder
        else -> null
    }

    fun back() {
        when {
            remote != null -> smbPath = smbPath.dropLast(1)
            showSmbStorage -> showSmbStorage = false
            local != null -> localPath = local.dropLast(1).takeIf { local.isNotEmpty() }
            else -> onDismiss()
        }
    }

    Dialog(onDismissRequest = ::back) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            Column(
                Modifier.widthIn(min = 420.dp, max = 500.dp).heightIn(max = 420.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
            ) {
                val title = when {
                    remote != null && smbProfile != null && remote == smbProfile.root -> smbProfile.name
                    remote != null -> Uri.parse(remote).lastPathSegment.orEmpty()
                    local != null -> local.lastOrNull() ?: stringResource(R.string.wallpaper_browse_files)
                    showSmbStorage -> stringResource(R.string.smb_storage)
                    else -> stringResource(mode.title)
                }
                SettingsTitle(title)
                when {
                    local == null && !showSmbStorage -> {
                        SettingsItem(
                            modifier = Modifier.focusRequester(initialFocus()),
                            onClick = { localPath = emptyList() },
                            headlineContent = { Text(stringResource(R.string.wallpaper_browse_files)) },
                            leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
                        )
                        SettingsItem(
                            onClick = { if (smbProfile == null) onConfigureSmb() else showSmbStorage = true },
                            headlineContent = { Text(stringResource(R.string.smb_storage)) },
                            supportingContent = { Text(smbProfile?.name ?: stringResource(R.string.smb_setup)) },
                            leadingContent = { Icon(AppIcons.Wifi, contentDescription = null) },
                        )
                    }
                    showSmbStorage && remote == null && smbProfile != null -> {
                        SettingsItem(
                            modifier = Modifier.focusRequester(initialFocus()),
                            onClick = { smbPath = listOf(smbProfile.root) },
                            headlineContent = { Text(smbProfile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
                        )
                    }
                    else -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
                            val focus = if (entries.isEmpty()) Modifier.focusRequester(initialFocus()) else Modifier
                            SettingsButton(onClick = ::back, modifier = focus) { Text(stringResource(R.string.back)) }
                            if (mode.folder) {
                                SettingsButton(onClick = { folder?.let { onSelect(listOf(it)) } }, enabled = folder != null) {
                                    Text(stringResource(R.string.smb_use_folder))
                                }
                            } else {
                                SettingsButton(onClick = { onSelect(selection) }, enabled = selection.isNotEmpty()) {
                                    Text(stringResource(R.string.done))
                                }
                            }
                        }
                        if (local != null && deviceMediaEnabled && !fullAccess) {
                            SettingsItem(
                                onClick = { permissions.launch(wallpaperMediaPermissions(mode.photo)) },
                                headlineContent = {
                                    Text(stringResource(if (mode.photo) R.string.wallpaper_photo_access else R.string.wallpaper_folder_access))
                                },
                            )
                        }
                        if (local != null && onAndroidBrowse != null) {
                            SettingsItem(
                                onClick = onAndroidBrowse,
                                headlineContent = { Text(stringResource(R.string.wallpaper_browse_android)) },
                                leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
                            )
                        }
                        if (remote != null && remoteResult == null) {
                            Text(stringResource(if (mode.photo) R.string.wallpaper_photo_loading else R.string.smb_loading))
                        } else if (remoteResult?.isFailure == true) {
                            SettingsItem(
                                onClick = { refresh++ },
                                headlineContent = { Text(stringResource(R.string.smb_retry)) },
                                supportingContent = { Text(stringResource(R.string.smb_connection_failed)) },
                            )
                        } else if ((remote != null || hasAccess) && entries.isEmpty() && folder == null) {
                            Text(stringResource(when {
                                mode.photo -> R.string.wallpaper_photos_empty
                                remote != null -> R.string.smb_empty
                                mode.folder -> R.string.wallpaper_folders_empty
                                else -> R.string.wallpaper_videos_empty
                            }))
                        }
                        key(local, remote) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
                                itemsIndexed(entries, key = { _, entry -> "${entry.folder}:${entry.source}" }) { index, entry ->
                                    SettingsItem(
                                        modifier = if (index == 0) Modifier.focusRequester(initialFocus()) else Modifier,
                                        onClick = {
                                            when {
                                                entry.folder && local != null -> localPath = local + entry.name
                                                entry.folder -> smbPath = smbPath + entry.source
                                                entry.source in checked -> checked = checked - entry.source
                                                else -> checked = checked + entry.source
                                            }
                                        },
                                        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = { Icon(when {
                                            entry.folder -> AppIcons.Folder
                                            mode.photo -> AppIcons.Image
                                            else -> AppIcons.Play
                                        }, contentDescription = null) },
                                        trailingContent = if (entry.folder) null else { { CheckMark(entry.source in checked) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
