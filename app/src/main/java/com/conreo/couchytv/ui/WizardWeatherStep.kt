package com.conreo.couchytv.ui

import android.text.BidiFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.LauncherConfig
import com.conreo.couchytv.data.WEATHER_SRC_LATLONG
import com.conreo.couchytv.data.WeatherLocation
import com.conreo.couchytv.data.WeatherRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WizardWeatherStep(
    config: LauncherConfig,
    useInitialIpLocation: Boolean,
    onBack: () -> Unit,
    onSave: suspend (WeatherLocation?) -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var location by remember {
        mutableStateOf(
            WeatherLocation(config.weatherLat, config.weatherLon, config.weatherCityLabel)
                .takeIf {
                    config.weatherEnabled && config.weatherLocationSource == WEATHER_SRC_LATLONG &&
                        it.isValid() && (it.label.isNotBlank() || it.latitude != 0.0 || it.longitude != 0.0)
                },
        )
    }
    var findingCity by remember { mutableStateOf(false) }
    var locatingIp by remember { mutableStateOf(useInitialIpLocation && location == null) }
    var ipFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(locatingIp) {
        if (!locatingIp) return@LaunchedEffect
        val detected = withContext(Dispatchers.IO) { WeatherRepository.previewIpLocation() }
        if (detected != null) location = detected else ipFailed = true
        locatingIp = false
    }

    fun save(choice: WeatherLocation?) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                onSave(choice)
                onNext()
            } catch (_: IOException) {
                Actions.toast(context, context.getString(R.string.toast_config_save_failed))
            } finally {
                saving = false
            }
        }
    }

    BackHandler(enabled = saving) {}
    val selected = location
    val focus = key(selected, ipFailed, locatingIp) { initialFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.item_weather), style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(stringResource(R.string.weather_hint), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(SettingsControlGap)) {
            SettingsButton(
                onClick = {
                    ipFailed = false
                    locatingIp = true
                },
                enabled = !locatingIp && !saving,
                modifier = if (selected == null && !ipFailed && !locatingIp) Modifier.focusRequester(focus) else Modifier,
            ) { Text(stringResource(R.string.weather_ip_enable)) }
            SettingsButton(
                onClick = { findingCity = true },
                enabled = !saving,
                modifier = if (selected == null && (ipFailed || locatingIp)) Modifier.focusRequester(focus) else Modifier,
            ) { Text(stringResource(R.string.weather_find_city)) }
        }
        if (locatingIp || ipFailed) {
            Text(
                stringResource(if (locatingIp) R.string.wizard_weather_locating else R.string.wizard_weather_ip_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
        if (selected != null) {
            val label = selected.label.ifBlank { "${selected.latitude}, ${selected.longitude}" }
            val bidi = BidiFormatter.getInstance(context.resources.configuration.locales[0])
            Text(
                stringResource(R.string.weather_ip_city, bidi.unicodeWrap(label)),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            SettingsButton(
                onClick = { save(selected) },
                enabled = !locatingIp && !saving,
                modifier = Modifier.focusRequester(focus),
            ) { Text(stringResource(R.string.wizard_weather_confirm)) }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(SettingsControlGap)) {
            SettingsButton(onClick = onBack, enabled = !saving) { Text(stringResource(R.string.back)) }
            SettingsButton(onClick = { save(null) }, enabled = !saving) { Text(stringResource(R.string.off)) }
        }
    }
    if (findingCity) {
        WeatherCityPicker(
            onDismiss = { findingCity = false },
            onSelect = { city ->
                locatingIp = false
                location = city.toLocation()
                ipFailed = false
                findingCity = false
            },
        )
    }
}
