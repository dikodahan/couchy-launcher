package com.conreo.couchytv.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

internal val SettingsPageInset = 20.dp
internal val SettingsRowGap = 6.dp
internal val SettingsControlGap = 8.dp
private val ControlShape = RoundedCornerShape(12.dp)
private val FocusFill = Color(0xFFF1F2F4)
private val FocusContent = Color(0xFF14151A)

@Composable
internal fun SettingsPage(
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(SettingsPageInset).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
        content = content,
    )
}

@Composable
internal fun SettingsTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = SettingsControlGap, bottom = 10.dp),
    )
}

@Composable
internal fun SettingsSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SettingsControlGap, top = 12.dp, bottom = 4.dp),
    )
}

/** The TV surface supplies one foreground to the label, description, and controls in each state. */
@Composable
internal fun SettingsControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        focused -> FocusFill
        filled -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val foreground = if (focused) FocusContent else MaterialTheme.colorScheme.onSurface
    // Programmatic focus can reach a freshly opened row before the TV surface
    // starts collecting focus interactions. Use the focus node for both colors.
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(ControlShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(focusedDisabledBorder = Border.None),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = foreground,
            focusedContainerColor = container,
            focusedContentColor = foreground,
            pressedContainerColor = container,
            pressedContentColor = foreground,
            disabledContainerColor = container,
            disabledContentColor = foreground,
        ),
        content = content,
    )
}

@Composable
internal fun SettingsItem(
    onClick: () -> Unit,
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    SettingsControl(onClick, modifier.fillMaxWidth(), enabled) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingContent != null) {
                Box(Modifier.defaultMinSize(minWidth = 24.dp), Alignment.Center, content = leadingContent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium, content = headlineContent)
                if (supportingContent != null) {
                    val foreground = LocalContentColor.current
                    CompositionLocalProvider(LocalContentColor provides foreground.copy(alpha = foreground.alpha * 0.8f)) {
                        ProvideTextStyle(MaterialTheme.typography.bodySmall, content = supportingContent)
                    }
                }
            }
            if (trailingContent != null) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge, content = trailingContent)
            }
        }
    }
}

@Composable
internal fun CheckMark(checked: Boolean) {
    val foreground = LocalContentColor.current
    val focused = foreground == FocusContent
    val mark = if (focused) foreground else MaterialTheme.colorScheme.primary.copy(alpha = foreground.alpha)
    val outline = if (checked) mark else foreground.copy(alpha = foreground.alpha * 0.65f)
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier.size(22.dp)
            .semantics { toggleableState = ToggleableState(checked) }
            .background(if (checked) mark else Color.Transparent, shape)
            .border(2.dp, outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                AppIcons.Check,
                contentDescription = null,
                tint = if (focused) FocusFill else FocusContent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun SettingsIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    SettingsControl(onClick = onClick, modifier = Modifier.size(42.dp), filled = true) {
        Icon(icon, contentDescription = label, modifier = Modifier.align(Alignment.Center).size(20.dp))
    }
}

@Composable
internal fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    SettingsControl(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Button },
        enabled = enabled,
        filled = true,
    ) {
        Row(
            Modifier.defaultMinSize(minWidth = 58.dp, minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

/** Text fields keep the editing surface dark and use the shared focus color for the outline. */
@Composable
internal fun Modifier.settingsFieldFrame(focused: Boolean): Modifier =
    background(MaterialTheme.colorScheme.surfaceVariant, ControlShape)
        .border(2.dp, if (focused) FocusFill else Color.Transparent, ControlShape)

@Composable
internal fun initialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Wait for the target to attach without pulling focus back after the user starts navigating.
        repeat(30) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos {}
        }
    }
    return requester
}
