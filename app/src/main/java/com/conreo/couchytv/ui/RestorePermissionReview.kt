package com.conreo.couchytv.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.BetaDiagnostics
import com.conreo.couchytv.data.LauncherConfig
import com.conreo.couchytv.data.READ_TV_LISTINGS

private data class RestoredRuntimePermission(
    val name: String,
    @StringRes val label: Int,
    val required: (LauncherConfig) -> Boolean,
)

// Add a rule here when an enabled backup setting requires another runtime permission.
private val restoredRuntimePermissions = listOf(
    RestoredRuntimePermission(
        READ_TV_LISTINGS,
        R.string.restore_permission_tv_listings,
        LauncherConfig::appContentPreviewEnabled,
    ),
)

@Composable
internal fun RestorePermissionReview(config: LauncherConfig, onFinished: suspend () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    var approved by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var inFlight by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        inFlight?.let {
            BetaDiagnostics.event("Restore permission result permission=$it granted=$granted")
            attempted = ArrayList(attempted + it)
        }
        inFlight = null
    }
    val missing = restoredRuntimePermissions.filter {
        it.required(config) && it.name !in attempted &&
            context.checkSelfPermission(it.name) != PackageManager.PERMISSION_GRANTED
    }
    val next = missing.firstOrNull()

    LaunchedEffect(lifecycleState, approved, inFlight, next) {
        if (lifecycleState != Lifecycle.State.RESUMED || inFlight != null) return@LaunchedEffect
        if (next == null) {
            onFinished()
        } else if (approved) {
            inFlight = next.name
            BetaDiagnostics.event("Restore permission requested permission=${next.name}")
            launcher.launch(next.name)
        }
    }

    if (missing.isNotEmpty() && !approved) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(shape = RoundedCornerShape(20.dp)) {
                Column(
                    Modifier.widthIn(max = 520.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.restore_permissions_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.restore_permissions_body), style = MaterialTheme.typography.bodyMedium)
                    missing.forEach {
                        Text(stringResource(it.label), style = MaterialTheme.typography.bodyMedium)
                    }
                    val focus = initialFocus()
                    SettingsButton(onClick = { approved = true }, modifier = Modifier.focusRequester(focus)) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}
