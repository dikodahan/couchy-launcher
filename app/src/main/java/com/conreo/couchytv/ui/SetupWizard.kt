package com.conreo.couchytv.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.LauncherConfig

@Composable
fun SetupWizard(
    config: LauncherConfig,
    saving: Boolean,
    onDone: (pressHome: Boolean, vpnPackage: String?, useIpWeather: Boolean?) -> Unit,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var vpnPackage by rememberSaveable { mutableStateOf(config.vpnApp.takeIf { config.showVpnButton }) }
    var useIpWeather by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val scroll = key(step) { rememberScrollState() }
    BackHandler(enabled = step == 1) { step = 0 }

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
                .verticalScroll(scroll)
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
                    Title(stringResource(R.string.wizard_vpn_title))
                    Body(stringResource(R.string.wizard_vpn_body))
                    val vpns = remember(context) { vpnApps(context) }
                    if (vpns.isEmpty()) {
                        Body(stringResource(R.string.wizard_vpn_none))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            vpns.forEach { (pkg, label) ->
                                Button(onClick = {
                                    vpnPackage = pkg
                                    step = 1
                                }) { Text(label) }
                            }
                        }
                    }
                    NavRow(nextLabel = stringResource(R.string.wizard_vpn_skip)) {
                        vpnPackage = null
                        step = 1
                    }
                }
                1 -> {
                    Title(stringResource(R.string.item_weather))
                    Body(stringResource(R.string.weather_hint))
                    Body(stringResource(R.string.weather_geoip_notice))
                    Button(onClick = {
                        useIpWeather = true
                        step = 2
                    }) { Text(stringResource(R.string.weather_ip_enable)) }
                    if (config.weatherEnabled) {
                        Button(onClick = {
                            useIpWeather = false
                            step = 2
                        }) { Text(stringResource(R.string.off)) }
                    }
                    NavRow(
                        backAction = { step = 0 },
                        nextLabel = stringResource(if (config.weatherEnabled) R.string.next else R.string.off),
                    ) {
                        useIpWeather = null
                        step = 2
                    }
                }
                else -> {
                    Title(stringResource(R.string.wizard_home_title))
                    HomeLauncherControls(
                        wizardBack = { step = 1 },
                        wizardSaving = saving,
                        onWizardDone = { pressHome -> onDone(pressHome, vpnPackage, useIpWeather) },
                    )
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
    val nextFocus = initialFocus()
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
