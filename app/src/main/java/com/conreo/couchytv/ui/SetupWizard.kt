package com.conreo.couchytv.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.LANGUAGES
import com.conreo.couchytv.data.LANGUAGE_REGIONS
import com.conreo.couchytv.data.LauncherConfig
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SetupWizard(
    config: LauncherConfig,
    saving: Boolean,
    languageApplied: Boolean,
    onChooseLanguage: suspend (String) -> Unit,
    onDone: (pressHome: Boolean, vpnPackage: String?, useIpWeather: Boolean?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var vpnPackage by rememberSaveable { mutableStateOf(config.vpnApp.takeIf { config.showVpnButton }) }
    var useIpWeather by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var pendingLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }
    val scroll = key(step) { rememberScrollState() }
    BackHandler(enabled = step == 1 || step == 2) { step-- }

    // Wait for the recreated activity to attach the new resources and direction
    // before drawing the next question. The pending choice survives recreation.
    LaunchedEffect(pendingLanguage, config.language, languageApplied) {
        if (pendingLanguage == config.language && languageApplied) {
            step = 1
            pendingLanguage = null
        }
    }
    fun selectLanguage(language: String) {
        if (pendingLanguage != null) return
        choosingLanguage = false
        pendingLanguage = language
        if (language == config.language) return
        scope.launch {
            try {
                onChooseLanguage(language)
            } catch (_: IOException) {
                pendingLanguage = null
                Actions.toast(context, context.getString(R.string.toast_config_save_failed))
            }
        }
    }

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BrandLogo(Modifier.size(44.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
            when (step) {
                0 -> {
                    Title(stringResource(R.string.wizard_language_title))
                    val languageFocus = initialFocus()
                    SettingsItem(
                        onClick = { choosingLanguage = true },
                        enabled = pendingLanguage == null,
                        modifier = Modifier.fillMaxWidth().focusRequester(languageFocus),
                        headlineContent = { Text(languageName(config.language)) },
                        leadingContent = { LanguageFlag(config.language) },
                        trailingContent = { Icon(AppIcons.Down, contentDescription = null) },
                    )
                    Button(
                        onClick = { selectLanguage(config.language) },
                        enabled = pendingLanguage == null,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.next)) }
                }
                1 -> {
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
                                    step = 2
                                }) { Text(label) }
                            }
                        }
                    }
                    NavRow(backAction = { step = 0 }, nextLabel = stringResource(R.string.wizard_vpn_skip)) {
                        vpnPackage = null
                        step = 2
                    }
                }
                2 -> {
                    Title(stringResource(R.string.item_weather))
                    Body(stringResource(R.string.weather_hint))
                    Body(stringResource(R.string.weather_geoip_notice))
                    Button(onClick = {
                        useIpWeather = true
                        step = 3
                    }) { Text(stringResource(R.string.weather_ip_enable)) }
                    if (config.weatherEnabled) {
                        Button(onClick = {
                            useIpWeather = false
                            step = 3
                        }) { Text(stringResource(R.string.off)) }
                    }
                    NavRow(
                        backAction = { step = 1 },
                        nextLabel = stringResource(if (config.weatherEnabled) R.string.next else R.string.off),
                    ) {
                        useIpWeather = null
                        step = 3
                    }
                }
                else -> {
                    Title(stringResource(R.string.wizard_home_title))
                    HomeLauncherControls(
                        wizardBack = { step = 2 },
                        wizardSaving = saving,
                        onWizardDone = { pressHome -> onDone(pressHome, vpnPackage, useIpWeather) },
                    )
                }
            }
        }
    }
    if (choosingLanguage) LanguageDropdown(config.language, ::selectLanguage) { choosingLanguage = false }
}

@Composable
internal fun languageName(code: String): String =
    if (code.isEmpty()) stringResource(R.string.lang_system)
    else Locale.forLanguageTag(code).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.uppercase() }

@Composable
private fun LanguageFlag(code: String) {
    val region = LANGUAGE_REGIONS[code].orEmpty()
    if (region.isEmpty()) {
        Icon(AppIcons.Language, contentDescription = null, modifier = Modifier.size(24.dp))
    } else {
        val flag = buildString {
            for (letter in region) appendCodePoint(0x1F1E6 + (letter - 'A'))
        }
        Text(flag, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LanguageDropdown(selected: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val current = selected.takeIf { it in LANGUAGES }.orEmpty()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.width(390.dp).padding(16.dp)) {
                Title(stringResource(R.string.item_language))
                val focus = initialFocus()
                LazyColumn(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = LANGUAGES.indexOf(current)),
                    modifier = Modifier.heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(LANGUAGES, key = { it }) { code ->
                        SettingsItem(
                            onClick = { onChoose(code) },
                            modifier = if (code == current) Modifier.focusRequester(focus) else Modifier,
                            headlineContent = { Text(languageName(code)) },
                            leadingContent = { LanguageFlag(code) },
                            trailingContent = { CheckMark(code == current) },
                        )
                    }
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
