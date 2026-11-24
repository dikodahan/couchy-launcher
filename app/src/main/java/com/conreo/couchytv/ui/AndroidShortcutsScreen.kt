package com.conreo.couchytv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R

@Composable
internal fun AndroidShortcutsScreen() {
    val context = LocalContext.current
    val focus = initialFocus()
    var choosingTimeout by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.android_shortcuts),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp),
        )
        SettingsItem(
            onClick = { Actions.openScreenPositionSettings(context) },
            headlineContent = { Text(stringResource(R.string.screen_resize)) },
            leadingContent = { Icon(AppIcons.Display, contentDescription = null) },
            modifier = Modifier.focusRequester(focus),
        )
        SettingsItem(
            onClick = { Actions.openGboardLanguages(context) },
            headlineContent = { Text(stringResource(R.string.shortcut_keyboard_languages)) },
            leadingContent = { Icon(AppIcons.Language, contentDescription = null) },
        )
        SettingsItem(
            onClick = { Actions.openScreensaverSettings(context) },
            headlineContent = { Text(stringResource(R.string.shortcut_screensaver)) },
            leadingContent = { Icon(AppIcons.WeatherMoon, contentDescription = null) },
        )
        SettingsItem(
            onClick = { choosingTimeout = !Actions.openPowerTimeoutSettings(context) },
            headlineContent = { Text(stringResource(R.string.shortcut_power_timeout)) },
            leadingContent = { Icon(AppIcons.Bolt, contentDescription = null) },
        )
        SettingsItem(
            onClick = { Actions.openSystemSettings(context) },
            headlineContent = { Text(stringResource(R.string.item_android_settings)) },
            leadingContent = { Icon(AppIcons.Gear, contentDescription = null) },
        )
    }
    if (choosingTimeout) AndroidPowerTimeoutDialog(onDismiss = { choosingTimeout = false })
}
