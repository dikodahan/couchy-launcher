package com.conreo.couchytv.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import com.conreo.couchytv.BuildConfig
import com.conreo.couchytv.data.RecommendedCatalog
import com.conreo.couchytv.data.RecommendedApps
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.AppEntry
import com.conreo.couchytv.data.AppRepository
import com.conreo.couchytv.data.BuiltinAerials
import com.conreo.couchytv.data.CategoryCfg
import com.conreo.couchytv.data.CloudNotConfigured
import com.conreo.couchytv.data.CloudSession
import com.conreo.couchytv.data.ConfigBackup
import com.conreo.couchytv.data.ConfigStore
import com.conreo.couchytv.data.DATE_FORMATS
import com.conreo.couchytv.data.IconPack
import com.conreo.couchytv.data.ICON_SCALE_EXTRA_SMALL
import com.conreo.couchytv.data.GLASS_STYLE_LIQUID
import com.conreo.couchytv.data.LAYOUT_DOCK
import com.conreo.couchytv.data.LAYOUT_GRID
import com.conreo.couchytv.data.LauncherConfig
import com.conreo.couchytv.data.HomeDiagnostics
import com.conreo.couchytv.data.NeedTelegramLogin
import com.conreo.couchytv.data.TelegramRequestFailure
import com.conreo.couchytv.data.ParentalControls
import com.conreo.couchytv.data.ParentalState
import com.conreo.couchytv.data.READ_TV_LISTINGS
import com.conreo.couchytv.data.TopShelf
import com.conreo.couchytv.data.TelegramCloudBackup
import com.conreo.couchytv.data.WEATHER_CLICK_APP
import com.conreo.couchytv.data.WEATHER_SRC_GEOIP
import com.conreo.couchytv.data.WEATHER_SRC_LATLONG
import com.conreo.couchytv.data.withWeatherLocation
import com.conreo.couchytv.data.resolveGlassStyle
import com.conreo.couchytv.data.WEATHER_SRC_ZIP
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


private enum class SettingsScreen { Main, Wallpaper, Display, StatusBar, Weather, Apps, RecommendedApps, Categories, IconPack, Parental, Launcher, AndroidShortcuts, About }

/**
 * Settings panel built entirely from focusable rows and buttons — every
 * element is reachable with the D-pad alone. Text entry only ever happens
 * inside a dedicated dialog (opened with the pencil button).
 */
@Composable
internal fun SettingsSheet(
    config: LauncherConfig,
    apps: List<AppEntry>,
    store: ConfigStore,
    onDismiss: () -> Unit,
    parental: ParentalControls,
    parentalState: ParentalState,
    onLaunchApp: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstallApp: (String) -> Unit,
    onWallpaperChanged: () -> Unit,
    onRestartAerials: () -> Unit,
    onRerunWizard: () -> Unit,
    updatePrompt: AppUpdatePrompt?,
    startAtWeather: Boolean = false,
    weatherStatus: Int? = null,
    weatherLocationName: String? = null,
    canRetryWeather: Boolean = false,
    onRetryWeather: () -> Unit = {},
    // True only when this sheet was opened by a still-held long-press (the
    // weather chip). That hold's eventual key-up must not reach — and
    // activate — whatever ends up focused first once the sheet appears.
    suppressInitialRelease: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(if (startAtWeather) SettingsScreen.Weather else SettingsScreen.Main) }
    var settingsReturnTo by remember { mutableStateOf(if (startAtWeather) SettingsScreen.Weather else SettingsScreen.Apps) }
    val mainMenuScroll = rememberScrollState()
    fun openSettings(target: SettingsScreen) {
        settingsReturnTo = target
        screen = target
    }
    var released by remember { mutableStateOf(!suppressInitialRelease) }
    val catalogUrl = config.recommendedAppsUrl.takeIf {
        BuildConfig.GITHUB_UPDATES && !BuildConfig.DEBUG && config.recommendedAppsEnabled && it.isNotBlank()
    }
    val verifiedCatalog by produceState<Pair<String, RecommendedCatalog>?>(null, catalogUrl) {
        if (catalogUrl == null) {
            value = null
            return@produceState
        }
        val repository = RecommendedApps(context, catalogUrl)
        val catalog = repository.cached() ?: try {
            repository.refresh()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        value = catalog?.let { catalogUrl to it }
    }
    val configuredCatalog = verifiedCatalog?.takeIf { it.first == catalogUrl }

    fun selectTopShelf() {
        scope.launch {
            store.update {
                it.copy(
                    useTopShelf = true,
                    useCustomWallpaper = false,
                    useVideoWallpaper = false,
                    useBuiltinAerials = false,
                )
            }
        }
    }
    val tvListingsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) selectTopShelf()
        else Actions.toast(context, context.getString(R.string.top_shelf_need_permission))
    }

    // Same document-picker flow as videos: opens the system file manager,
    // works with USB drives and network storage providers.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        File(context.filesDir, "wallpaper.jpg").outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                }
                store.update {
                    it.copy(
                        useCustomWallpaper = true,
                        useTopShelf = false,
                        useVideoWallpaper = false,
                        useBuiltinAerials = false,
                    )
                }
                onWallpaperChanged()
            }
        }
    }

    // Video wallpaper: keep a persistable read grant and stream in place —
    // aerial files are hundreds of MB, never copied.
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope.launch {
                store.update {
                    it.copy(
                        videoUri = uri.toString(),
                        useVideoWallpaper = true,
                        useTopShelf = false,
                        useCustomWallpaper = false,
                        useBuiltinAerials = false,
                    )
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (screen != SettingsScreen.Main) screen = SettingsScreen.Main else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        FillDialogWindow()
        // A Dialog spawns its own window that re-provides the platform density,
        // so the launcher's ScaledUi doesn't reach here — re-apply it. The
        // dialog's LocalConfiguration is the real (unscaled) device width.
        ScaledUi(uiScaleFactor(config.uiScale, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .width(440.dp)
                    .fillMaxHeight()
                    // Same held-button guard as MenuDialog: swallow every select
                    // event, including the first key-up, until the long-press
                    // that opened this sheet has been fully released once.
                    .onPreviewKeyEvent { e ->
                        val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            !select -> false
                            released -> false
                            e.type == KeyEventType.KeyUp -> { released = true; true }
                            else -> true
                        }
                    },
            ) {
                val towardEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
                // Enter from the sheet's outer edge and reverse when going back.
                androidx.compose.animation.AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState != SettingsScreen.Main
                        val dir = if (forward) towardEnd else -towardEnd
                        (androidx.compose.animation.slideInHorizontally(tween(260)) { w -> dir * w } +
                            androidx.compose.animation.fadeIn(tween(260))) togetherWith
                            (androidx.compose.animation.slideOutHorizontally(tween(260)) { w -> -dir * w } +
                                androidx.compose.animation.fadeOut(tween(260)))
                    },
                    label = "settingsScreen",
                ) { target ->
                when (target) {
                    SettingsScreen.Main -> MainScreen(
                        returnTo = settingsReturnTo,
                        scroll = mainMenuScroll,
                        onWallpaper = { openSettings(SettingsScreen.Wallpaper) },
                        onDisplay = { openSettings(SettingsScreen.Display) },
                        onStatusBar = { openSettings(SettingsScreen.StatusBar) },
                        onWeather = { openSettings(SettingsScreen.Weather) },
                        onApps = { openSettings(SettingsScreen.Apps) },
                        onRecommendedApps = if (configuredCatalog != null) {
                            { openSettings(SettingsScreen.RecommendedApps) }
                        } else null,
                        onCategories = { openSettings(SettingsScreen.Categories) },
                        onIconPack = { openSettings(SettingsScreen.IconPack) },
                        onParental = { openSettings(SettingsScreen.Parental) },
                        onLauncher = { openSettings(SettingsScreen.Launcher) },
                        onAndroidShortcuts = { openSettings(SettingsScreen.AndroidShortcuts) },
                        updatePrompt = updatePrompt,
                        onAbout = { openSettings(SettingsScreen.About) },
                    )
                    SettingsScreen.Wallpaper -> WallpaperScreen(
                        config = config,
                        onSelectBuiltinAerials = {
                            scope.launch {
                                store.update {
                                    it.copy(
                                        useBuiltinAerials = true,
                                        useTopShelf = false,
                                        useVideoWallpaper = false,
                                        useCustomWallpaper = false,
                                    )
                                }
                            }
                        },
                        onSelectBuiltinSource = { v ->
                            scope.launch { store.update { it.copy(builtinSource = v) } }
                        },
                        onRestartAerials = onRestartAerials,
                        onSelectAerialQuality = { quality ->
                            scope.launch { store.update { it.copy(aerialQuality = quality) } }
                        },
                        onSetScrim = { v ->
                            scope.launch { store.update { it.copy(scrimMode = v) } }
                        },
                        onSetSpeed = { v ->
                            scope.launch { store.update { it.copy(videoSpeed = v) } }
                        },
                        onPreset = { i ->
                            scope.launch {
                                store.update {
                                    it.copy(
                                        wallpaper = i,
                                        useTopShelf = false,
                                        useCustomWallpaper = false,
                                        useVideoWallpaper = false,
                                        useBuiltinAerials = false,
                                    )
                                }
                            }
                        },
                        onSelectTopShelf = {
                            if (TopShelf.hasPermission(context)) selectTopShelf()
                            else tvListingsPermission.launch(READ_TV_LISTINGS)
                        },
                        onPickPhoto = {
                            runCatching { photoPicker.launch(arrayOf("image/*")) }
                                .onFailure { Actions.toast(context, context.getString(R.string.toast_no_picker)) }
                        },
                        onPickVideo = {
                            runCatching { videoPicker.launch(arrayOf("video/*")) }
                                .onFailure { Actions.toast(context, context.getString(R.string.toast_no_picker)) }
                        },
                    )
                    SettingsScreen.Display -> DisplayScreen(
                        config = config,
                        store = store,
                        onBack = { screen = SettingsScreen.Main },
                    )
                    SettingsScreen.StatusBar -> StatusBarScreen(
                        config = config,
                        apps = apps,
                        store = store,
                        onBack = { screen = SettingsScreen.Main },
                    )
                    SettingsScreen.Weather -> WeatherScreen(
                        apps = apps,
                        config = config,
                        store = store,
                        weatherStatus = weatherStatus,
                        weatherLocationName = weatherLocationName,
                        canRetry = canRetryWeather,
                        onRetry = onRetryWeather,
                        onBack = { screen = SettingsScreen.Main },
                    )
                    SettingsScreen.IconPack -> IconPackScreen(
                        config = config,
                        apps = apps,
                        store = store,
                        onBack = { screen = SettingsScreen.Main },
                    )
                    SettingsScreen.Parental -> ParentalSettings(parentalState, parental)
                    SettingsScreen.RecommendedApps -> configuredCatalog?.let { (url, catalog) ->
                        androidx.compose.runtime.key(url) { RecommendedAppsScreen(url, catalog) }
                    }
                    SettingsScreen.Apps -> AppsScreen(
                        apps = apps,
                        config = config,
                        onBack = { screen = SettingsScreen.Main },
                        onLaunch = onLaunchApp,
                        onAppInfo = onAppInfo,
                        onUninstall = onUninstallApp,
                        onToggleHide = { pkg ->
                            scope.launch {
                                store.update { c ->
                                    c.copy(hidden = if (pkg in c.hidden) c.hidden - pkg else c.hidden + pkg)
                                }
                            }
                        },
                    )
                    SettingsScreen.Categories -> CategoriesScreen(config = config, store = store)
                    SettingsScreen.AndroidShortcuts -> AndroidShortcutsScreen()
                    SettingsScreen.About -> AboutScreen(
                        onBack = { screen = SettingsScreen.Main },
                    )
                    SettingsScreen.Launcher -> LauncherSettingsSubscreen(
                        config = config,
                        store = store,
                        onBack = { screen = SettingsScreen.Main },
                        onRerunWizard = onRerunWizard,
                    )
                }
                }
            }
        }
        }
    }
}

/* ------------------------------ main menu ------------------------------ */

@Composable
private fun MainScreen(
    returnTo: SettingsScreen,
    scroll: androidx.compose.foundation.ScrollState,
    onWallpaper: () -> Unit,
    onDisplay: () -> Unit,
    onStatusBar: () -> Unit,
    onWeather: () -> Unit,
    onApps: () -> Unit,
    onRecommendedApps: (() -> Unit)?,
    onCategories: () -> Unit,
    onIconPack: () -> Unit,
    onParental: () -> Unit,
    onLauncher: () -> Unit,
    onAndroidShortcuts: () -> Unit,
    updatePrompt: AppUpdatePrompt?,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val focus = initialFocus()
    val target = if (returnTo == SettingsScreen.RecommendedApps && onRecommendedApps == null) {
        SettingsScreen.Apps
    } else returnTo
    fun focusFor(row: SettingsScreen) = if (row == target) Modifier.focusRequester(focus) else Modifier
    SettingsPage(scroll) {
        SettingsTitle(stringResource(R.string.settings_title))

        // ---- Content: what shows on the home screen ----
        SettingsSectionLabel(stringResource(R.string.group_content).uppercase())
        SettingsItem(
            onClick = onApps,
            modifier = focusFor(SettingsScreen.Apps),
            headlineContent = { Text(stringResource(R.string.item_app)) },
            supportingContent = { Text(stringResource(R.string.item_app_sub)) },
            leadingContent = { Icon(AppIcons.Apps, contentDescription = null) },
        )
        if (onRecommendedApps != null) {
            SettingsItem(
                onClick = onRecommendedApps,
                modifier = focusFor(SettingsScreen.RecommendedApps),
                headlineContent = { Text(stringResource(R.string.recommended_title)) },
                leadingContent = { Icon(AppIcons.Download, contentDescription = null) },
            )
        }
        SettingsItem(
            onClick = onCategories,
            modifier = focusFor(SettingsScreen.Categories),
            headlineContent = { Text(stringResource(R.string.item_sections)) },
            leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
        )
        SettingsItem(
            onClick = onIconPack,
            modifier = focusFor(SettingsScreen.IconPack),
            headlineContent = { Text(stringResource(R.string.item_icon_pack)) },
            supportingContent = { Text(stringResource(R.string.item_icon_pack_sub)) },
            leadingContent = { Icon(AppIcons.Palette, contentDescription = null) },
        )

        SettingsItem(
            onClick = onParental,
            modifier = focusFor(SettingsScreen.Parental),
            headlineContent = { Text(stringResource(R.string.parental_controls)) },
            leadingContent = { Icon(AppIcons.Lock, contentDescription = null) },
        )

        // ---- Appearance: how it looks ----
        SettingsSectionLabel(stringResource(R.string.group_appearance).uppercase())
        SettingsItem(
            onClick = onDisplay,
            modifier = focusFor(SettingsScreen.Display),
            headlineContent = { Text(stringResource(R.string.item_display)) },
            supportingContent = { Text(stringResource(R.string.item_display_sub)) },
            leadingContent = { Icon(AppIcons.Display, contentDescription = null) },
        )
        SettingsItem(
            onClick = onWallpaper,
            modifier = focusFor(SettingsScreen.Wallpaper),
            headlineContent = { Text(stringResource(R.string.item_wallpaper)) },
            supportingContent = { Text(stringResource(R.string.item_wallpaper_sub)) },
            leadingContent = { Icon(AppIcons.Image, contentDescription = null) },
        )
        SettingsItem(
            onClick = onStatusBar,
            modifier = focusFor(SettingsScreen.StatusBar),
            headlineContent = { Text(stringResource(R.string.item_statusbar)) },
            supportingContent = { Text(stringResource(R.string.item_statusbar_sub)) },
            leadingContent = { Icon(AppIcons.Wifi, contentDescription = null) },
        )
        SettingsItem(
            onClick = onWeather,
            modifier = focusFor(SettingsScreen.Weather),
            headlineContent = { Text(stringResource(R.string.item_weather)) },
            supportingContent = { Text(stringResource(R.string.item_weather_sub)) },
            leadingContent = { Icon(AppIcons.WeatherPartlyCloudy, contentDescription = null) },
        )

        // ---- System ----
        SettingsSectionLabel(stringResource(R.string.group_system).uppercase())
        SettingsItem(
            onClick = onLauncher,
            modifier = focusFor(SettingsScreen.Launcher),
            headlineContent = { Text(stringResource(R.string.item_launcher_settings)) },
            supportingContent = { Text(stringResource(R.string.item_launcher_settings_sub)) },
            leadingContent = {
                Icon(painterResource(R.drawable.brand_silhouette), contentDescription = null, modifier = Modifier.size(24.dp))
            },
        )
        SettingsItem(
            onClick = onAndroidShortcuts,
            modifier = focusFor(SettingsScreen.AndroidShortcuts),
            headlineContent = { Text(stringResource(R.string.android_shortcuts)) },
            leadingContent = { Icon(AppIcons.Shortcut, contentDescription = null) },
        )
        SettingsItem(
            onClick = { Actions.openSystemSettings(context) },
            headlineContent = { Text(stringResource(R.string.item_android_settings)) },
            leadingContent = { Icon(AppIcons.Gear, contentDescription = null) },
        )
        if (updatePrompt != null) {
            val status = updatePrompt.checkStatus
            SettingsItem(
                onClick = updatePrompt::checkNow,
                headlineContent = { Text(stringResource(R.string.update_check_now)) },
                supportingContent = status?.let { state ->
                    { Text(stringResource(state.message)) }
                },
                leadingContent = { Icon(AppIcons.Bell, contentDescription = null) },
            )
        }
        SettingsItem(
            onClick = onAbout,
            modifier = focusFor(SettingsScreen.About),
            headlineContent = { Text(stringResource(R.string.item_about)) },
            leadingContent = { Icon(AppIcons.Info, contentDescription = null) },
        )
    }
}

/* ------------------------------ about ------------------------------ */

@Composable
private fun AboutScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    SettingsPage {
        SettingsTitle(stringResource(R.string.item_about))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        ) {
            BrandLogo(Modifier.size(44.dp))
            Column {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "v" + (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrDefault("?")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.about_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp),
        )

        SettingsSectionLabel(stringResource(R.string.about_foss).uppercase())
        Text(
            stringResource(R.string.about_license),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        // Source code
        Text(
            stringResource(R.string.about_source) + "  ·  " + stringResource(R.string.github_url),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp),
        )

        Text(
            stringResource(R.string.about_thirdparty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 8.dp),
        )
    }
}

/* ------------------------------ display ------------------------------ */

@Composable
private fun DisplayScreen(
    config: LauncherConfig,
    store: ConfigStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    fun update(transform: (LauncherConfig) -> LauncherConfig) {
        scope.launch { store.update(transform) }
    }
    SettingsPage {
        SettingsTitle(stringResource(R.string.item_display))
        SettingsSectionLabel(stringResource(R.string.section_layout).uppercase())
        val f = initialFocus()
        SelectorRow(
            modifier = Modifier.focusRequester(f),
            label = stringResource(R.string.view_mode),
            value = listOf(stringResource(R.string.mode_carousel), stringResource(R.string.mode_grid), stringResource(R.string.mode_dock))[config.layout.coerceIn(0, 2)],
            description = when (config.layout) {
                LAYOUT_GRID -> stringResource(R.string.mode_grid_desc)
                LAYOUT_DOCK -> stringResource(R.string.mode_dock_desc)
                else -> stringResource(R.string.mode_carousel_desc)
            },
            steps = 3,
            current = config.layout.coerceIn(0, 2),
            onSelect = { v -> update { it.copy(layout = v) } },
        )
        val glassStyle = resolveGlassStyle(config.glassStyle)
        SelectorRow(
            label = stringResource(R.string.glass_style),
            value = stringResource(
                if (glassStyle == GLASS_STYLE_LIQUID) R.string.glass_liquid else R.string.glass_transparent,
            ),
            description = stringResource(R.string.glass_liquid_hint),
            steps = 2,
            current = glassStyle,
            onSelect = { style -> update { it.copy(glassStyle = style) } },
        )
        // Section names are only drawn in carousel/grid — hide the toggle in dock.
        if (config.layout != LAYOUT_DOCK) {
            SettingsSectionLabel(stringResource(R.string.section_elements).uppercase())
            SettingsItem(
                onClick = { update { it.copy(showCategoryNames = !it.showCategoryNames) } },
                headlineContent = { Text(stringResource(R.string.section_names_label)) },
                trailingContent = { CheckMark(checked = config.showCategoryNames) },
            )
        }
        SettingsSectionLabel(stringResource(R.string.section_size).uppercase())
        run {
            val labels = listOf(stringResource(R.string.scale_auto), "75%", "90%", "100%", "115%", "130%")
            val idx = config.uiScale.coerceIn(0, labels.size - 1)
            SelectorRow(
                label = stringResource(R.string.ui_scale),
                value = labels[idx],
                description = if (idx == 0) stringResource(R.string.ui_scale_auto_sub) else null,
                steps = labels.size,
                current = idx,
                onSelect = { v -> update { it.copy(uiScale = v) } },
            )
        }
        SelectorRow(
            label = stringResource(R.string.roundness),
            value = listOf(stringResource(R.string.size_xs), stringResource(R.string.size_s), stringResource(R.string.size_n), stringResource(R.string.size_l), stringResource(R.string.size_xl))[config.cornerRadius.coerceIn(0, 4)],
            steps = 5,
            current = config.cornerRadius.coerceIn(0, 4),
            onSelect = { v -> update { it.copy(cornerRadius = v) } },
        )
        val iconSizes = listOf(
            ICON_SCALE_EXTRA_SMALL to R.string.icon_size_extra_small,
            0 to R.string.icon_size_compact,
            1 to R.string.size_s,
            2 to R.string.size_n,
            3 to R.string.size_l,
            4 to R.string.size_xl,
        )
        val iconSizeIndex = iconSizes.indexOfFirst { it.first == config.iconScale }
            .takeIf { it >= 0 } ?: (config.iconScale.coerceIn(0, 4) + 1)
        SelectorRow(
            label = stringResource(R.string.icon_size),
            value = stringResource(iconSizes[iconSizeIndex].second),
            steps = iconSizes.size,
            current = iconSizeIndex,
            onSelect = { index -> update { it.copy(iconScale = iconSizes[index].first) } },
        )
        SelectorRow(
            label = stringResource(R.string.spacing),
            value = listOf(stringResource(R.string.size_xs), stringResource(R.string.size_s), stringResource(R.string.size_n), stringResource(R.string.size_l), stringResource(R.string.size_xl))[config.spacing.coerceIn(0, 4)],
            steps = 5,
            current = config.spacing.coerceIn(0, 4),
            onSelect = { v -> update { it.copy(spacing = v) } },
        )
    }
}

/**
 * D-pad selector row: Left/Right changes the value directly, OK cycles.
 * The dots show the position within the available steps.
 */
@Composable
private fun SelectorRow(
    label: String,
    value: String,
    steps: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val towardEnd = if (rtl) -1 else 1
    SettingsItem(
        onClick = { onSelect((current + 1) % steps) },
        headlineContent = { Text(label) },
        supportingContent = { if (description != null) Text(description) },
        trailingContent = {
            Row(
                Modifier.widthIn(max = 156.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SettingsRowGap),
            ) {
                Text(if (rtl) "►" else "◄")
                Text(value, Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
                Text(if (rtl) "◄" else "►")
            }
        },
        modifier = modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { onSelect((current - towardEnd + steps) % steps); true }
                Key.DirectionRight -> { onSelect((current + towardEnd + steps) % steps); true }
                else -> false
            }
        },
    )
}

/* --------------- app list: icon + name launches, ⓘ = app info --------------- */

@Composable
private fun AppsScreen(
    apps: List<AppEntry>,
    config: LauncherConfig,
    onBack: () -> Unit,
    onLaunch: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onToggleHide: (String) -> Unit,
) {
    val catNames = remember(config.categories) { config.categories.associate { it.id to it.name } }

    Column(Modifier.fillMaxSize().padding(SettingsPageInset)) {
        SettingsTitle(stringResource(R.string.item_app))
        Text(
            stringResource(R.string.app_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp),
        )
        val f = initialFocus()
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
        ) {
            items(apps.size, key = { apps[it].pkg }) { i ->
                val app = apps[i]
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SettingsControlGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        SettingsItem(
                            onClick = { onLaunch(app.pkg) },
                            modifier = if (i == 0) Modifier.focusRequester(f) else Modifier,
                            headlineContent = { Text(app.label) },
                            supportingContent = {
                                val names = AppRepository.sectionsOf(app, config)
                                    .mapNotNull { catNames[it] }
                                val suffix = if (app.pkg in config.hidden) " · " + stringResource(R.string.hidden_tag) else ""
                                Text(names.joinToString(" · ").ifEmpty { stringResource(R.string.no_section) } + suffix)
                            },
                            leadingContent = { AppThumb(app) },
                        )
                    }
                    val isHidden = app.pkg in config.hidden
                    SettingsIconButton(
                        if (isHidden) AppIcons.Hide else AppIcons.Show,
                        stringResource(if (isHidden) R.string.menu_unhide else R.string.menu_hide),
                    ) { onToggleHide(app.pkg) }
                    SettingsIconButton(AppIcons.Info, stringResource(R.string.menu_app_info)) { onAppInfo(app.pkg) }
                    SettingsIconButton(AppIcons.Delete, stringResource(R.string.menu_uninstall)) { onUninstall(app.pkg) }
                }
            }
        }
    }
}

/** Small leading artwork for list rows: banner if the app ships one, else icon. */
@Composable
private fun AppThumb(app: AppEntry) {
    when {
        app.banner != null -> Image(
            bitmap = app.banner,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        app.icon != null -> Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        else -> Box(
            Modifier
                .size(width = 52.dp, height = 30.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(5.dp))
        )
    }
}

/* ------------------------------ launcher settings ------------------------------ */

private enum class LauncherPane { Menu, Home, Language, RecommendedApps, Save, Load }

@Composable
private fun LauncherSettingsSubscreen(
    config: LauncherConfig,
    store: ConfigStore,
    onBack: () -> Unit,
    onRerunWizard: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud = remember { TelegramCloudBackup.get(context.applicationContext) }
    val session by cloud.session.collectAsStateWithLifecycle(CloudSession.Empty)
    var pane by remember { mutableStateOf(LauncherPane.Menu) }
    var launcherReturnTo by remember { mutableStateOf(LauncherPane.Home) }
    val launcherMenuScroll = rememberScrollState()
    fun openPane(target: LauncherPane) {
        launcherReturnTo = target
        pane = target
    }
    var pendingCloud by remember { mutableStateOf<LauncherPane?>(null) }
    var confirmWizardRerun by remember { mutableStateOf(false) }
    var showAuth by remember { mutableStateOf(false) }
    LaunchedEffect(cloud) { cloud.warmup() }

    if (pane != LauncherPane.Menu) {
        BackHandler { pane = LauncherPane.Menu }
    }

    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ConfigBackup.encode(store.snapshot())
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
                    output.bufferedWriter().use { it.write(text) }
                }.isSuccess
            }
            Actions.toast(context, context.getString(if (saved) R.string.toast_config_saved else R.string.toast_config_save_failed))
        }
    }
    fun saveLocal() {
        scope.launch {
            val ok = runCatching { store.exportTo(ConfigStore.defaultLocalFile()) }.isSuccess
            if (ok) Actions.toast(context, context.getString(R.string.toast_config_saved_local))
            else runCatching { savePicker.launch(ConfigStore.LOCAL_FILE) }
                .onFailure { Actions.toast(context, context.getString(R.string.toast_config_save_failed)) }
        }
    }

    fun applyLoaded(loaded: LauncherConfig?) {
        scope.launch {
            if (loaded != null) {
                val saved = runCatching { store.restore(loaded) }.isSuccess
                Actions.toast(context, context.getString(
                    if (saved) R.string.toast_config_loaded else R.string.toast_config_bad,
                ))
            } else {
                Actions.toast(context, context.getString(R.string.toast_config_bad))
            }
        }
    }

    fun saveCloud() {
        scope.launch {
            val result = runCatching { cloud.saveConfig(store.snapshot()) }
            when (val error = result.exceptionOrNull()) {
                is NeedTelegramLogin, is CloudNotConfigured -> {
                    if (!cloud.isConfigured()) {
                        Actions.toast(context, cloud.unavailableMessage())
                    } else {
                        pendingCloud = LauncherPane.Save
                        showAuth = true
                    }
                }
                null -> {
                    HomeDiagnostics.record(context, "Telegram Cloud Save confirmed")
                    android.util.Log.i("CouchyTelegram", "Cloud Save confirmed")
                    Actions.toast(context, context.getString(R.string.toast_config_saved_cloud))
                }
                else -> {
                    if (error is kotlinx.coroutines.CancellationException &&
                        error !is kotlinx.coroutines.TimeoutCancellationException
                    ) throw error
                    val requestFailure = error as? TelegramRequestFailure
                    val detail = requestFailure?.diagnostic ?: error.javaClass.simpleName
                    HomeDiagnostics.record(context, "Telegram Cloud Save failed: $detail")
                    android.util.Log.w("CouchyTelegram", detail)
                    val message = context.getString(R.string.toast_cloud_save_fail)
                    val code = requestFailure?.code?.takeIf { it != 0 }
                    val request = if (code != null) "${requestFailure.method} $code" else null
                    val explanation = listOfNotNull(request, requestFailure?.reason).joinToString(" · ")
                    Actions.toast(context, if (explanation.isBlank()) message else "$message\n$explanation")
                }
            }
        }
    }

    fun loadCloud() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { cloud.loadConfig() } }
            when (val e = result.exceptionOrNull()) {
                is NeedTelegramLogin, is CloudNotConfigured -> {
                    if (!cloud.isConfigured()) {
                        Actions.toast(context, cloud.unavailableMessage())
                    } else {
                        pendingCloud = LauncherPane.Load
                        showAuth = true
                    }
                }
                null -> {
                    val loaded = result.getOrNull()
                    if (loaded == null) {
                        Actions.toast(context, context.getString(R.string.toast_cloud_load_missing))
                    } else {
                        applyLoaded(loaded)
                    }
                }
                else -> {
                    Actions.toast(context, context.getString(R.string.toast_cloud_load_fail))
                    android.util.Log.w("Couchy", "cloud load failed", e)
                }
            }
        }
    }

    fun onCloudChosen(which: LauncherPane) {
        if (!cloud.isConfigured()) {
            Actions.toast(context, cloud.unavailableMessage())
            return
        }
        if (session.signedIn) {
            if (which == LauncherPane.Save) saveCloud() else loadCloud()
        } else {
            pendingCloud = which
            showAuth = true
        }
    }

    val loadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            ConfigBackup.read(input)
                        }
                    }.getOrNull()
                }
                applyLoaded(loaded)
            }
        }
    }

    when (pane) {
        LauncherPane.Home -> HomeLauncherScreen()
        LauncherPane.RecommendedApps -> RecommendedAppsSettings(config, store)
        LauncherPane.Language -> LanguageScreen(
            config = config, store = store, onBack = { pane = LauncherPane.Menu },
        )
        LauncherPane.Save ->         BackupTargetScreen(
            title = stringResource(R.string.item_save_config),
            localLabel = stringResource(R.string.item_save_local),
            localSub = stringResource(R.string.item_save_local_sub),
            localIcon = AppIcons.Save,
            cloudLabel = stringResource(R.string.item_save_cloud),
            cloudSub = stringResource(R.string.item_save_cloud_sub),
            session = session,
            onLocal = { saveLocal() },
            onCloud = { onCloudChosen(LauncherPane.Save) },
            onDisconnect = { scope.launch { cloud.signOut() } },
        )
        LauncherPane.Load ->         BackupTargetScreen(
            title = stringResource(R.string.item_load_config),
            localLabel = stringResource(R.string.item_load_local),
            localSub = stringResource(R.string.item_load_local_sub),
            localIcon = AppIcons.Folder,
            cloudLabel = stringResource(R.string.item_load_cloud),
            cloudSub = stringResource(R.string.item_load_cloud_sub),
            session = session,
            onLocal = {
                runCatching { loadPicker.launch(arrayOf("application/json", "*/*")) }
                    .onFailure { Actions.toast(context, context.getString(R.string.toast_no_picker)) }
            },
            onCloud = { onCloudChosen(LauncherPane.Load) },
            onDisconnect = { scope.launch { cloud.signOut() } },
        )
        LauncherPane.Menu -> SettingsPage(launcherMenuScroll) {
            SettingsTitle(stringResource(R.string.item_launcher_settings))
            val focus = initialFocus()
            fun focusFor(row: LauncherPane) = if (row == launcherReturnTo) Modifier.focusRequester(focus) else Modifier
            SettingsItem(
                onClick = { openPane(LauncherPane.Home) },
                modifier = focusFor(LauncherPane.Home),
                headlineContent = { Text(stringResource(R.string.home_launcher_title)) },
                supportingContent = { Text(stringResource(R.string.home_launcher_settings_sub)) },
                leadingContent = { Icon(AppIcons.Apps, contentDescription = null) },
            )
            SettingsItem(
                onClick = { openPane(LauncherPane.Language) },
                modifier = focusFor(LauncherPane.Language),
                headlineContent = { Text(stringResource(R.string.item_language)) },
                supportingContent = { Text(stringResource(R.string.item_language_sub)) },
                leadingContent = { Icon(AppIcons.Language, contentDescription = null) },
            )
            if (BuildConfig.GITHUB_UPDATES && !BuildConfig.DEBUG) {
                SettingsItem(
                    onClick = { openPane(LauncherPane.RecommendedApps) },
                    modifier = focusFor(LauncherPane.RecommendedApps),
                    headlineContent = { Text(stringResource(R.string.recommended_settings)) },
                    supportingContent = { Text(stringResource(R.string.recommended_settings_sub)) },
                    leadingContent = { Icon(AppIcons.Download, contentDescription = null) },
                )
            }
            SettingsItem(
                onClick = { openPane(LauncherPane.Save) },
                modifier = focusFor(LauncherPane.Save),
                headlineContent = { Text(stringResource(R.string.item_save_config)) },
                supportingContent = { Text(stringResource(R.string.item_save_config_sub)) },
                leadingContent = { Icon(AppIcons.Save, contentDescription = null) },
            )
            SettingsItem(
                onClick = { openPane(LauncherPane.Load) },
                modifier = focusFor(LauncherPane.Load),
                headlineContent = { Text(stringResource(R.string.item_load_config)) },
                supportingContent = { Text(stringResource(R.string.item_load_config_sub)) },
                leadingContent = { Icon(AppIcons.Folder, contentDescription = null) },
            )
            SettingsItem(
                onClick = { confirmWizardRerun = true },
                headlineContent = { Text(stringResource(R.string.rerun_wizard)) },
                supportingContent = { Text(stringResource(R.string.rerun_wizard_sub)) },
                leadingContent = { Icon(AppIcons.Play, contentDescription = null) },
            )
        }
    }

    if (confirmWizardRerun) {
        val dismiss = { confirmWizardRerun = false }
        Dialog(onDismissRequest = dismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.width(400.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.rerun_wizard_confirm),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val cancelFocus = initialFocus()
                    Column(verticalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
                        SettingsButton(
                            onClick = dismiss,
                            modifier = Modifier.fillMaxWidth().focusRequester(cancelFocus),
                        ) { Text(stringResource(R.string.cancel)) }
                        SettingsButton(
                            onClick = {
                                dismiss()
                                onRerunWizard()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.rerun_wizard)) }
                    }
                }
            }
        }
    }

    if (showAuth) {
        TelegramAuthDialog(
            backup = cloud,
            onSuccess = {
                showAuth = false
                val next = pendingCloud
                pendingCloud = null
                when (next) {
                    LauncherPane.Save -> saveCloud()
                    LauncherPane.Load -> loadCloud()
                    else -> {}
                }
            },
            onDismiss = {
                showAuth = false
                pendingCloud = null
            },
        )
    }
}

@Composable
private fun BackupTargetScreen(
    title: String,
    localLabel: String,
    localSub: String,
    localIcon: androidx.compose.ui.graphics.vector.ImageVector,
    cloudLabel: String,
    cloudSub: String,
    session: CloudSession,
    onLocal: () -> Unit,
    onCloud: () -> Unit,
    onDisconnect: () -> Unit,
) {
    SettingsPage {
        SettingsTitle(title)
        val f = initialFocus()
        SettingsItem(
            onClick = onLocal,
            modifier = Modifier.focusRequester(f),
            headlineContent = { Text(localLabel) },
            supportingContent = { Text(localSub) },
            leadingContent = { Icon(localIcon, contentDescription = null) },
        )
        val cloudHint = if (session.signedIn && session.accountLabel.isNotBlank()) {
            stringResource(R.string.cloud_signed_in, session.accountLabel)
        } else {
            stringResource(R.string.cloud_sign_in_hint)
        }
        SettingsItem(
            onClick = onCloud,
            headlineContent = { Text(cloudLabel) },
            supportingContent = { Text("$cloudSub\n$cloudHint") },
            leadingContent = { Icon(AppIcons.Cloud, contentDescription = null) },
        )
        if (session.signedIn) {
            SettingsItem(
                onClick = onDisconnect,
                headlineContent = { Text(stringResource(R.string.cloud_disconnect)) },
                supportingContent = { Text(stringResource(R.string.cloud_disconnect_sub)) },
                leadingContent = { Icon(AppIcons.Logout, contentDescription = null) },
            )
        }
    }
}

/* ------------------------------ language ------------------------------ */

@Composable
private fun LanguageScreen(
    config: LauncherConfig,
    store: ConfigStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    SettingsPage {
        SettingsTitle(stringResource(R.string.item_language))
        val f = initialFocus()
        com.conreo.couchytv.data.LANGUAGES.forEachIndexed { i, code ->
            val label = languageName(code)
            SettingsItem(
                onClick = {
                    // Just persist it; LauncherApp mirrors it and recreates.
                    if (code != config.language) scope.launch {
                        store.update { it.copy(language = code) }
                    }
                },
                headlineContent = { Text(label) },
                trailingContent = { CheckMark(checked = config.language == code) },
                modifier = if (i == 0) Modifier.focusRequester(f) else Modifier,
            )
        }
    }
}

/* ------------------------------ status bar ------------------------------ */

@Composable
private fun StatusBarScreen(
    config: LauncherConfig,
    apps: List<AppEntry>,
    store: ConfigStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pickVpn by remember { mutableStateOf(false) }
    val vpnLabel = apps.firstOrNull { it.pkg == config.vpnApp }?.label ?: stringResource(R.string.vpn_system)

    SettingsPage {
        SettingsTitle(stringResource(R.string.item_statusbar))
        val f = initialFocus()
        SettingsItem(
            onClick = { scope.launch { store.update { it.copy(showStatusBar = !it.showStatusBar) } } },
            modifier = Modifier.focusRequester(f),
            headlineContent = { Text(stringResource(R.string.show_statusbar)) },
            supportingContent = { Text(stringResource(R.string.show_statusbar_sub)) },
            trailingContent = { CheckMark(checked = config.showStatusBar) },
        )
        SettingsItem(
            onClick = { scope.launch { store.update { it.copy(statusBarGlass = !it.statusBarGlass) } } },
            headlineContent = { Text(stringResource(R.string.statusbar_glass)) },
            supportingContent = { Text(stringResource(R.string.statusbar_glass_sub)) },
            trailingContent = { CheckMark(checked = config.statusBarGlass) },
        )
        SettingsItem(
            onClick = { scope.launch { store.update { it.copy(h24 = !it.h24) } } },
            headlineContent = { Text(stringResource(R.string.clock_24h)) },
            trailingContent = { CheckMark(checked = config.h24) },
        )
        run {
            val idx = config.dateFormat.coerceIn(0, DATE_FORMATS.size - 1)
            SelectorRow(
                label = stringResource(R.string.date_label),
                // Live preview of today's date in the selected format
                value = if (idx == 0) stringResource(R.string.off)
                else java.text.SimpleDateFormat(DATE_FORMATS[idx], java.util.Locale.getDefault())
                    .format(java.util.Date()),
                steps = DATE_FORMATS.size,
                current = idx,
                onSelect = { v -> scope.launch { store.update { it.copy(dateFormat = v) } } },
            )
        }
        SettingsItem(
            onClick = { scope.launch { store.update { it.copy(showVpnButton = !it.showVpnButton) } } },
            headlineContent = { Text(stringResource(R.string.show_vpn_button)) },
            leadingContent = { Icon(AppIcons.Vpn, contentDescription = null) },
            trailingContent = { CheckMark(checked = config.showVpnButton) },
        )
        // Grayed out (and not focusable) while the VPN button is hidden
        SettingsItem(
            enabled = config.showVpnButton,
            onClick = { pickVpn = true },
            headlineContent = { Text(stringResource(R.string.vpn_opens)) },
            supportingContent = { Text(vpnLabel) },
            leadingContent = { Icon(AppIcons.Vpn, contentDescription = null) },
        )
    }

    if (pickVpn) {
        Dialog(onDismissRequest = { pickVpn = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.width(380.dp).height(480.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        stringResource(R.string.vpn_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                    )
                    val fv = initialFocus()
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
                        item {
                            SettingsItem(
                                onClick = {
                                    scope.launch { store.update { it.copy(vpnApp = "") } }
                                    pickVpn = false
                                },
                                headlineContent = { Text(stringResource(R.string.vpn_system)) },
                                trailingContent = { CheckMark(checked = config.vpnApp.isEmpty()) },
                                modifier = Modifier.focusRequester(fv),
                            )
                        }
                        items(apps.size, key = { apps[it].pkg }) { i ->
                            val app = apps[i]
                            SettingsItem(
                                onClick = {
                                    scope.launch { store.update { it.copy(vpnApp = app.pkg) } }
                                    pickVpn = false
                                },
                                headlineContent = { Text(app.label) },
                                leadingContent = { AppThumb(app) },
                                trailingContent = { CheckMark(checked = config.vpnApp == app.pkg) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------ weather ------------------------------ */

@Composable
private fun WeatherScreen(
    config: LauncherConfig,
    apps: List<AppEntry>,
    store: ConfigStore,
    weatherStatus: Int?,
    weatherLocationName: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editField by remember { mutableStateOf<String?>(null) } // "lat" | "lon" | "zip" | "country"
    var pickWeatherApp by remember { mutableStateOf(false) }
    var findCity by remember { mutableStateOf(false) }
    var savingCity by remember { mutableStateOf(false) }

    SettingsPage {
        SettingsTitle(stringResource(R.string.item_weather))
        Text(
            stringResource(R.string.weather_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp),
        )
        val f = initialFocus()
        SettingsItem(
            onClick = { scope.launch { store.update { it.copy(weatherEnabled = !it.weatherEnabled) } } },
            modifier = Modifier.focusRequester(f),
            headlineContent = { Text(stringResource(R.string.weather_enable)) },
            trailingContent = { CheckMark(checked = config.weatherEnabled) },
        )
        if (!config.weatherEnabled) return@SettingsPage
        if (weatherStatus != null) {
            Text(
                stringResource(weatherStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        if (canRetry && weatherStatus != null) {
            SettingsItem(
                onClick = onRetry,
                headlineContent = { Text(stringResource(R.string.weather_retry)) },
            )
        }

        SettingsItem(
            onClick = { findCity = true },
            headlineContent = { Text(stringResource(R.string.weather_find_city)) },
            supportingContent = {
                val detail = if (config.weatherLocationSource == WEATHER_SRC_LATLONG && config.weatherCityLabel.isNotBlank())
                    config.weatherCityLabel
                else stringResource(R.string.weather_find_city_sub)
                Text(detail)
            },
        )

        val sourceNames = listOf(
            stringResource(R.string.weather_src_geoip),
            stringResource(R.string.weather_src_latlong),
            stringResource(R.string.weather_src_zip),
        )
        val srcIdx = config.weatherLocationSource.coerceIn(0, sourceNames.size - 1)
        SelectorRow(
            label = stringResource(R.string.weather_source),
            value = sourceNames[srcIdx],
            steps = sourceNames.size,
            current = srcIdx,
            onSelect = { v -> scope.launch { store.update { it.copy(weatherLocationSource = v) } } },
        )
        if (srcIdx == WEATHER_SRC_GEOIP) {
            if (weatherLocationName != null) {
                Text(
                    stringResource(R.string.weather_ip_city, weatherLocationName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            // Full-width note, not squeezed into the row's supporting-text column
            // next to the ◄ ► stepper — that wrapped this sentence into confetti.
            Text(
                stringResource(R.string.weather_geoip_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            if (!config.weatherGeoJsEnabled) {
                SettingsItem(
                    onClick = { scope.launch { store.update { it.copy(weatherGeoJsEnabled = true) } } },
                    headlineContent = { Text(stringResource(R.string.weather_ip_enable)) },
                )
            }
        }

        when (srcIdx) {
            WEATHER_SRC_LATLONG -> {
                SettingsItem(
                    onClick = { editField = "lat" },
                    headlineContent = { Text(stringResource(R.string.weather_lat)) },
                    supportingContent = { Text(config.weatherLat.toString()) },
                )
                SettingsItem(
                    onClick = { editField = "lon" },
                    headlineContent = { Text(stringResource(R.string.weather_lon)) },
                    supportingContent = { Text(config.weatherLon.toString()) },
                )
            }
            WEATHER_SRC_ZIP -> {
                SettingsItem(
                    onClick = { editField = "zip" },
                    headlineContent = { Text(stringResource(R.string.weather_zip)) },
                    supportingContent = { Text(config.weatherZip.ifEmpty { stringResource(R.string.weather_not_set) }) },
                )
                SettingsItem(
                    onClick = { editField = "country" },
                    headlineContent = { Text(stringResource(R.string.weather_country)) },
                    supportingContent = {
                        Text(config.weatherCountry.ifEmpty { "US" })
                    },
                )
            }
        }

        val unitNames = listOf(stringResource(R.string.weather_celsius), stringResource(R.string.weather_fahrenheit))
        val unitIdx = config.weatherUnit.coerceIn(0, 1)
        SelectorRow(
            label = stringResource(R.string.weather_unit),
            value = unitNames[unitIdx],
            steps = 2,
            current = unitIdx,
            onSelect = { v -> scope.launch { store.update { it.copy(weatherUnit = v) } } },
        )

        val displayNames = listOf(
            stringResource(R.string.weather_display_icon),
            stringResource(R.string.weather_display_temp),
            stringResource(R.string.weather_display_lowhigh),
        )
        val displayIdx = config.weatherDisplay.coerceIn(0, displayNames.size - 1)
        SelectorRow(
            label = stringResource(R.string.weather_display),
            value = displayNames[displayIdx],
            steps = displayNames.size,
            current = displayIdx,
            onSelect = { v -> scope.launch { store.update { it.copy(weatherDisplay = v) } } },
        )

        val forecastViews = listOf(
            stringResource(R.string.weather_forecast_both),
            stringResource(R.string.weather_forecast_hours_only),
            stringResource(R.string.weather_forecast_days_only),
        )
        val view = config.weatherForecastView.coerceIn(0, forecastViews.lastIndex)
        SelectorRow(
            label = stringResource(R.string.weather_forecast_view),
            value = forecastViews[view],
            steps = forecastViews.size,
            current = view,
            onSelect = { v -> scope.launch { store.update { it.copy(weatherForecastView = v) } } },
        )

        val iconStyles = listOf(
            stringResource(R.string.weather_icons_mono),
            stringResource(R.string.weather_icons_color),
            stringResource(R.string.weather_icons_animated),
        )
        val iconStyle = config.weatherForecastIconStyle.coerceIn(0, iconStyles.lastIndex)
        SelectorRow(
            label = stringResource(R.string.weather_forecast_icons),
            value = iconStyles[iconStyle],
            steps = iconStyles.size,
            current = iconStyle,
            onSelect = { v -> scope.launch { store.update { it.copy(weatherForecastIconStyle = v) } } },
        )

        val clickNames = listOf(
            stringResource(R.string.weather_click_settings),
            stringResource(R.string.weather_click_forecast),
            stringResource(R.string.weather_click_app),
        )
        val clickIdx = config.weatherClickAction.coerceIn(0, clickNames.size - 1)
        SelectorRow(
            label = stringResource(R.string.weather_click_action),
            value = clickNames[clickIdx],
            steps = clickNames.size,
            current = clickIdx,
            description = stringResource(R.string.weather_click_sub),
            onSelect = { v -> scope.launch { store.update { it.copy(weatherClickAction = v) } } },
        )
        if (clickIdx == WEATHER_CLICK_APP) {
            val appLabel = apps.firstOrNull { it.pkg == config.weatherApp }?.label
                ?: stringResource(R.string.weather_not_set)
            SettingsItem(
                onClick = { pickWeatherApp = true },
                headlineContent = { Text(stringResource(R.string.weather_app_opens)) },
                supportingContent = { Text(appLabel) },
            )
        }
    }

    if (findCity) {
        WeatherCityPicker(
            onDismiss = { findCity = false },
            onSelect = { city ->
                if (!savingCity) scope.launch {
                    savingCity = true
                    runCatching {
                        store.update {
                            it.withWeatherLocation(city.toLocation())
                        }
                    }.onSuccess { findCity = false }
                        .onFailure { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Actions.toast(context, context.getString(R.string.toast_config_save_failed))
                        }
                    savingCity = false
                }
            },
        )
    }

    if (pickWeatherApp) {
        Dialog(onDismissRequest = { pickWeatherApp = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.width(380.dp).height(480.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        stringResource(R.string.weather_app_opens),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                    )
                    val fw = initialFocus()
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
                        items(apps.size, key = { apps[it].pkg }) { i ->
                            val app = apps[i]
                            SettingsItem(
                                onClick = {
                                    scope.launch { store.update { it.copy(weatherApp = app.pkg) } }
                                    pickWeatherApp = false
                                },
                                headlineContent = { Text(app.label) },
                                leadingContent = { AppThumb(app) },
                                trailingContent = { CheckMark(checked = config.weatherApp == app.pkg) },
                                modifier = if (i == 0) Modifier.focusRequester(fw) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }

    editField?.let { field ->
        val (title, initial) = when (field) {
            "lat" -> stringResource(R.string.weather_lat) to config.weatherLat.toString()
            "lon" -> stringResource(R.string.weather_lon) to config.weatherLon.toString()
            "zip" -> stringResource(R.string.weather_zip) to config.weatherZip
            else -> stringResource(R.string.weather_country) to config.weatherCountry
        }
        TextEntryDialog(
            title = title,
            initial = initial,
            onSave = { value ->
                val clean = value.trim()
                val number = clean.toDoubleOrNull()
                val valid = when (field) {
                    "lat" -> number != null && number.isFinite() && number in -90.0..90.0
                    "lon" -> number != null && number.isFinite() && number in -180.0..180.0
                    "zip" -> clean.isNotEmpty() && clean.length <= 32 && clean.none { it.isISOControl() }
                    else -> clean.matches(Regex("[A-Za-z]{2}"))
                }
                if (valid) {
                    scope.launch {
                        store.update {
                            when (field) {
                                "lat" -> it.copy(weatherLat = number!!, weatherCityLabel = "")
                                "lon" -> it.copy(weatherLon = number!!, weatherCityLabel = "")
                                "zip" -> it.copy(weatherZip = clean)
                                else -> it.copy(weatherCountry = clean.uppercase(java.util.Locale.ROOT))
                            }
                        }
                    }
                    editField = null
                } else Actions.toast(context, context.getString(R.string.weather_invalid_input))
            },
            onDismiss = { editField = null },
        )
    }
}

/* ------------------------------ sections ------------------------------ */

@Composable
private fun CategoriesScreen(
    config: LauncherConfig,
    store: ConfigStore,
) {
    val scope = rememberCoroutineScope()
    var renameFor by remember { mutableStateOf<CategoryCfg?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var choosingIcon by remember { mutableStateOf(false) }

    fun changeSectionIcon(id: String, name: String?, enabled: Boolean) {
        scope.launch {
            store.update { cfg ->
                cfg.copy(categories = cfg.categories.map { section ->
                    if (section.id == id) section.copy(iconName = name, iconEnabled = enabled) else section
                })
            }
        }
    }

    SettingsPage {
        SettingsTitle(stringResource(R.string.item_sections))
        val f = initialFocus()
        val allSection = config.categories.firstOrNull { it.id == AppRepository.ALL_APPS_ID }
        val allName = stringResource(R.string.cat_all_apps)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SettingsControlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                SettingsItem(
                    onClick = {
                        scope.launch {
                            store.update { cfg ->
                                val rest = cfg.categories.filter { it.id != AppRepository.ALL_APPS_ID }
                                cfg.copy(categories = if (allSection != null) rest else
                                    rest + CategoryCfg(AppRepository.ALL_APPS_ID, allName))
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(f),
                    headlineContent = { Text(allName) },
                    supportingContent = { Text(stringResource(R.string.all_apps_sub)) },
                    leadingContent = {
                        if (allSection?.visibleIcon == null) Icon(AppIcons.Apps, contentDescription = null)
                        else SectionIcon(allSection.visibleIcon, Modifier.size(24.dp), tint = LocalContentColor.current)
                    },
                    trailingContent = { CheckMark(checked = allSection != null) },
                )
            }
            if (allSection != null) {
                SettingsIconButton(AppIcons.Pencil, stringResource(R.string.edit_section)) {
                    editingId = allSection.id
                }
            }
        }
        config.categories.forEachIndexed { i, cat ->
            if (cat.id == AppRepository.ALL_APPS_ID) return@forEachIndexed
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SettingsControlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SettingsItem(
                        onClick = { editingId = cat.id },
                        headlineContent = { Text(cat.name) },
                        leadingContent = {
                            if (cat.visibleIcon == null) Icon(AppIcons.Folder, contentDescription = null)
                            else SectionIcon(cat.visibleIcon, Modifier.size(24.dp), tint = LocalContentColor.current)
                        },
                    )
                }
                SettingsIconButton(AppIcons.Up, stringResource(R.string.cd_move_up)) {
                    if (i > 0) scope.launch {
                        store.update { cfg ->
                            val l = cfg.categories.toMutableList()
                            val tmp = l[i - 1]; l[i - 1] = l[i]; l[i] = tmp
                            cfg.copy(categories = l)
                        }
                    }
                }
                SettingsIconButton(AppIcons.Down, stringResource(R.string.cd_move_down)) {
                    if (i < config.categories.size - 1) scope.launch {
                        store.update { cfg ->
                            val l = cfg.categories.toMutableList()
                            val tmp = l[i + 1]; l[i + 1] = l[i]; l[i] = tmp
                            cfg.copy(categories = l)
                        }
                    }
                }
                if (config.categories.size > 1) {
                    SettingsIconButton(AppIcons.Delete, stringResource(R.string.cd_delete_section)) {
                        scope.launch {
                            store.update { cfg ->
                                cfg.copy(categories = cfg.categories.filter { it.id != cat.id })
                            }
                        }
                    }
                }
            }
        }
        val newSectionName = stringResource(R.string.new_section)
        SettingsItem(
            onClick = {
                scope.launch {
                    store.update {
                        it.copy(
                            categories = it.categories +
                                CategoryCfg("c${System.currentTimeMillis()}", newSectionName)
                        )
                    }
                }
            },
            headlineContent = { Text(stringResource(R.string.add_section)) },
            leadingContent = { Icon(AppIcons.Add, contentDescription = null) },
        )
    }

    config.categories.firstOrNull { it.id == editingId }?.let { section ->
        if (choosingIcon) {
            LibraryIconPickDialog(
                onPick = { icon ->
                    changeSectionIcon(section.id, icon.name, enabled = true)
                    choosingIcon = false
                },
                onDismiss = { choosingIcon = false },
            )
        } else if (renameFor == null) {
            SectionEditDialog(
                section = section,
                onRename = if (section.id == AppRepository.ALL_APPS_ID) null else ({ renameFor = section }),
                onToggleIcon = {
                    if (section.visibleIcon != null) changeSectionIcon(section.id, section.iconName, enabled = false)
                    else choosingIcon = true
                },
                onChooseIcon = { choosingIcon = true },
                onDismiss = { editingId = null },
            )
        }
    }

    renameFor?.let { cat ->
        TextEntryDialog(
            title = stringResource(R.string.rename_section),
            initial = cat.name,
            onSave = { newName ->
                scope.launch {
                    store.update { cfg ->
                        cfg.copy(categories = cfg.categories.map {
                            if (it.id == cat.id) it.copy(name = newName) else it
                        })
                    }
                }
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }
}

@Composable
private fun SectionEditDialog(
    section: CategoryCfg,
    onRename: (() -> Unit)?,
    onToggleIcon: () -> Unit,
    onChooseIcon: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.width(410.dp).padding(SettingsPageInset), verticalArrangement = Arrangement.spacedBy(SettingsRowGap)) {
                Text(section.name, style = MaterialTheme.typography.titleMedium)
                val focus = initialFocus()
                if (onRename != null) {
                    SettingsItem(
                        onClick = onRename,
                        modifier = Modifier.focusRequester(focus),
                        headlineContent = { Text(stringResource(R.string.rename_section)) },
                        leadingContent = { Icon(AppIcons.Pencil, contentDescription = null) },
                    )
                }
                SettingsItem(
                    onClick = onToggleIcon,
                    modifier = if (onRename == null) Modifier.focusRequester(focus) else Modifier,
                    headlineContent = { Text(stringResource(R.string.section_icon)) },
                    trailingContent = { CheckMark(section.visibleIcon != null) },
                )
                if (section.visibleIcon != null) {
                    SettingsItem(
                        onClick = onChooseIcon,
                        headlineContent = { Text(stringResource(R.string.section_choose_icon)) },
                        leadingContent = { SectionIcon(section.visibleIcon, Modifier.size(24.dp), tint = LocalContentColor.current) },
                    )
                }
                SettingsButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

@Composable
private fun RecommendedAppsSettings(config: LauncherConfig, store: ConfigStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editUrl by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val focus = initialFocus()

    SettingsPage {
        SettingsTitle(stringResource(R.string.recommended_settings))
        SettingsItem(
            onClick = { error = null; editUrl = true },
            modifier = Modifier.focusRequester(focus),
            leadingContent = { Icon(AppIcons.Link, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.recommended_catalog_prompt)) },
            supportingContent = {
                Text(stringResource(R.string.recommended_catalog_description))
                if (config.recommendedAppsUrl.isNotBlank()) {
                    Text(
                        config.recommendedAppsUrl,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        )
        if (config.recommendedAppsUrl.isNotBlank()) {
            SettingsItem(
                onClick = {
                    if (config.recommendedAppsEnabled) {
                        scope.launch { store.update { it.copy(recommendedAppsEnabled = false) } }
                    } else {
                        error = null
                        editUrl = true
                    }
                },
                headlineContent = { Text(stringResource(R.string.recommended_settings)) },
                trailingContent = { CheckMark(config.recommendedAppsEnabled) },
            )
        }
    }

    if (editUrl) {
        TextEntryDialog(
            title = stringResource(R.string.recommended_catalog_prompt),
            initial = config.recommendedAppsUrl,
            onSave = { url ->
                if (!checking) {
                    checking = true
                    error = null
                    scope.launch {
                        try {
                            RecommendedApps(context, url).refresh()
                            store.update { it.copy(recommendedAppsEnabled = true, recommendedAppsUrl = url) }
                            editUrl = false
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = R.string.recommended_catalog_invalid
                        } finally {
                            checking = false
                        }
                    }
                }
            },
            onDismiss = { if (!checking) editUrl = false },
            busy = checking,
            status = if (checking) R.string.recommended_loading else error,
            urlInput = true,
            positionAboveKeyboard = true,
        )
    }
}

/** The ONLY place with a text field, so D-pad browsing never lands in an editor. */
@Composable
internal fun TextEntryDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    submitLabel: Int = R.string.save,
    allowEmpty: Boolean = false,
    busy: Boolean = false,
    status: Int? = null,
    urlInput: Boolean = false,
    positionAboveKeyboard: Boolean = false,
) {
    var name by remember { mutableStateOf(initial) }
    val keyboard = LocalSoftwareKeyboardController.current
    val saveFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val canSave = !busy && (allowEmpty || name.isNotBlank())
    fun submit() {
        val trimmed = name.trim()
        if (!busy && (allowEmpty || trimmed.isNotEmpty())) onSave(trimmed)
    }
    Dialog(onDismissRequest = onDismiss) {
        if (positionAboveKeyboard) PositionDialogAboveKeyboard()
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.width(340.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val f = initialFocus()
                var editorFocused by remember { mutableStateOf(false) }
                if (urlInput) LaunchedEffect(Unit) {
                    f.requestFocus()
                    withFrameNanos {}
                    keyboard?.show()
                }
                Box(
                    Modifier.fillMaxWidth()
                        .settingsFieldFrame(editorFocused)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        readOnly = busy,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            textDirection = if (urlInput) TextDirection.Ltr else TextDirection.Unspecified,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = if (urlInput) KeyboardType.Uri else KeyboardType.Text,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submit() }
                        ),
                        decorationBox = { editor ->
                            Box {
                                if (urlInput && name.isEmpty()) {
                                    Text(
                                    "https://",
                                    color = Color(0xFFC3CBD8),
                                    style = TextStyle(fontSize = 15.sp, textDirection = TextDirection.Ltr),
                                )
                                }
                                editor()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(f)
                            .onFocusChanged { editorFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (urlInput && event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown) {
                                    (if (canSave) saveFocus else cancelFocus).requestFocus()
                                    true
                                } else false
                            },
                    )
                }
                if (status != null) Text(stringResource(status))
                Row(horizontalArrangement = Arrangement.spacedBy(SettingsControlGap)) {
                    SettingsButton(onClick = ::submit, enabled = !urlInput || canSave, modifier = Modifier.focusRequester(saveFocus)) {
                        Text(stringResource(submitLabel))
                    }
                    SettingsButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

/* ------------------------------ icon pack ------------------------------ */

@Composable
private fun IconPackScreen(
    config: LauncherConfig,
    apps: List<AppEntry>,
    store: ConfigStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs by produceState(initialValue = emptyList<com.conreo.couchytv.data.IconPackInfo>(), apps) {
        value = withContext(Dispatchers.IO) { IconPack.installedIconPacks(context) }
    }
    val matchCounts by produceState(initialValue = emptyMap<String, Int>(), packs, apps) {
        value = withContext(Dispatchers.IO) {
            packs.associate { pack ->
                val map = runCatching { IconPack.appFilterMap(context, pack.pkg) }.getOrNull().orEmpty()
                pack.pkg to apps.count { it.pkg in map }
            }
        }
    }

    SettingsPage {
        SettingsTitle(stringResource(R.string.item_icon_pack))
        Text(
            stringResource(R.string.icon_pack_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp),
        )
        val f = initialFocus()
        SettingsItem(
            onClick = { scope.launch { store.update { it.copy(iconPackPkg = "") } } },
            modifier = Modifier.focusRequester(f),
            headlineContent = { Text(stringResource(R.string.icon_pack_none)) },
            trailingContent = { CheckMark(checked = config.iconPackPkg.isEmpty()) },
        )
        packs.forEach { pack ->
            SettingsItem(
                onClick = { scope.launch { store.update { it.copy(iconPackPkg = pack.pkg) } } },
                headlineContent = { Text(pack.label) },
                supportingContent = {
                    Text(stringResource(R.string.icon_pack_match_count, matchCounts[pack.pkg] ?: 0, apps.size))
                },
                leadingContent = {
                    if (pack.icon != null) {
                        Image(bitmap = pack.icon, contentDescription = null, modifier = Modifier.size(30.dp))
                    }
                },
                trailingContent = { CheckMark(checked = config.iconPackPkg == pack.pkg) },
            )
        }
        if (packs.isEmpty()) {
            Text(
                stringResource(R.string.icon_pack_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, start = 8.dp),
            )
        }
    }
}

/* ------------------------------ wallpaper ------------------------------ */

/**
 * ONE row per background mode, checkmark = active. Selecting any mode turns
 * the others off. Two groups: Static (color, photo) and Video (aerials, video).
 */
@Composable
private fun WallpaperScreen(
    config: LauncherConfig,
    onSelectBuiltinAerials: () -> Unit,
    onSelectBuiltinSource: (Int) -> Unit,
    onSelectAerialQuality: (Int) -> Unit,
    onRestartAerials: () -> Unit,
    onSetScrim: (Int) -> Unit,
    onSetSpeed: (Int) -> Unit,
    onPreset: (Int) -> Unit,
    onSelectTopShelf: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickVideo: () -> Unit,
) {
    SettingsPage {
        SettingsTitle(stringResource(R.string.item_wallpaper))
        val f = initialFocus()
        val staticActive = !config.useBuiltinAerials &&
            !config.useVideoWallpaper && !config.useCustomWallpaper && !config.useTopShelf

        // Dimming — how much the wallpaper is darkened for icon readability.
        // Applies to any background; "Top & bottom" keeps the centre clear.
        run {
            val names = listOf(
                stringResource(R.string.dim_top_bottom),
                stringResource(R.string.dim_top),
                stringResource(R.string.dim_bottom),
                stringResource(R.string.dim_full),
                stringResource(R.string.dim_off),
            )
            val idx = config.scrimMode.coerceIn(0, names.size - 1)
            SelectorRow(
                modifier = Modifier.focusRequester(f),
                label = stringResource(R.string.dim_label),
                value = names[idx],
                steps = names.size,
                current = idx,
                onSelect = { v -> onSetScrim(v) },
            )
        }

        /* ---------------- STATIC: color gradient + your own photo ---------------- */
        SettingsSectionLabel(stringResource(R.string.wp_section_static).uppercase())

        // Color gradient mode — checkmark like every other mode; the picker
        // below appears once it's active (mirrors the aerial collection row).
        val presetIdx = config.wallpaper.coerceIn(0, WALLPAPERS.size - 1)
        SettingsItem(
            onClick = { onPreset(presetIdx) },
            headlineContent = { Text(stringResource(R.string.color_gradient)) },
            leadingContent = { Icon(AppIcons.Palette, contentDescription = null) },
            trailingContent = { CheckMark(checked = staticActive) },
        )
        if (staticActive) {
            val presetNames = listOf(
                stringResource(R.string.wp_midnight),
                stringResource(R.string.wp_aurora),
                stringResource(R.string.wp_sunset),
                stringResource(R.string.wp_deep),
                stringResource(R.string.wp_charcoal),
            )
            SelectorRow(
                label = stringResource(R.string.preset_label),
                value = presetNames[presetIdx],
                steps = WALLPAPERS.size,
                current = presetIdx,
                onSelect = { v -> onPreset(v) },
            )
        }

        // A photo of your own
        SettingsItem(
            onClick = onPickPhoto,
            headlineContent = { Text(stringResource(R.string.pick_photo)) },
            supportingContent = { Text(stringResource(R.string.pick_photo_sub)) },
            leadingContent = { Icon(AppIcons.Image, contentDescription = null) },
            trailingContent = { CheckMark(checked = config.useCustomWallpaper) },
        )

        SettingsItem(
            onClick = onSelectTopShelf,
            headlineContent = { Text(stringResource(R.string.top_shelf)) },
            supportingContent = { Text(stringResource(R.string.top_shelf_desc)) },
            leadingContent = { Icon(AppIcons.Display, contentDescription = null) },
            trailingContent = { CheckMark(checked = config.useTopShelf) },
        )

        /* ---------------- VIDEO: built-in aerials + your own video ---------------- */
        SettingsSectionLabel(stringResource(R.string.wp_section_video).uppercase())

        // Built-in aerial videos
        SettingsItem(
            onClick = onSelectBuiltinAerials,
            headlineContent = { Text(stringResource(R.string.builtin_aerials)) },
            supportingContent = { Text(stringResource(R.string.builtin_aerials_sub)) },
            leadingContent = { Icon(AppIcons.Play, contentDescription = null) },
            trailingContent = { CheckMark(checked = config.useBuiltinAerials) },
        )
        if (config.useBuiltinAerials) {
            val reduced = config.aerialQuality == BuiltinAerials.QUALITY_720P
            val quality = if (reduced) BuiltinAerials.QUALITY_720P else BuiltinAerials.QUALITY_1080P
            val labels = listOf("1080p", "720p")
            SelectorRow(
                label = stringResource(R.string.aerial_quality),
                value = labels[quality],
                steps = labels.size,
                current = quality,
                onSelect = onSelectAerialQuality,
                description = if (reduced) stringResource(R.string.aerial_quality_amazon) else null,
            )
            if (!reduced) {
                val sourceNames = listOf(
                    stringResource(R.string.aerial_src_all),
                    stringResource(R.string.aerial_src_apple),
                    stringResource(R.string.aerial_src_amazon),
                    stringResource(R.string.aerial_src_comm1),
                    stringResource(R.string.aerial_src_comm2),
                )
                val idx = config.builtinSource.coerceIn(0, sourceNames.size - 1)
                SelectorRow(
                    label = stringResource(R.string.aerial_src_label),
                    value = sourceNames[idx],
                    steps = sourceNames.size,
                    current = idx,
                    onSelect = { v -> onSelectBuiltinSource(v) },
                )
            }
            SettingsItem(
                onClick = onRestartAerials,
                headlineContent = { Text(stringResource(R.string.aerial_restart_playback)) },
                leadingContent = { Icon(AppIcons.Refresh, contentDescription = null) },
            )
        }

        // A video file of your own
        SettingsItem(
            onClick = onPickVideo,
            headlineContent = { Text(stringResource(R.string.pick_video)) },
            supportingContent = { Text(stringResource(R.string.pick_video_sub)) },
            leadingContent = { Icon(AppIcons.Image, contentDescription = null) },
            trailingContent = { CheckMark(checked = config.useVideoWallpaper) },
        )

        // Playback speed — applies to aerials and your own video.
        run {
            val labels = listOf("0.25×", "0.5×", "0.75×", "1×")
            val idx = config.videoSpeed.coerceIn(0, labels.size - 1)
            SelectorRow(
                label = stringResource(R.string.speed_label),
                value = labels[idx],
                steps = labels.size,
                current = idx,
                onSelect = { v -> onSetSpeed(v) },
            )
        }
    }
}
