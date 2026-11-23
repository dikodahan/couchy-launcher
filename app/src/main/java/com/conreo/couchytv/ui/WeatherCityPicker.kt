package com.conreo.couchytv.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.WeatherCity
import com.conreo.couchytv.data.searchWeatherCities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WeatherCityPicker(onDismiss: () -> Unit, onSelect: (WeatherCity) -> Unit) {
    val context = LocalContext.current
    val language = context.resources.configuration.locales[0].language
    var term by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<WeatherCity>?>(null) }

    LaunchedEffect(term, editing, language) {
        if (!editing && term.isNotEmpty()) {
            matches = withContext(Dispatchers.IO) { searchWeatherCities(term, language) }
            loading = false
        }
    }
    if (editing) {
        TextEntryDialog(
            title = stringResource(R.string.weather_find_city),
            initial = term,
            submitLabel = R.string.weather_city_search,
            onSave = { input ->
                if (input.length !in 2..80 || input.any { it.isISOControl() }) {
                    Actions.toast(context, context.getString(R.string.weather_city_invalid))
                } else {
                    term = input
                    matches = null
                    loading = true
                    editing = false
                }
            },
            onDismiss = onDismiss,
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.widthIn(max = 460.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.weather_city_choose), style = MaterialTheme.typography.titleMedium)
                val cities = matches.orEmpty()
                val first = key(cities.isNotEmpty()) { initialFocus() }
                if (cities.isEmpty()) {
                    Text(stringResource(when {
                        loading -> R.string.weather_city_searching
                        matches == null -> R.string.weather_city_failed
                        else -> R.string.weather_city_no_matches
                    }))
                } else {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        itemsIndexed(cities) { index, city ->
                            SettingsItem(
                                onClick = { onSelect(city) },
                                headlineContent = { Text(city.name) },
                                supportingContent = if (city.description.isNotEmpty()) ({ Text(city.description) }) else null,
                                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { editing = true },
                        modifier = if (cities.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    ) { Text(stringResource(R.string.weather_city_search_again)) }
                    Button(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}
