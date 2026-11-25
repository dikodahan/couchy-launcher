package com.conreo.couchytv.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.conreo.couchytv.R
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.conreo.couchytv.data.NetStatus
import com.conreo.couchytv.data.WEATHER_DISPLAY_ICON
import com.conreo.couchytv.data.WEATHER_DISPLAY_LOWHIGH
import com.conreo.couchytv.data.WeatherData
import com.conreo.couchytv.data.WeatherIconKind
import kotlin.math.roundToInt

internal fun weatherIconFor(kind: WeatherIconKind, isDay: Boolean): ImageVector = when (kind) {
    WeatherIconKind.Clear -> if (isDay) AppIcons.WeatherClear else AppIcons.WeatherMoon
    WeatherIconKind.PartlyCloudy -> AppIcons.WeatherPartlyCloudy
    WeatherIconKind.Cloudy -> AppIcons.WeatherCloudy
    WeatherIconKind.Fog -> AppIcons.WeatherFog
    WeatherIconKind.Rain -> AppIcons.WeatherRain
    WeatherIconKind.Snow -> AppIcons.WeatherSnow
    WeatherIconKind.Storm -> AppIcons.WeatherStorm
}

@Composable
internal fun labelFor(kind: WeatherIconKind): String = stringResource(
    when (kind) {
        WeatherIconKind.Clear -> R.string.weather_kind_clear
        WeatherIconKind.PartlyCloudy -> R.string.weather_kind_partly_cloudy
        WeatherIconKind.Cloudy -> R.string.weather_kind_cloudy
        WeatherIconKind.Fog -> R.string.weather_kind_fog
        WeatherIconKind.Rain -> R.string.weather_kind_rain
        WeatherIconKind.Snow -> R.string.weather_kind_snow
        WeatherIconKind.Storm -> R.string.weather_kind_storm
    },
)

@Composable
fun StatusBar(
    net: NetStatus,
    time: String,
    date: String,
    showVpn: Boolean,
    glass: Boolean,
    weather: WeatherData?,
    weatherPlaceholder: Int?,
    weatherDisplay: Int,
    onVpnClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onWeatherClick: () -> Unit,
    onWeatherLongClick: () -> Unit,
    onUpdateClick: (() -> Unit)? = null,
) {
    // Optional glass panel behind the icon cluster — same look as the dock
    // panel, corners follow the roundness setting.
    val cluster = if (glass) {
        Modifier
            .launcherGlass(LocalCornerRadius.current, GlassPanel.Status)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    } else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = cluster,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Order: weather · VPN · network · settings · date · clock (clock far right)
        if (weather != null || weatherPlaceholder != null) {
            WeatherChip(weather, weatherPlaceholder, weatherDisplay, onWeatherClick, onWeatherLongClick)
        }
        if (showVpn) {
            StatusIcon(
                icon = AppIcons.Vpn,
                active = net.vpn,
                contentDescription = stringResource(R.string.cd_vpn),
                onClick = onVpnClick,
            )
        }
        StatusIcon(
            icon = when {
                net.ethernet -> AppIcons.Ethernet
                net.wifi -> AppIcons.Wifi
                else -> AppIcons.WifiOff
            },
            active = net.connected,
            contentDescription = stringResource(R.string.cd_network),
            onClick = onNetworkClick,
        )
        StatusIcon(
            icon = AppIcons.Gear,
            active = true,
            contentDescription = stringResource(R.string.cd_settings),
            onClick = onSettingsClick,
        )
        if (onUpdateClick != null) UpdateBell(onUpdateClick)
        if (date.isNotEmpty()) {
            Text(
                text = date,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            // Tabular figures: digits keep a fixed width so the clock never jitters.
            text = time,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = Color.White,
            modifier = Modifier.padding(start = 10.dp),
        )
      }
    }
}

@Composable
private fun WeatherChip(
    weather: WeatherData?,
    placeholder: Int?,
    display: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // Same D-pad long-press detection as AppCard: a remote's OK button only
    // reports onLongClick to the pointer/mouse path, so a held-down OK is
    // timed by hand and the click that follows its release is suppressed.
    var downAt by remember { mutableLongStateOf(0L) }
    var lastKeyDown by remember { mutableLongStateOf(0L) }
    var longPressAt by remember { mutableLongStateOf(0L) }
    fun fireLongPress() {
        longPressAt = SystemClock.uptimeMillis()
        onLongClick()
    }
    Surface(
        onClick = { if (SystemClock.uptimeMillis() - longPressAt > 1_000L) onClick() },
        onLongClick = { fireLongPress() },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = statusControlColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = STATUS_FOCUS_SCALE),
        modifier = Modifier.onPreviewKeyEvent { e ->
            val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            if (!select || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val now = SystemClock.uptimeMillis()
            if (lastKeyDown == 0L || now - lastKeyDown > 300) {
                downAt = now
                lastKeyDown = now
                return@onPreviewKeyEvent false
            }
            lastKeyDown = now
            if (now - longPressAt > 1_000L && now - downAt >= 450) fireLongPress()
            true
        },
    ) {
        val text = if (weather == null) placeholder?.let { stringResource(it) }
        else when (display) {
            WEATHER_DISPLAY_ICON -> null
            WEATHER_DISPLAY_LOWHIGH -> "${weather.low.roundToInt()}°/${weather.high.roundToInt()}°"
            else -> "${weather.temp.roundToInt()}°"
        }
        Row(
            modifier = Modifier.heightIn(min = STATUS_SLOT_SIZE).padding(
                start = if (weather == null) 10.dp else 0.dp,
                end = if (text != null) 10.dp else 0.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (weather != null) {
                StatusGlyph(
                    icon = weatherIconFor(weather.kind, weather.isDay),
                    contentDescription = stringResource(R.string.cd_weather),
                    active = true,
                )
            }
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = statusControlColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = STATUS_FOCUS_SCALE),
    ) {
        StatusGlyph(icon, contentDescription, active)
    }
}

@Composable
private fun UpdateBell(onClick: () -> Unit) {
    val swing = rememberInfiniteTransition(label = "updateBell").animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "bellRotation",
    )
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = statusControlColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = STATUS_FOCUS_SCALE),
    ) {
        Box(Modifier.size(STATUS_SLOT_SIZE), contentAlignment = Alignment.Center) {
            key(LocalHomeArtworkGeneration.current) {
                Icon(
                    imageVector = AppIcons.Bell,
                    contentDescription = stringResource(R.string.update_bell),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).graphicsLayer {
                        rotationZ = swing.value
                        transformOrigin = TransformOrigin(0.5f, 0.25f)
                    },
                )
            }
        }
    }
}

private const val STATUS_FOCUS_SCALE = 1.18f
private val STATUS_SLOT_SIZE = 40.dp

@Composable
private fun statusControlColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    focusedContainerColor = Color.White.copy(alpha = 0.22f),
    contentColor = Color.White,
    focusedContentColor = Color.White,
)

@Composable
private fun StatusGlyph(icon: ImageVector, contentDescription: String, active: Boolean) {
    Box(Modifier.size(STATUS_SLOT_SIZE), contentAlignment = Alignment.Center) {
        key(LocalHomeArtworkGeneration.current) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = if (active) 0.95f else 0.35f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
