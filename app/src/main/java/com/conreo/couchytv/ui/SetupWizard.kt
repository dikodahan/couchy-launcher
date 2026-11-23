package com.conreo.couchytv.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R

/**
 * First-launch wizard. Detects the device type and guides the user to set
 * Couchy as the default home — including certified Google TV devices, where
 * the system UI does not allow changing the home app and ADB is required.
 * [onVpnChosen] receives the picked VPN package, or null to hide the VPN icon.
 */
@Composable
fun SetupWizard(onDone: () -> Unit, onVpnChosen: (String?) -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    val isDefault = defaultHomePackage(context) == context.packageName
    BackHandler(enabled = step > 0) { step-- }

    Box(
        Modifier
            .fillMaxSize()
            .background(WALLPAPERS[0].brush()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(680.dp)
                .padding(horizontal = 36.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Branding: the couch, on every wizard step.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_couch),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
            when (step) {
                0 -> {
                    Title(stringResource(R.string.wizard_welcome_title))
                    Body(stringResource(R.string.wizard_welcome_body))
                    Body(stringResource(R.string.wizard_privacy_body))
                    NavRow(nextLabel = stringResource(R.string.next)) { step = 1 }
                }
                1 -> {
                    Title(stringResource(R.string.wizard_home_title))
                    HomeLauncherControls(wizardContinue = { step = 2 })
                }
                2 -> {
                    Title(stringResource(R.string.wizard_vpn_title))
                    Body(stringResource(R.string.wizard_vpn_body))
                    val vpns = remember { vpnApps(context) }
                    if (vpns.isEmpty()) {
                        Body(stringResource(R.string.wizard_vpn_none))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vpns.forEach { (pkg, label) ->
                                Button(onClick = { onVpnChosen(pkg); step = 3 }) { Text(label) }
                            }
                        }
                    }
                    NavRow(
                        backAction = { step = 1 },
                        nextLabel = stringResource(R.string.wizard_vpn_skip),
                    ) { onVpnChosen(null); step = 3 }
                }
                else -> {
                    Title(stringResource(R.string.wizard_done_title))
                    Body(
                        stringResource(R.string.wizard_tips) +
                            if (!isDefault) "\n\n" + stringResource(R.string.wizard_rerun_note) else ""
                    )
                    NavRow(
                        backAction = { step = 2 },
                        nextLabel = stringResource(R.string.wizard_start),
                    ) { onDone() }
                }
            }
        }
    }
}

/* ------------------------------ pieces ------------------------------ */

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White)
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.85f),
    )
}

@Composable
private fun NavRow(
    backAction: (() -> Unit)? = null,
    nextLabel: String,
    nextAction: () -> Unit,
) {
    // Focus the primary (next) button on entry so every step opens with a
    // button selected and the D-pad ready.
    val nextFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { nextFocus.requestFocus() }
    }
    Row(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (backAction != null) {
            Button(onClick = backAction) { Text(stringResource(R.string.back)) }
        }
        Button(onClick = nextAction, modifier = Modifier.focusRequester(nextFocus)) {
            Text(nextLabel)
        }
    }
}

/* ------------------------------ helpers ------------------------------ */

fun defaultHomePackage(context: Context): String? {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager
        .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
}

/** Installed VPN apps = anything implementing android.net.VpnService. */
private fun vpnApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    return pm.queryIntentServices(Intent("android.net.VpnService"), 0)
        .mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (si.packageName == context.packageName) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: si.packageName
            si.packageName to label
        }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}
