package com.conreo.couchytv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.WEATHER_FORECAST_DAILY
import com.conreo.couchytv.data.WEATHER_FORECAST_HOURLY
import com.conreo.couchytv.data.WEATHER_ICONS_ANIMATED
import com.conreo.couchytv.data.WEATHER_ICONS_COLOR
import com.conreo.couchytv.data.WeatherData
import com.conreo.couchytv.data.WeatherIconKind
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun WeatherForecastDialog(
    weather: WeatherData,
    view: Int,
    iconStyle: Int,
    h24: Boolean,
    onDismiss: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale, h24) {
        DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", locale)
    }
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE, MMM d", locale) }
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
    val showHours = view != WEATHER_FORECAST_DAILY
    val showDays = view != WEATHER_FORECAST_HOURLY
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A single animation drives every icon and is disposed when the dialog closes.
        val motion = if (iconStyle == WEATHER_ICONS_ANIMATED) {
            rememberInfiniteTransition(label = "weather").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing)),
                label = "weatherIconMotion",
            )
        } else null
        BoxWithConstraints(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val rowHeight = if (maxHeight < 420.dp) 28.dp else 38.dp
            val showConditions = !showHours || !showDays || maxWidth >= 620.dp
            Surface(
                shape = RoundedCornerShape(20.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.widthIn(max = if (showHours && showDays) 800.dp else 490.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val closeFocus = initialFocus()
                    Row(
                        Modifier.fillMaxWidth().background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            RoundedCornerShape(12.dp),
                        ).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForecastIcon(weather.kind, weather.isDay, iconStyle, motion, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.weather_forecast_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${weather.temp.roundToInt()}° · ${labelFor(weather.kind)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            weather.observedAt?.let { observed ->
                                Text(
                                    "${dayFormat.format(observed)} · ${timeFormat.format(observed)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Button(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus)) {
                            Text(stringResource(R.string.menu_close))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (showHours) {
                            ForecastPane(stringResource(R.string.weather_forecast_hourly), Modifier.weight(1f)) {
                                if (weather.hourly.isEmpty()) Text(stringResource(R.string.weather_forecast_no_data))
                                for (hour in weather.hourly) {
                                    ForecastRow(
                                        timeFormat.format(hour.time),
                                        hour.kind,
                                        hour.isDay,
                                        "${hour.temperature.roundToInt()}°",
                                        hour.precipChance.takeIf { it >= 0 }?.let { percent.format(it / 100.0) },
                                        iconStyle,
                                        motion,
                                        rowHeight,
                                        showConditions,
                                    )
                                }
                            }
                        }
                        if (showDays) {
                            ForecastPane(stringResource(R.string.weather_forecast_daily), Modifier.weight(1f)) {
                                if (weather.daily.isEmpty()) Text(stringResource(R.string.weather_forecast_no_data))
                                for (day in weather.daily) {
                                    val date = runCatching { dayFormat.format(LocalDate.parse(day.date)) }
                                        .getOrDefault(day.date)
                                    ForecastRow(
                                        date,
                                        day.kind,
                                        true,
                                        "${day.high.roundToInt()}° / ${day.low.roundToInt()}°",
                                        day.precipChance.takeIf { it >= 0 }?.let { percent.format(it / 100.0) },
                                        iconStyle,
                                        motion,
                                        rowHeight,
                                        showConditions,
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

@Composable
private fun ForecastPane(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.background(
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
            RoundedCornerShape(12.dp),
        ).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        content()
    }
}

@Composable
private fun ForecastRow(
    label: String,
    kind: WeatherIconKind,
    isDay: Boolean,
    temperature: String,
    precipitation: String?,
    style: Int,
    motion: State<Float>?,
    height: Dp,
    showCondition: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = height),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        ForecastIcon(kind, isDay, style, motion, 21.dp, description = if (showCondition) null else labelFor(kind))
        if (showCondition) {
            Text(labelFor(kind), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (precipitation != null) {
            Text(precipitation, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(temperature, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

@Composable
private fun ForecastIcon(
    kind: WeatherIconKind,
    isDay: Boolean,
    style: Int,
    motion: State<Float>?,
    size: Dp,
    description: String? = null,
) {
    if (style == WEATHER_ICONS_COLOR || style == WEATHER_ICONS_ANIMATED) {
        ColoredForecastIcon(kind, isDay, motion, Modifier.size(size), description)
    } else {
        Icon(
            weatherIconFor(kind, isDay),
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size),
        )
    }
}
