package com.conreo.couchytv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.BetaDiagnostics
import com.conreo.couchytv.data.IndexedWallpaperFolder
import com.conreo.couchytv.data.hasFullWallpaperVideoAccess
import com.conreo.couchytv.data.hasWallpaperVideoAccess
import com.conreo.couchytv.data.indexedWallpaperFolders
import com.conreo.couchytv.data.wallpaperVideoPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun IndexedVideoFolderPicker(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val hasAccess = remember(refresh) { context.hasWallpaperVideoAccess() }
    val fullAccess = remember(refresh) { context.hasFullWallpaperVideoAccess() }
    val folders by produceState<Result<List<IndexedWallpaperFolder>>?>(null, refresh) {
        value = if (!hasAccess) null else try {
            Result.success(withContext(Dispatchers.IO) { context.indexedWallpaperFolders() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BetaDiagnostics.failure("Indexed video folder list", error)
            Result.failure(error)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp)) {
            LazyColumn(
                Modifier.widthIn(min = 330.dp, max = 500.dp).heightIn(max = 420.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
            ) {
                item { SettingsTitle(stringResource(R.string.pick_video_folder)) }
                if (!fullAccess) {
                    item {
                        val focus = initialFocus()
                        SettingsItem(
                            modifier = Modifier.focusRequester(focus),
                            onClick = { permissions.launch(wallpaperVideoPermissions()) },
                            headlineContent = { Text(stringResource(R.string.wallpaper_folder_access)) },
                        )
                    }
                }
                val result = folders
                val entries = result?.getOrNull().orEmpty()
                if (hasAccess && result != null && entries.isEmpty()) {
                    item {
                        Text(stringResource(
                            if (result.isFailure) R.string.wallpaper_video_access_failed else R.string.wallpaper_folders_empty,
                        ))
                    }
                }
                itemsIndexed(entries, key = { _, folder -> folder.source }) { index, folder ->
                    val focus = if (index == 0 && fullAccess) Modifier.focusRequester(initialFocus()) else Modifier
                    SettingsItem(
                        modifier = focus,
                        onClick = { onSelect(folder.source) },
                        headlineContent = { Text(folder.name) },
                        supportingContent = if (folder.location == folder.name || folder.location.isEmpty()) null
                            else { { Text(folder.location) } },
                        leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
                    )
                }
                if (fullAccess && entries.isEmpty()) {
                    item {
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
