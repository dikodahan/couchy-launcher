package com.conreo.couchytv.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.Actions
import com.conreo.couchytv.R
import com.conreo.couchytv.data.reconcileInstalledApps
import com.conreo.couchytv.data.AppEntry
import com.conreo.couchytv.data.AppPlacement
import com.conreo.couchytv.data.applyReorder
import com.conreo.couchytv.data.AppRepository
import com.conreo.couchytv.data.AerialVideoCache
import com.conreo.couchytv.data.AerialLoadErrorPolicy
import com.conreo.couchytv.data.BuiltinAerials
import com.conreo.couchytv.data.StoredConfig
import com.conreo.couchytv.data.ConfigStore
import com.conreo.couchytv.data.IconPack
import com.conreo.couchytv.data.GLASS_STYLE_LIQUID
import com.conreo.couchytv.data.LAYOUT_DOCK
import com.conreo.couchytv.data.LAYOUT_GRID
import com.conreo.couchytv.data.LauncherConfig
import com.conreo.couchytv.data.NetStatus
import com.conreo.couchytv.data.ParentalControls
import com.conreo.couchytv.data.ParentalState
import com.conreo.couchytv.data.WEATHER_CLICK_APP
import com.conreo.couchytv.data.WEATHER_SRC_GEOIP
import com.conreo.couchytv.data.WEATHER_CLICK_FORECAST
import com.conreo.couchytv.data.WeatherFailure
import com.conreo.couchytv.data.WeatherResult
import com.conreo.couchytv.data.WeatherRepository
import com.conreo.couchytv.data.networkStatusFlow
import com.conreo.couchytv.data.resolveGlassStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Icon size steps (card width) — 5 levels for fine control. */
val ICON_SIZES = listOf(120.dp, 150.dp, 190.dp, 230.dp, 270.dp)

/** Allocated ONCE — recomposition never rebuilds the scrim brushes. */
// Full: darkens the whole wallpaper evenly.
private val ScrimFull = Brush.verticalGradient(
    listOf(
        Color.Black.copy(alpha = 0.55f),
        Color.Black.copy(alpha = 0.25f),
        Color.Black.copy(alpha = 0.45f),
    )
)
// Top & bottom: dark bands only where the status bar and dock/labels sit; the
// middle of the wallpaper stays fully clear (video/aerials keep their punch).
private val ScrimTopBottom = Brush.verticalGradient(
    0.0f to Color.Black.copy(alpha = 0.6f),
    0.16f to Color.Black.copy(alpha = 0f),
    0.82f to Color.Black.copy(alpha = 0f),
    1.0f to Color.Black.copy(alpha = 0.5f),
)
// Top only — dark band under the status bar, rest clear.
private val ScrimTop = Brush.verticalGradient(
    0.0f to Color.Black.copy(alpha = 0.6f),
    0.20f to Color.Black.copy(alpha = 0f),
    1.0f to Color.Black.copy(alpha = 0f),
)
// Bottom only — dark band under the dock/labels, rest clear.
private val ScrimBottom = Brush.verticalGradient(
    0.0f to Color.Black.copy(alpha = 0f),
    0.80f to Color.Black.copy(alpha = 0f),
    1.0f to Color.Black.copy(alpha = 0.5f),
)
/** Spacing steps between cards — 5 levels. */
val GAP_SIZES = listOf(4.dp, 10.dp, 16.dp, 24.dp, 32.dp)
/** Video wallpaper playback speeds (index stored in config.videoSpeed). */
val VIDEO_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f)
/** Icon corner roundness steps (index stored in config.cornerRadius). */
val CORNER_RADII = listOf(0.dp, 4.dp, 10.dp, 18.dp, 28.dp)
/** Manual whole-UI scale steps (config.uiScale 1..5). <1 = more compact. */
val UI_SCALES = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f)

/** The effective UI scale: 0 = Auto (compact high-DPI TVs down to ~1200dp of
 *  width so every device reads the same, never enlarging roomy ones); 1..5 pick
 *  a fixed value from [UI_SCALES]. */
fun uiScaleFactor(index: Int, screenWidthDp: Int): Float =
    if (index <= 0) (screenWidthDp / 1200f).coerceIn(0.6f, 1.0f)
    else UI_SCALES[(index - 1).coerceIn(0, UI_SCALES.size - 1)]

/** Global text boost (sp only, not dp): 1 = off. Raise to enlarge every label
 *  without touching icons or layout. */
const val FONT_BOOST = 1f

/** Renders [content] at [scale]× the device density — one knob shrinks/grows
 *  every dp and sp uniformly (icons, text, settings, wizard). Also rescales the
 *  Configuration so width-derived layout (dock/grid columns) stays correct. */
@Composable
fun ScaledUi(scale: Float, content: @Composable () -> Unit) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val scaledCfg = remember(cfg, scale) {
        android.content.res.Configuration(cfg).apply {
            screenWidthDp = (cfg.screenWidthDp / scale).toInt()
            screenHeightDp = (cfg.screenHeightDp / scale).toInt()
            smallestScreenWidthDp = (cfg.smallestScreenWidthDp / scale).toInt()
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(base.density * scale, base.fontScale * FONT_BOOST),
        androidx.compose.ui.platform.LocalConfiguration provides scaledCfg,
    ) { content() }
}
// compositionLocalOf (not static): roundness changes at runtime and must
// recompose every reader, including those inside subcompositions like the
// dock's BoxWithConstraints (its 2nd-row peek) — static wouldn't reach them.
val LocalCornerRadius = androidx.compose.runtime.compositionLocalOf { 10.dp }

/**
 * How many full-size cards fit in [available] width, and the spacing to use.
 * Icons NEVER shrink: if the requested gap doesn't fit, the gap is squeezed
 * instead so every icon keeps its exact size.
 */
fun fitRow(available: Dp, cardWidth: Dp, gap: Dp): Pair<Int, Dp> {
    val cols = (((available + gap) / (cardWidth + gap)).toInt()).coerceAtLeast(1)
    val leftover = available - cardWidth * cols
    val used = if (cols > 1 && leftover > 0.dp) {
        val spread = leftover / (cols - 1)
        if (spread < gap) spread else gap
    } else 0.dp
    return cols to used
}

/** Unwrap a (possibly wrapped) Context to its host Activity, or null. */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
fun LauncherApp(rescanTick: Int, homeIntentTick: Int) {
    val context = LocalContext.current
    val store = remember(context) { ConfigStore(context) }
    val scope = rememberCoroutineScope()

    // Wait for the persisted config before drawing anything, so the setup
    // wizard never flashes for users who already completed it.
    val parental = remember(context) { ParentalControls(context) }
    val parentalOrNull by produceState<ParentalState?>(null) {
        parental.state.collect { value = it }
    }
    val storedConfig by produceState<StoredConfig?>(initialValue = null, store) {
        if (store.prepareFirstLaunch()) launch { store.finishInitialWeatherUnit() }
        store.storedConfig.collect { value = it }
    }
    // Kick off the app scan on the FIRST composition — concurrent with the
    // DataStore config read, not serialized after it (scan takes no config).
    // Warm relaunches start from the in-memory cache (instant); a fresh scan
    // still runs to pick up changes. Not rendered until past the gates below.
    val appsOrNull by produceState(initialValue = AppRepository.memoryCache, rescanTick) {
        value = withContext(Dispatchers.Default) { AppRepository.scan(context.applicationContext) }
    }
    val parentalState = parentalOrNull ?: run {
        LoadingScreen()
        return
    }
    val (config, restoreRevision, useInitialIpLocation) = storedConfig ?: run {
        LoadingScreen()
        return
    }

    val startupPrefs = remember(context) { context.getSharedPreferences("startup", android.content.Context.MODE_PRIVATE) }
    var startupIntroSeen by remember(startupPrefs) {
        mutableStateOf(startupPrefs.getBoolean("intro_seen", false))
    }
    if (!config.setupDone && !startupIntroSeen) {
        StartupIntro {
            startupPrefs.edit().putBoolean("intro_seen", true).apply()
            startupIntroSeen = true
        }
        return
    }

    val uiScale = uiScaleFactor(
        config.uiScale,
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp,
    )

    // Mirror the chosen language to SharedPreferences (which attachBaseContext
    // reads synchronously) and recreate the activity when it changes, so the new
    // locale takes effect without a restart.
    val activity = remember(context) { context.findActivity() }
    LaunchedEffect(config.language) {
        if (com.conreo.couchytv.MainActivity.currentLocalePref(context) != config.language) {
            com.conreo.couchytv.MainActivity.persistLocale(context, config.language)
            activity?.recreate()
        }
    }
    // The SurfaceView video wallpaper needs a transparent window to show through;
    // every other mode draws an opaque wallpaper, so keep the window opaque then
    // (a transparent window would let whatever is behind the home leak through).
    val videoWallpaper = !config.useTopShelf &&
        (config.useBuiltinAerials || (config.useVideoWallpaper && config.videoUri.isNotEmpty()))
    LaunchedEffect(activity, videoWallpaper) {
        activity?.window?.setBackgroundDrawable(
            if (videoWallpaper) null
            else android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)
        )
    }

    var savingSetup by remember { mutableStateOf(false) }
    var pressHomeAtTick by remember { mutableStateOf<Int?>(null) }
    val showSetupHomePrompt = pressHomeAtTick == homeIntentTick
    LaunchedEffect(homeIntentTick) {
        if (pressHomeAtTick != homeIntentTick) pressHomeAtTick = null
    }
    if (!config.setupDone) {
        ScaledUi(uiScale) {
            LiteTvTheme(accent = ACCENTS[0]) {
                SetupWizard(
                    config = config,
                    saving = savingSetup,
                    languageApplied = (activity as? com.conreo.couchytv.MainActivity)?.attachedLanguage == config.language,
                    useInitialIpLocation = useInitialIpLocation,
                    onChooseLanguage = { language -> store.update { it.copy(language = language) } },
                    onConfigureWeather = store::saveSetupWeather,
                    onDone = { pressHome, vpnPackage ->
                        if (!savingSetup) {
                            savingSetup = true
                            scope.launch {
                                try {
                                    store.update { current ->
                                        current.copy(
                                            setupDone = true,
                                            showVpnButton = vpnPackage != null,
                                            vpnApp = vpnPackage.orEmpty(),
                                        )
                                    }
                                    // Android can bring this activity to the front via Home.
                                    // Commit setup before asking the user to press that button.
                                    if (pressHome) pressHomeAtTick = homeIntentTick
                                } catch (_: IOException) {
                                    Actions.toast(context, context.getString(R.string.toast_config_save_failed))
                                } finally {
                                    savingSetup = false
                                }
                            }
                        }
                    },
                )
            }
        }
        return
    }

    // Hold the sober loading screen until the launcher can render COMPLETE —
    // every app scanned and every icon decoded.
    val apps = appsOrNull ?: run {
        LoadingScreen()
        return
    }
    // flowOn(IO): the callbackFlow's initial compute() does ConnectivityManager
    // binder calls — keep them off the main thread on the first real frame.
    val net by remember { networkStatusFlow(context).flowOn(Dispatchers.IO) }
        .collectAsStateWithLifecycle(initialValue = NetStatus())

    // Bump on every ON_RESUME so the clock/date refresh immediately after
    // sleep — they normally only tick on minute boundaries via produceState.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Format with the app's current locale (reflects the language override, and
    // recomposes on change) — not Locale.getDefault(), which the framework can
    // reset, leaving the date stuck in the previous language.
    val locale = androidx.core.os.ConfigurationCompat
        .getLocales(androidx.compose.ui.platform.LocalConfiguration.current)
        .get(0) ?: Locale.getDefault()
    val time by produceState(initialValue = "", config.h24, locale, resumeTick) {
        val fmt = SimpleDateFormat(if (config.h24) "HH:mm" else "h:mm a", locale)
        while (true) {
            value = fmt.format(Date())
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }
    val date by produceState(initialValue = "", config.dateFormat, locale, resumeTick) {
        val pattern = com.conreo.couchytv.data.DATE_FORMATS
            .getOrElse(config.dateFormat) { "" }
        if (pattern.isEmpty()) { value = ""; return@produceState }
        val fmt = SimpleDateFormat(pattern, locale)
        while (true) {
            value = fmt.format(Date())
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    // Clear old data immediately when units/location change, and only poll while Home is visible.
    val weatherRequest = remember(config.weatherEnabled, config.weatherLocationSource, config.weatherGeoJsEnabled,
        config.weatherLat, config.weatherLon, config.weatherZip, config.weatherCountry, config.weatherUnit) {
        com.conreo.couchytv.data.WeatherRequest.from(config)
    }
    var weatherRetryTick by remember { mutableIntStateOf(0) }
    var weatherResult by remember(weatherRequest, resumeTick, weatherRetryTick) {
        mutableStateOf<WeatherResult?>(null)
    }
    LaunchedEffect(weatherRequest, resumeTick, weatherRetryTick, lifecycleOwner) {
        val request = weatherRequest ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                weatherResult = withContext(Dispatchers.IO) { WeatherRepository.fetch(context.applicationContext, request) }
                delay(30 * 60_000L)
            }
        }
    }
    val weather = weatherResult?.data
    val needsGeoJs = config.weatherEnabled && config.weatherLocationSource == WEATHER_SRC_GEOIP &&
        !config.weatherGeoJsEnabled
    val weatherStatus = when {
        !config.weatherEnabled || needsGeoJs -> null
        weatherRequest == null -> R.string.weather_location_missing
        weatherResult == null -> R.string.weather_loading
        weatherResult?.usingSavedIpLocation == true && weather != null -> R.string.weather_using_saved_ip_city
        else -> when (weatherResult?.failure) {
            WeatherFailure.IpRateLimited -> R.string.weather_ip_rate_limited
            WeatherFailure.LocationUnavailable -> R.string.weather_location_failed
            WeatherFailure.ForecastUnavailable -> R.string.weather_forecast_failed
            null -> null
        }
    }

    // Per-app rename/icon/banner overrides + icon-pack icons layered on top of
    // the raw scan — recomputed off the main thread whenever config or the
    // scan itself changes.
    var overrideVersion by remember { mutableIntStateOf(0) }
    val displayApps by produceState(initialValue = apps, apps, config.labelOverrides, config.iconPackPkg, overrideVersion) {
        value = AppRepository.overlay(context.applicationContext, apps, config)
    }

    // New installs are auto-added to the FIRST section.
    LaunchedEffect(apps) {
        if (apps.isEmpty()) return@LaunchedEffect
        val current = apps.map { it.pkg }.toSet()
        store.update { it.reconcileInstalledApps(current) }
    }

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var customizeFor by remember { mutableStateOf<AppEntry?>(null) }
    var renameFor by remember { mutableStateOf<AppEntry?>(null) }
    var reorderFrom by remember { mutableStateOf<AppPlacement?>(null) }
    var menuSectionId by remember { mutableStateOf<String?>(null) }
    var savingReorder by remember { mutableStateOf(false) }
    fun openAppMenu(app: AppEntry, section: String? = null) {
        menuSectionId = section
        menuFor = app
    }
    var showSettings by remember { mutableStateOf(false) }
    var settingsStartAtWeather by remember { mutableStateOf(false) }
    // Only true when Settings was opened by a still-held long-press (the
    // weather chip): the eventual key-up of that hold must not fall through
    // to whatever is focused first once the sheet appears (see SettingsSheet).
    var settingsSuppressRelease by remember { mutableStateOf(false) }
    var showForecast by remember { mutableStateOf(false) }
    var wallpaperVersion by remember { mutableIntStateOf(0) }

    var pinPrompt by remember { mutableStateOf<PinPrompt?>(null) }
    var closingApp by remember { mutableStateOf(false) }
    var closeFailure by remember { mutableStateOf<Pair<String, Int>?>(null) }

    fun protectedAction(pkg: String, action: () -> Unit) {
        if (parentalState.isLocked(pkg)) {
            pinPrompt = PinPrompt(R.string.parental_open_app, verify = parental::verify, onAccepted = action)
        } else action()
    }

    fun launchApp(pkg: String) = protectedAction(pkg) { Actions.launchApp(context, pkg) }
    fun appInfo(pkg: String) = protectedAction(pkg) { Actions.openAppInfo(context, pkg) }
    fun uninstallApp(pkg: String) = protectedAction(pkg) { Actions.uninstall(context, pkg) }

    fun closeApp(pkg: String) {
        if (closingApp) return
        closingApp = true
        scope.launch {
            val result = try {
                Actions.closeAndClearCache(context, pkg)
            } finally {
                closingApp = false
            }
            val message = when (result) {
                Actions.CloseResult.Complete -> R.string.close_cache_complete
                Actions.CloseResult.Stopped -> R.string.close_cache_partial
                Actions.CloseResult.AdbUnavailable -> R.string.adb_enable_required
                Actions.CloseResult.Unavailable -> R.string.close_cache_unavailable
            }
            if (result == Actions.CloseResult.Complete) Actions.toast(context, context.getString(message))
            else closeFailure = pkg to message
        }
    }

    fun openWeatherSettings(suppressRelease: Boolean = false) {
        settingsStartAtWeather = true
        settingsSuppressRelease = suppressRelease
        showSettings = true
    }
    fun onWeatherClick() {
        when (config.weatherClickAction) {
            WEATHER_CLICK_FORECAST ->
                if (weather != null) showForecast = true else openWeatherSettings()
            WEATHER_CLICK_APP ->
                if (config.weatherApp.isNotEmpty()) launchApp(config.weatherApp) else openWeatherSettings()
            else -> openWeatherSettings()
        }
    }

    // Icon picker for the per-app "Customize" menu — same OpenDocument pattern
    // SettingsSheet uses for the wallpaper photo picker. Only one slot (banner)
    // is written to — see the comment by IconSourceMenu's usage below.
    var bannerTargetPkg by remember { mutableStateOf<String?>(null) }
    // "Change icon" also offers another installed app's icon, or a pack icon,
    // as poor substitutes for a file browser aren't reliably present on TV OSes.
    var iconSourceFor by remember { mutableStateOf<AppEntry?>(null) }
    var appIconPickFor by remember { mutableStateOf<AppEntry?>(null) }
    var packIconPickFor by remember { mutableStateOf<AppEntry?>(null) }
    var libraryIconPickFor by remember { mutableStateOf<AppEntry?>(null) }
    val updatePrompt = rememberAppUpdatePrompt(
        connected = net.connected,
        homeIdle = !showSetupHomePrompt && !showSettings && !showForecast && reorderFrom == null && menuFor == null &&
            customizeFor == null && renameFor == null && pinPrompt == null &&
            closeFailure == null && iconSourceFor == null && appIconPickFor == null &&
            packIconPickFor == null && libraryIconPickFor == null,
    )
    val iconPacks by produceState(initialValue = emptyList<com.conreo.couchytv.data.IconPackInfo>(), apps, resumeTick) {
        value = withContext(Dispatchers.IO) { IconPack.installedIconPacks(context.applicationContext) }
    }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pkg = bannerTargetPkg; bannerTargetPkg = null
        if (uri != null && pkg != null) {
            scope.launch {
                if (AppRepository.saveOverrideBanner(context.applicationContext, pkg, uri)) overrideVersion++
                else Actions.toast(context, context.getString(R.string.icon_save_failed))
            }
        }
    }

    // Drop empty sections: an empty row is dead space AND a focus trap that
    // stops D-pad navigation from reaching the section below it.
    val sectionRows = remember(displayApps, config) { AppRepository.categorize(displayApps, config) }
    val categorized = remember(sectionRows) { sectionRows.filter { it.second.isNotEmpty() } }
    // The dock's flat app list (all sections concatenated, de-duped). Hoisted &
    // remembered so its identity stays stable across recompositions — otherwise a
    // fresh list on every clock tick blocks DockArea from strong-skipping.
    val dockApps = remember(categorized) {
        categorized.flatMap { it.second }.distinctBy { it.pkg }
    }
    val accent = ACCENTS[config.accent.coerceIn(0, ACCENTS.size - 1)]

    // ----- display dimensions: size + spacing drive everything, columns
    // are always derived from available width (never a fixed count) -----
    val cardWidth: Dp = ICON_SIZES[config.iconScale.coerceIn(0, ICON_SIZES.size - 1)]
    val gap: Dp = GAP_SIZES[config.spacing.coerceIn(0, GAP_SIZES.size - 1)]

    var dockExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(config.layout) { if (config.layout != LAYOUT_DOCK) dockExpanded = false }

    // ----- wallpaper: decoded off the main thread, downsampled, cached.
    // A ~100px copy is decoded alongside: upscaled by the GPU it looks
    // blurred, and crossfading it in costs one texture blend per frame
    // instead of a full-screen RenderEffect blur (heavy on TV GPUs). -----
    val wallpaperPair by produceState<Pair<ImageBitmap?, ImageBitmap?>>(
        initialValue = null to null, config.useCustomWallpaper, wallpaperVersion, restoreRevision
    ) {
        value = if (!config.useCustomWallpaper) null to null
        else withContext(Dispatchers.IO) {
            val f = File(context.filesDir, "wallpaper.jpg")
            if (!f.exists()) null to null
            else runCatching {
                // 1280px is indistinguishable as a scrimmed background but the
                // texture is ~2.3× smaller than 1920 — TV GPUs choke on the
                // first upload of full-res textures. prepareToDraw() moves the
                // upload off the first visible frame.
                val sharp = decodeDownsampled(f, maxWidth = 1280)
                    ?.also { it.prepareToDraw() }?.asImageBitmap()
                val blurred = decodeDownsampled(f, maxWidth = 96)
                    ?.also { it.prepareToDraw() }?.asImageBitmap()
                sharp to blurred
            }.getOrDefault(null to null)
        }
    }
    val (wallpaperSharp, wallpaperBlurred) = wallpaperPair
    val presetBrush = remember(config.wallpaper) {
        WALLPAPERS[config.wallpaper.coerceIn(0, WALLPAPERS.size - 1)].brush()
    }

    // ----- Built-in aerial videos: keep a shuffled queue so ExoPlayer can
    // prepare the successor before the current clip reaches its last frame. -----
    val aerialPlaylist by key(config.useBuiltinAerials, config.builtinSource, config.aerialQuality, wallpaperVersion, restoreRevision) {
        produceState<List<String>>(initialValue = emptyList()) {
            if (!config.useBuiltinAerials) return@produceState
            value = withContext(Dispatchers.IO) {
                AerialVideoCache.warm(context.applicationContext)
                BuiltinAerials.load(context, config.builtinSource, config.aerialQuality).shuffled()
            }
        }
    }
    var aerialPosition by remember(aerialPlaylist) { mutableIntStateOf(0) }
    val aerialWallpaper = if (aerialPlaylist.isEmpty()) null
        else aerialPlaylist[aerialPosition.mod(aerialPlaylist.size)]
    val nextAerialWallpaper = if (aerialPlaylist.size < 2) null
        else aerialPlaylist[(aerialPosition + 1).mod(aerialPlaylist.size)]

    // Keep the player and its surface during a quick skip. Repeated failures
    // fall back to the gradient briefly before trying the next clip.
    // Give a restored wallpaper the same fresh playback state as a newly selected source.
    var aerialError by remember(restoreRevision, config.useBuiltinAerials, config.builtinSource, config.aerialQuality, net.connected) {
        mutableStateOf(false)
    }
    var aerialFailureStreak by remember(restoreRevision, config.useBuiltinAerials, config.builtinSource, config.aerialQuality, net.connected) {
        mutableIntStateOf(0)
    }
    var aerialCoolingDown by remember(restoreRevision, config.useBuiltinAerials, config.builtinSource, config.aerialQuality, net.connected) {
        mutableStateOf(false)
    }
    fun recordAerialFailure() {
        if (aerialError || aerialCoolingDown) return
        aerialFailureStreak++
        if (aerialFailureStreak >= 6 || aerialPlaylist.size < 2) {
            aerialCoolingDown = true
        } else {
            aerialError = true
        }
    }
    fun recordAerialReady(firstFrameMs: Long) {
        aerialFailureStreak = 0
        android.util.Log.d("Couchy", "Aerial ready in ${firstFrameMs}ms; cache=${AerialVideoCache.bytesUsed()}")
    }
    LaunchedEffect(aerialCoolingDown, restoreRevision) {
        if (!aerialCoolingDown) return@LaunchedEffect
        delay(30_000L)
        aerialPosition = (aerialPosition + 1).mod(aerialPlaylist.size.coerceAtLeast(1))
        aerialFailureStreak = 0
        aerialCoolingDown = false
    }
    LaunchedEffect(aerialError, net.connected, aerialPlaylist, restoreRevision) {
        if (!aerialError || !net.connected || aerialPlaylist.isEmpty()) return@LaunchedEffect
        delay(200L)
        aerialPosition = (aerialPosition + 1).mod(aerialPlaylist.size)
        aerialError = false
    }

    var shelfFocus by remember { mutableStateOf<Pair<Any, String>?>(null) }
    val reportShelfFocus = remember {
        { token: Any, packageName: String, focused: Boolean ->
            if (focused) shelfFocus = token to packageName
            else if (shelfFocus?.first == token) shelfFocus = null
        }
    }
    val shelfPackage = if (config.useTopShelf && reorderFrom == null && LocalWindowInfo.current.isWindowFocused) {
        shelfFocus?.second?.takeUnless(parentalState::isLocked)
    } else null

    val playsAerial = aerialWallpaper != null && net.connected && !aerialCoolingDown
    val playsLocalVideo = config.useVideoWallpaper && config.videoUri.isNotEmpty()
    val videoBackdropVisible = !config.useTopShelf && (playsAerial || playsLocalVideo)
    val wallpaperScrim = when (config.scrimMode) {
        4 -> null
        3 -> ScrimFull
        2 -> ScrimBottom
        1 -> ScrimTop
        else -> ScrimTopBottom
    }
    val overlayAlpha = animateFloatAsState(
        targetValue = if (dockExpanded || reorderFrom != null) 1f else 0f,
        animationSpec = tween(1000),
        label = "dockOverlay",
    )
    val backdropPhoto = wallpaperSharp.takeUnless { config.useTopShelf || videoBackdropVisible }
    val liquidGlass = resolveGlassStyle(config.glassStyle) == GLASS_STYLE_LIQUID
    val glassBackdrop = remember(backdropPhoto, presetBrush, wallpaperScrim, config.useTopShelf, videoBackdropVisible, liquidGlass) {
        LauncherBackdrop(backdropPhoto, presetBrush, wallpaperScrim, videoBackdropVisible, liquidGlass, overlayAlpha)
    }
    val videoGlassActive = liquidGlass && reorderFrom == null && LocalWindowInfo.current.isWindowFocused &&
        (config.statusBarGlass || (config.layout == LAYOUT_DOCK && !dockExpanded))

    val corner: Dp = CORNER_RADII[config.cornerRadius.coerceIn(0, CORNER_RADII.size - 1)]
    Box(Modifier.fillMaxSize()) {
    ScaledUi(uiScale) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCornerRadius provides corner,
        LocalLauncherBackdrop provides glassBackdrop,
        LocalTopShelfFocus provides reportShelfFocus.takeIf { config.useTopShelf },
    ) {
    LiteTvTheme(accent = accent) {
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (reorderFrom == null && event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                        showSettings = true
                        true
                    } else false
                }
        ) {
            // Wallpaper + readability scrim.
            // Top Shelf fades over its gradient fallback. Other wallpaper modes are exclusive.
            val videoSpeed = VIDEO_SPEEDS[config.videoSpeed.coerceIn(0, VIDEO_SPEEDS.size - 1)]
            val aerial = aerialWallpaper
            if (config.useTopShelf) {
                Box(Modifier.fillMaxSize().background(presetBrush))
                TopShelfWallpaper(shelfPackage, glassBackdrop)
            } else if (aerial != null && playsAerial) {
                VideoWallpaper(
                    uri = aerial,
                    nextUri = nextAerialWallpaper,
                    speed = videoSpeed,
                    loop = false, // play once, then shuffle to the next aerial
                    coverBrush = presetBrush,
                    glassActive = videoGlassActive,
                    useGpuGlass = liquidGlass && (config.statusBarGlass || config.layout == LAYOUT_DOCK),
                    remoteCache = true,
                    onAdvanced = {
                        aerialPosition = (aerialPosition + 1).mod(aerialPlaylist.size)
                    },
                    onEnded = {
                        if (aerialPlaylist.size > 1) {
                            aerialPosition = (aerialPosition + 1).mod(aerialPlaylist.size)
                        } else {
                            // A one-item source has no automatic transition to
                            // drive a recomposition, so explicitly reload it.
                            wallpaperVersion++
                        }
                    },
                    onReady = ::recordAerialReady,
                    onError = ::recordAerialFailure,
                )
            } else if (playsLocalVideo) {
                VideoWallpaper(
                    uri = config.videoUri,
                    speed = videoSpeed,
                    loop = true,
                    coverBrush = presetBrush,
                    glassActive = videoGlassActive,
                )
            } else if (wallpaperSharp != null) {
                Image(
                    bitmap = wallpaperSharp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(presetBrush))
            }
            // Dock-expanded overlay: cheap pseudo-blur (tiny bitmap upscaled)
            // + dim, crossfaded. Works identically on every Android version.
            val dim = overlayAlpha.value
            if (dim > 0.01f) {
                if (wallpaperBlurred != null) {
                    Image(
                        bitmap = wallpaperBlurred,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dim },
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = dim }
                        .background(Color.Black.copy(alpha = 0.45f))
                )
            }
            wallpaperScrim?.let { Box(Modifier.fillMaxSize().background(it)) }

            // Launch entrance: after the boot logo, wait a beat, then the glass
            // panels slide in (status bar from the top, dock from the bottom) and
            // the icons fade in after.
            var introShown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(450); introShown = true }
            val introTop by animateFloatAsState(
                targetValue = if (introShown) 1f else 0f,
                animationSpec = tween(340, easing = FastOutSlowInEasing),
                label = "introTop",
            )

            val reorderStart = reorderFrom
            if (reorderStart != null) {
                AppReorderScreen(sectionRows, reorderStart, config, accent, cardWidth, gap, savingReorder) { original, reordered ->
                    if (original == reordered) {
                        reorderFrom = null
                    } else if (!savingReorder) {
                        savingReorder = true
                        scope.launch {
                            try {
                                store.update { it.applyReorder(original, reordered) }
                                reorderFrom = null
                            } catch (_: java.io.IOException) {
                                Actions.toast(context, context.getString(R.string.toast_config_save_failed))
                            } finally {
                                savingReorder = false
                            }
                        }
                    }
                }
            } else Column(Modifier.fillMaxSize()) {
                // The status bar sits ABOVE the content, so Up from the top row
                // reaches it — and its height IS the content's top inset (the top
                // padding is a function of the bar's height, nothing hardcoded).
                // When hidden it collapses to an invisible 4dp strip that is still
                // focusable: Up from the top row slides it back into view.
                var statusBarFocused by remember { mutableStateOf(false) }
                val statusBarHeight by animateDpAsState(
                    targetValue = if (config.showStatusBar || statusBarFocused || updatePrompt?.available != null) 80.dp else 4.dp,
                    label = "statusBarHeight",
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(statusBarHeight)
                        .clipToBounds()
                        .onFocusChanged { statusBarFocused = it.hasFocus }
                        .focusGroup()
                        // Entrance: slide down from the top + fade in.
                        .graphicsLayer {
                            translationY = (introTop - 1f) * size.height
                            alpha = introTop
                        }
                ) {
                    StatusBar(
                        net = net,
                        time = time,
                        date = date,
                        showVpn = config.showVpnButton,
                        glass = config.statusBarGlass,
                        weather = weather,
                        weatherPlaceholder = if (needsGeoJs) R.string.weather_ip_enable
                        else if (weatherStatus == null) null
                        else if (weatherResult == null && weatherRequest != null) R.string.weather_loading_short
                        else R.string.weather_unavailable_short,
                        weatherDisplay = config.weatherDisplay,
                        onVpnClick = {
                            if (config.vpnApp.isNotEmpty()) launchApp(config.vpnApp)
                            else Actions.openVpnSettings(context)
                        },
                        onNetworkClick = { Actions.openNetworkSettings(context) },
                        onSettingsClick = { settingsStartAtWeather = false; settingsSuppressRelease = false; showSettings = true },
                        onWeatherClick = { if (weather != null) onWeatherClick() else openWeatherSettings() },
                        onWeatherLongClick = { openWeatherSettings(suppressRelease = true) },
                        onUpdateClick = updatePrompt?.takeIf { it.available != null }?.let { it::open },
                    )
                }
                when (config.layout) {
                    LAYOUT_DOCK -> DockArea(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        apps = dockApps,
                        config = config,
                        accent = accent,
                        cardWidth = cardWidth,
                        gap = gap,
                        intro = introShown,
                        expanded = dockExpanded,
                        onExpandChange = { dockExpanded = it },
                        onLaunch = { app -> launchApp(app.pkg) },
                        onMenu = { app -> openAppMenu(app) },
                    )
                    else -> {
                        // Magnetic vertical scroll: the focused row is pinned to a
                        // fixed pivot just below the status bar, so the row above
                        // slides off leaving ~15% of its card peeking (like the
                        // dock's row-1 peek) and the target row drops into place and
                        // snaps. Same peek at the bottom. The pivot leaves room for
                        // the section label + a 15%-card sliver above the focused row.
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val cardHeightPx = with(density) { (cardWidth * 9f / 16f).toPx() }
                        val peekPx = cardHeightPx * 0.15f
                        val labelPx = with(density) {
                            if (config.showCategoryNames || categorized.any { it.first.visibleIcon != null }) 34.dp.toPx() else 0f
                        }
                        val pivotPx = peekPx + labelPx
                        val vPivot = remember(pivotPx) {
                            object : androidx.compose.foundation.gestures.BringIntoViewSpec {
                                override fun calculateScrollDistance(
                                    offset: Float,
                                    size: Float,
                                    containerSize: Float,
                                ): Float = offset - pivotPx
                            }
                        }
                        // Rows crossing the top edge fade out (and back in on the way
                        // down) instead of being hard-clipped by an invisible wall.
                        // Kept to the peek sliver so the focused section's label,
                        // which sits just below it, stays fully readable.
                        val fadePx = peekPx
                        // Hoisted out of drawWithContent: rebuilt inside the draw
                        // lambda it re-allocated a Brush (+ shader) every frame.
                        val fadeBrush = remember(fadePx) {
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                                startY = 0f,
                                endY = fadePx,
                            )
                        }
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides vPivot
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Restore the last-focused card when focus returns
                                    // (e.g. coming back down from the status bar).
                                    .focusRestorer()
                                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
                                    },
                                contentPadding = PaddingValues(
                                    top = with(density) { pivotPx.toDp() },
                                    bottom = with(density) { peekPx.toDp() } + 16.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                        items(categorized.size, key = { categorized[it].first.id }) { rowIndex ->
                            val (cat, catApps) = categorized[rowIndex]
                            val isGrid = config.layout == LAYOUT_GRID
                            // One-shot launch entrance (tied to introShown, so it never
                            // replays on scroll/navigation): carousel rows slide in from
                            // alternating sides and decelerate; the grid blooms per-icon
                            // (inside GridSection). The label fades in after either.
                            val rowEnter by animateFloatAsState(
                                targetValue = if (introShown) 1f else 0f,
                                animationSpec = tween(500, easing = LinearOutSlowInEasing),
                                label = "rowEnter",
                            )
                            val labelAlpha by animateFloatAsState(
                                targetValue = if (introShown) 1f else 0f,
                                animationSpec = tween(280, delayMillis = 320),
                                label = "labelFade",
                            )
                            val slideDir = if (rowIndex % 2 == 0) 1f else -1f
                            Column(
                                modifier = if (!isGrid) Modifier.graphicsLayer {
                                    translationX = (1f - rowEnter) * slideDir * size.width
                                    alpha = rowEnter
                                } else Modifier
                            ) {
                                if (config.showCategoryNames || cat.visibleIcon != null) {
                                    SectionHeading(
                                        cat, config.showCategoryNames,
                                        Modifier.graphicsLayer { alpha = labelAlpha }
                                            .padding(start = 44.dp, bottom = 10.dp),
                                    )
                                }
                                if (isGrid) {
                                    GridSection(
                                        catApps, config, accent,
                                        cardWidth, gap, introShown,
                                        onLaunch = { app -> launchApp(app.pkg) },
                                        onMenu = { app -> openAppMenu(app, cat.id) },
                                    )
                                } else {
                                    CarouselSection(
                                        catApps, config, accent,
                                        cardWidth, gap,
                                        onLaunch = { app -> launchApp(app.pkg) },
                                        onMenu = { app -> openAppMenu(app, cat.id) },
                                    )
                                }
                            }
                        }
                            }
                        }
                    }
                }
            }

            // ----- dialogs -----
            menuFor?.let { app ->
                AppContextMenu(
                    app = app,
                    isHidden = app.pkg in config.hidden,
                    onDismiss = { menuFor = null },
                    onOpen = { menuFor = null; launchApp(app.pkg) },
                    onMove = {
                        menuFor = null
                        val section = menuSectionId ?: categorized.firstOrNull { (_, apps) ->
                            apps.any { it.pkg == app.pkg }
                        }?.first?.id
                        if (section != null) reorderFrom = AppPlacement(section, app.pkg)
                    },
                    onToggleHide = {
                        menuFor = null
                        scope.launch {
                            store.update {
                                it.copy(
                                    hidden = if (app.pkg in it.hidden) it.hidden - app.pkg
                                    else it.hidden + app.pkg
                                )
                            }
                        }
                    },
                    onCustomize = { menuFor = null; customizeFor = app },
                    onAppInfo = { menuFor = null; appInfo(app.pkg) },
                    onClose = { menuFor = null; closeApp(app.pkg) },
                    onUninstall = { menuFor = null; uninstallApp(app.pkg) },
                )
            }

            customizeFor?.let { app ->
                AppCustomizeMenu(
                    app = app,
                    onRename = { customizeFor = null; renameFor = app },
                    onPickIcon = {
                        customizeFor = null
                        iconSourceFor = app
                    },
                    onReset = {
                        customizeFor = null
                        scope.launch {
                            withContext(Dispatchers.IO) { AppRepository.clearOverrideImages(context.applicationContext, app.pkg) }
                            store.update { it.copy(labelOverrides = it.labelOverrides - app.pkg) }
                            overrideVersion++
                        }
                    },
                    lockAction = if (parentalState.enabled) {
                        {
                            customizeFor = null
                            val lock = !parentalState.isLocked(app.pkg)
                            val title = if (lock) R.string.parental_lock_app else R.string.parental_unlock_app
                            pinPrompt = PinPrompt(title, verify = { parental.lock(it, app.pkg, lock) })
                        }
                    } else null,
                    isLocked = parentalState.isLocked(app.pkg),
                    onDismiss = { customizeFor = null },
                )
            }

            // All icon sources land on the BANNER slot — this is a TV launcher,
            // banners are the primary tile art, and a bare app icon stretched
            // into a 16:9 tile looks wrong, so it's always the banner (an app's
            // own banner verbatim, or an icon composited onto the same card
            // IconPack.compositeOnCard uses for pack logos) that gets saved.
            iconSourceFor?.let { app ->
                IconSourceMenu(
                    app = app,
                    hasPacks = iconPacks.isNotEmpty(),
                    onPhoto = {
                        iconSourceFor = null
                        bannerTargetPkg = app.pkg
                        runCatching { bannerPicker.launch(arrayOf("image/*")) }
                            .onFailure { Actions.toast(context, context.getString(R.string.toast_no_picker)) }
                    },
                    onFromApp = { iconSourceFor = null; appIconPickFor = app },
                    onFromPack = { iconSourceFor = null; packIconPickFor = app },
                    onFromLibrary = { iconSourceFor = null; libraryIconPickFor = app },
                    onDismiss = { iconSourceFor = null },
                )
            }

            appIconPickFor?.let { app ->
                AppIconPickDialog(
                    apps = apps.filter { it.pkg != app.pkg },
                    onPick = { picked ->
                        appIconPickFor = null
                        val bitmap = picked.banner?.asAndroidBitmap()
                            ?: picked.icon?.asAndroidBitmap()?.let { IconPack.compositeOnCard(it) }
                        if (bitmap != null) {
                            scope.launch {
                                if (AppRepository.saveOverrideBannerBitmap(context.applicationContext, app.pkg, bitmap)) overrideVersion++
                                else Actions.toast(context, context.getString(R.string.icon_save_failed))
                            }
                        }
                    },
                    onDismiss = { appIconPickFor = null },
                )
            }

            packIconPickFor?.let { app ->
                PackIconPickDialog(
                    packs = iconPacks,
                    apps = apps,
                    onPick = { packPkg, drawableName ->
                        packIconPickFor = null
                        scope.launch {
                            val bitmap = withContext(Dispatchers.IO) {
                                IconPack.loadPackBanner(context.applicationContext, packPkg, drawableName)
                            }
                            if (bitmap != null && AppRepository.saveOverrideBannerBitmap(context.applicationContext, app.pkg, bitmap)) overrideVersion++
                            else Actions.toast(context, context.getString(R.string.icon_save_failed))
                        }
                    },
                    onDismiss = { packIconPickFor = null },
                )
            }

            libraryIconPickFor?.let { app ->
                LibraryIconPickDialog(
                    onPick = { icon ->
                        libraryIconPickFor = null
                        scope.launch {
                            val bitmap = withContext(Dispatchers.Default) {
                                val glyph = icon.render(192)
                                try { IconPack.compositeOnCard(glyph) }
                                finally { glyph.recycle() }
                            }
                            try {
                                if (AppRepository.saveOverrideBannerBitmap(context.applicationContext, app.pkg, bitmap)) overrideVersion++
                                else Actions.toast(context, context.getString(R.string.icon_save_failed))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    },
                    onDismiss = { libraryIconPickFor = null },
                )
            }

            renameFor?.let { app ->
                TextEntryDialog(
                    title = stringResource(R.string.menu_rename),
                    initial = app.label,
                    onSave = { newLabel ->
                        scope.launch {
                            store.update { it.copy(labelOverrides = it.labelOverrides + (app.pkg to newLabel.take(256))) }
                        }
                        renameFor = null
                    },
                    onDismiss = { renameFor = null },
                )
            }

            if (showSettings) {
                SettingsSheet(
                    config = config,
                    apps = displayApps,
                    store = store,
                    parental = parental,
                    parentalState = parentalState,
                    onLaunchApp = ::launchApp,
                    onAppInfo = ::appInfo,
                    onUninstallApp = ::uninstallApp,
                    onDismiss = { showSettings = false },
                    onWallpaperChanged = { wallpaperVersion++ },
                    // Runs on LauncherApp's scope, which survives the dialog
                    // teardown — otherwise the setupDone write gets cancelled.
                    onRerunWizard = {
                        showSettings = false
                        scope.launch { store.update { it.copy(setupDone = false) } }
                    },
                    updatePrompt = updatePrompt,
                    startAtWeather = settingsStartAtWeather,
                    weatherStatus = weatherStatus,
                    weatherLocationName = weatherResult?.ipLocationLabel,
                    canRetryWeather = weatherRequest != null && weatherResult != null &&
                        (weatherRequest.source != WEATHER_SRC_GEOIP ||
                            weatherResult?.failure == WeatherFailure.ForecastUnavailable),
                    onRetryWeather = { weatherRetryTick++ },
                    suppressInitialRelease = settingsSuppressRelease,
                )
            }

            closeFailure?.let { (pkg, message) ->
                MenuDialog(
                    title = stringResource(message),
                    entries = buildList {
                        if (message == R.string.adb_enable_required) {
                            add(MenuEntry(stringResource(R.string.home_open_debugging), AppIcons.Gear) {
                                closeFailure = null
                                Actions.openDebuggingSettings(context)
                            })
                        }
                        add(MenuEntry(stringResource(R.string.menu_app_info), AppIcons.Info) {
                            closeFailure = null
                            appInfo(pkg)
                        })
                        add(MenuEntry(stringResource(R.string.cancel), AppIcons.Stop) { closeFailure = null })
                    },
                    onDismiss = { closeFailure = null },
                )
            }
            pinPrompt?.let { PinDialog(it) { pinPrompt = null } }
            updatePrompt?.let { AppUpdateDialog(it) }
            if (showSetupHomePrompt) PressHomeDialog(waitForHome = true) {}

            if (showForecast) {
                weather?.let {
                    WeatherForecastDialog(
                        weather = it,
                        view = config.weatherForecastView,
                        iconStyle = config.weatherForecastIconStyle,
                        h24 = config.h24,
                        onDismiss = { showForecast = false },
                    )
                }
            }
        }
    }
    }
    }
    // Fondu: the couch boot logo fades out over the freshly-drawn launcher.
    // Wait for a real drawn frame (not just first composition) so the fade
    // begins once the heavy first frame is actually on screen — the breathing
    // logo covers that hitch instead of fading out through it.
    var booted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos {}; withFrameNanos {}; booted = true }
    androidx.compose.animation.AnimatedVisibility(
        visible = !booted,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.fadeOut(tween(500)),
        modifier = Modifier.fillMaxSize(),
    ) { LoadingScreen() }
    }
}

/* --------------------- carousel: fixed selection --------------------- */

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CarouselSection(
    catApps: List<AppEntry>,
    config: LauncherConfig,
    accent: Color,
    cardWidth: Dp,
    gap: Dp,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    val state = rememberLazyListState()
    // Pivot scrolling done RIGHT: instead of letting the default
    // bring-into-view scroll minimally and then re-pinning by hand (two
    // fighting animations = the janky feel), we replace the scroll spec so
    // the ONLY scroll that ever happens places the focused card at the
    // fixed pivot (the 44dp start padding) in one smooth motion.
    val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
    val pivotSpec = remember(padPx) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = offset - padPx
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pivotSpec
    ) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 44.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(catApps.size, key = { catApps[it].pkg }) { i ->
                val app = catApps[i]
                AppCard(
                    app = app,
                    isMoving = false,
                    isHidden = app.pkg in config.hidden,
                    accent = accent,
                    cardWidth = cardWidth,
                    showLabel = config.showAppLabels,
                    onLaunch = { onLaunch(app) },
                    onLongPress = { onMenu(app) },
                )
            }
        }
    }
}

/* ------------------- grid: everything on screen ------------------- */

/** Columns are derived from the available width, card size and spacing —
 *  a row can never overflow, so the last icon is never squeezed. */
@Composable
private fun GridSection(
    catApps: List<AppEntry>,
    config: LauncherConfig,
    accent: Color,
    cardWidth: Dp,
    gap: Dp,
    intro: Boolean,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 44.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        // fitRow only decides how MANY columns fit; the gap is then expanded so the
        // row fills the width exactly — left margin == right margin (both 44dp) for
        // any icon size / spacing, and every section lines up column-for-column.
        val (cols, _) = fitRow(maxWidth, cardWidth, gap)
        val gapUsed = if (cols > 1) (maxWidth - cardWidth * cols) / (cols - 1) else 0.dp
        val blockWidth = cardWidth * cols + gapUsed * (cols - 1)
        val rows = catApps.chunked(cols)
        // Launch entrance: icons bloom out from the section's centre — the middle
        // tiles pop first (scale + fade), outer ones stagger by distance. One-shot
        // via `intro`; sections composed later (scroll) start settled, no replay.
        val centerR = (rows.size - 1) / 2f
        val centerC = (cols - 1) / 2f
        val maxDist = kotlin.math.hypot(centerC.toDouble(), centerR.toDouble())
            .toFloat().coerceAtLeast(1f)
        Column(
            Modifier.width(blockWidth),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            rows.forEachIndexed { r, rowApps ->
                Row(horizontalArrangement = Arrangement.spacedBy(gapUsed)) {
                    rowApps.forEachIndexed { c, app ->
                        val dist = kotlin.math.hypot((c - centerC).toDouble(), (r - centerR).toDouble()).toFloat()
                        val bloom by animateFloatAsState(
                            targetValue = if (intro) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = 300,
                                delayMillis = (dist / maxDist * 240f).toInt(),
                                easing = FastOutSlowInEasing,
                            ),
                            label = "bloom",
                        )
                        AppCard(
                            app = app,
                            isMoving = false,
                            isHidden = app.pkg in config.hidden,
                            accent = accent,
                            cardWidth = cardWidth,
                            showLabel = config.showAppLabels,
                            modifier = Modifier.graphicsLayer {
                                    alpha = bloom
                                    val s = 0.8f + 0.2f * bloom
                                    scaleX = s
                                    scaleY = s
                                },
                            onLaunch = { onLaunch(app) },
                            onLongPress = { onMenu(app) },
                        )
                    }
                }
            }
        }
    }
}

/* ----- dock: floating glass panel with one row; Down = full grid over blurred bg ----- */

@Composable
private fun DockArea(
    modifier: Modifier,
    apps: List<AppEntry>,
    config: LauncherConfig,
    accent: Color,
    cardWidth: Dp,
    gap: Dp,
    intro: Boolean,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    val dockFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    // Back closes the open grid (returns to the dock); when closed it falls
    // through to the activity's no-op, so Back never exits the home screen.
    androidx.activity.compose.BackHandler(enabled = expanded) { onExpandChange(false) }
    // Which dock column the user pressed Down from, so the grid opens focused on
    // that column of the 2nd row — pressing Down moves down, not back to icon #1.
    var expandFromCol by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, intro) {
        if (!intro) return@LaunchedEffect // wait for the launch entrance
        // Claim focus over the first few frames (not after a delay): otherwise the
        // focus, briefly orphaned when the old row's node leaves, snaps to the first
        // focusable — the status-bar VPN icon — and flashes selected before we grab it.
        val target = if (expanded) gridFocus else dockFocus
        repeat(4) {
            withFrameNanos {}
            runCatching { target.requestFocus() }
        }
    }

    // Size + spacing define how many icons the dock holds; the SAME count
    // becomes the grid's columns when unfolded, and both states are centered
    // with the same column math — so icons never shift horizontally.
    BoxWithConstraints(modifier) {
        val panelInnerPadding = 18.dp
        val available = maxWidth - 80.dp - panelInnerPadding * 2
        val (cols, gapUsed) = fitRow(available, cardWidth, gap)
        val cardHeight = cardWidth * 9f / 16f
        val rows = apps.chunked(cols)
        // The grid opens focused on row 0, same column as the dock icon we came
        // from (row 0 is those same icons) — focus stays put, it doesn't jump down.
        val targetRow = 0
        val targetCol = expandFromCol.coerceIn(0, (rows.getOrNull(targetRow)?.size ?: 1) - 1)
        // Fixed block width shared by every row: complete rows are centered,
        // and a partial LAST row stays left-aligned with the columns above.
        val blockWidth = cardWidth * cols + gapUsed * (cols - 1)
        val peekH = cardHeight * 0.22f
        // Distance between the grid's row-0 spot (top, 24dp pad) and the dock's
        // row-0 spot (bottom): the grid slides straight out of / back into the
        // dock icons, so the same icons read as one set rising and sinking.
        val dockRisePx = with(androidx.compose.ui.platform.LocalDensity.current) {
            (maxHeight - peekH - cardHeight - 52.dp).toPx()
        }.toInt().coerceAtLeast(0)
        // Row spacing morphs from a wide "dock" gap down to the tight grid gap as
        // it opens (rows start far apart, like the dock, then draw together), and
        // back out on close.
        val rowGap by animateDpAsState(
            targetValue = if (expanded) gap else gap + cardHeight * 0.35f,
            animationSpec = tween(340, easing = FastOutSlowInEasing),
            label = "rowGap",
        )
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f))
            // Glass panel + row-0 icons, DISSOCIATED so they animate apart: the
            // frosted panel slides DOWN and fades out; the icons fade and drift
            // UP (toward the grid), while the grid rises them into position.
            Box(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                DockGlassPanel(
                    visible = intro && !expanded,
                    rowApps = rows.firstOrNull().orEmpty(),
                    config = config,
                    accent = accent,
                    dockFocus = dockFocus,
                    cardWidth = cardWidth,
                    gapUsed = gapUsed,
                    innerPadding = panelInnerPadding,
                    onExpandFrom = { i -> expandFromCol = i; onExpandChange(true) },
                    onLaunch = onLaunch,
                    onMenu = onMenu,
                )
            }
            // Teaser: the tops of the 2nd row's cards peeking at the bottom edge.
            // Fades in in SYNC with the dock icons (same delay), so the peek never
            // appears before row 0 on collapse.
            AnimatedVisibility(
                visible = intro && !expanded && rows.size > 1,
                enter = fadeIn(tween(200, delayMillis = 220)),
                exit = fadeOut(tween(200)),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gapUsed)) {
                        rows.getOrNull(1).orEmpty().forEach { app ->
                            PeekStrip(app, cardWidth, cardHeight, peekH)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            // The grid slides straight out of the dock (row 0 aligned with the
            // dock icons) and sinks back into it — one continuous set of icons,
            // ease-in-out (S-curve). Fades in fast so it covers the dock row
            // cleanly instead of double-exposing.
            enter = fadeIn(tween(150)) +
                slideInVertically(tween(340, easing = FastOutSlowInEasing)) { dockRisePx },
            // Closing: sink back into the dock (S-curve) and only fade out at the
            // very end, so the grid stays solid the whole way down.
            exit = fadeOut(tween(120, delayMillis = 240)) +
                slideOutVertically(tween(340, easing = FastOutSlowInEasing)) { dockRisePx },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(rowGap),
            ) {
                items(rows.size, key = { rows.getOrNull(it)?.firstOrNull()?.pkg ?: "row$it" }) { r ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.width(blockWidth)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(gapUsed)) {
                                rows[r].forEachIndexed { c, app ->
                                    AppCard(
                                        app = app,
                                        isMoving = false,
                                        isHidden = app.pkg in config.hidden,
                                        accent = accent,
                                        cardWidth = cardWidth,
                                        showLabel = config.showAppLabels,
                                        // At the top boundary, Up mirrors Back: the
                                        // full library sinks into the dock instead of
                                        // leaving focus stranded in the open grid.
                                        modifier = (when {
                                            r == targetRow && c == targetCol -> Modifier.focusRequester(gridFocus)
                                            else -> Modifier
                                        }).onPreviewKeyEvent { event ->
                                            if (
                                                r == 0 &&
                                                event.type == KeyEventType.KeyDown &&
                                                event.key == Key.DirectionUp
                                            ) {
                                                onExpandChange(false)
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                        onLaunch = { onLaunch(app) },
                                        onLongPress = { onMenu(app) },
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

/**
 * The dock's glass panel with its row-0 icons — DISSOCIATED so they
 * animate independently on expand: the glass background slides DOWN and fades
 * out fast, while the icons fade and drift UP as the grid rises them into place.
 * Kept in its own (non-Column) composable so AnimatedVisibility resolves to the
 * plain overload, and so LocalCornerRadius updates the corners live.
 */
@Composable
private fun DockGlassPanel(
    visible: Boolean,
    rowApps: List<AppEntry>,
    config: LauncherConfig,
    accent: Color,
    dockFocus: FocusRequester,
    cardWidth: Dp,
    gapUsed: Dp,
    innerPadding: Dp,
    onExpandFrom: (Int) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        // Glass background — its exit is the exact mirror of its enter: rises
        // up + fades in on close, sinks down + fades out on open (same 300ms fade
        // / 500ms S-curve slide, just reversed).
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(300)) +
                slideInVertically(tween(340, easing = FastOutSlowInEasing)) { it },
            exit = fadeOut(tween(300)) +
                slideOutVertically(tween(340, easing = FastOutSlowInEasing)) { it },
            modifier = Modifier.matchParentSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .launcherGlass(LocalCornerRadius.current + 14.dp, GlassPanel.Dock)
            )
        }
        // Icons — on OPEN they vanish almost instantly (the rising grid takes
        // over); on CLOSE they wait for the grid to sink back before fading in.
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200, delayMillis = 220)),
            exit = fadeOut(tween(90)),
        ) {
            Row(
                Modifier.padding(innerPadding),
                horizontalArrangement = Arrangement.spacedBy(gapUsed),
            ) {
                rowApps.forEachIndexed { i, app ->
                    AppCard(
                        app = app,
                        isMoving = false,
                        isHidden = app.pkg in config.hidden,
                        accent = accent,
                        cardWidth = cardWidth,
                        showLabel = config.showAppLabels,
                        modifier = (when {
                            i == 0 -> Modifier.focusRequester(dockFocus)
                            else -> Modifier
                        }).onPreviewKeyEvent { e ->
                            if (
                                e.type == KeyEventType.KeyDown &&
                                e.key == Key.DirectionDown
                            ) { onExpandFrom(i); true } else false
                        },
                        onLaunch = { onLaunch(app) },
                        onLongPress = { onMenu(app) },
                    )
                }
            }
        }
    }
}

/**
 * One second-row peek strip: the top slice of a card. It's its OWN composable
 * (not inline) so reading LocalCornerRadius here gives it an independent
 * recomposition scope — roundness changes update it live, exactly like AppCard.
 */
@Composable
private fun PeekStrip(app: AppEntry, cardWidth: Dp, cardHeight: Dp, peekH: Dp) {
    val corner = LocalCornerRadius.current
    Box(
        Modifier
            .width(cardWidth)
            .height(peekH)
            .clip(RoundedCornerShape(topStart = corner, topEnd = corner))
            .background(app.tile),
    ) {
        if (app.banner != null) {
            Image(
                bitmap = app.banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                // full card height, parent clips to peekH → only the top slice shows.
                modifier = Modifier.width(cardWidth).height(cardHeight),
            )
        }
    }
}

/** Process-wide, certificate- and hostname-validating client for public aerial video. */
private val aerialHttpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/**
 * Live video wallpaper behind the launcher, muted, aspect-filled, via ExoPlayer.
 * [loop] true replays the same clip (a user's own single video); false plays it
 * once then calls [onEnded] so aerials shuffle to the next. Releases the decoder
 * while the launcher is hidden and rebuilds it on return.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoWallpaper(
    uri: String,
    nextUri: String? = null,
    speed: Float = 1f,
    loop: Boolean = true,
    coverBrush: Brush,
    glassActive: Boolean,
    useGpuGlass: Boolean = false,
    remoteCache: Boolean = false,
    onEnded: () -> Unit = {},
    onAdvanced: () -> Unit = {},
    onReady: (Long) -> Unit = {},
    onError: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val transitionVeil = remember { androidx.compose.animation.core.Animatable(0f) }
    val playerScope = rememberCoroutineScope()
    var firstFrameGen by remember { mutableIntStateOf(0) }

    // The ExoPlayer — and its hardware video decoder, surface and buffers — is
    // kept while Home is still visible (STARTED), including system overlays that
    // only PAUSE it, and released once a full-screen app genuinely
    // hides Home (STOPPED).
    var player by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    var prepareStartedAt by remember(player) { mutableStateOf(0L) }
    var awaitingFrameAt by remember(player) { mutableStateOf<Int?>(null) }
    val latestOnReady by androidx.compose.runtime.rememberUpdatedState(onReady)
    val latestOnError by androidx.compose.runtime.rememberUpdatedState(onError)
    val latestOnEnded by androidx.compose.runtime.rememberUpdatedState(onEnded)
    val latestOnAdvanced by androidx.compose.runtime.rememberUpdatedState(onAdvanced)
    val latestNextUri by androidx.compose.runtime.rememberUpdatedState(nextUri)
    DisposableEffect(lifecycleOwner, remoteCache) {
        fun build(): androidx.media3.exoplayer.ExoPlayer {
            val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true) // fall back to SW decoder on odd clips
            // A failed certificate check falls back to the gradient; never bypass TLS validation.
            val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(aerialHttpClient)
            val dataSource: androidx.media3.datasource.DataSource.Factory =
                if (remoteCache) AerialVideoCache.dataSourceFactory(http)
                else androidx.media3.datasource.DefaultDataSource.Factory(context, http)
            val sourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource)
            if (remoteCache) sourceFactory.setLoadErrorHandlingPolicy(AerialLoadErrorPolicy)
            return androidx.media3.exoplayer.ExoPlayer.Builder(context)
                .setRenderersFactory(renderers)
                .setMediaSourceFactory(sourceFactory)
                // A muted, non-interactive wallpaper never seeks, so the default
                // 50s buffer just pins ~25-30MB of RAM. Trim it — but keep >=15s
                // max so a slow TV network doesn't rebuffer visibly on the loop.
                .setLoadControl(
                    androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(10_000, 15_000, 2_500, 5_000)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build()
                )
                .build().apply {
                    repeatMode = if (loop) androidx.media3.common.Player.REPEAT_MODE_ONE
                        else androidx.media3.common.Player.REPEAT_MODE_OFF
                    volume = 0f
                    videoScalingMode =
                        androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    playWhenReady = true
                    setPlaybackSpeed(speed)
                }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START ->
                    if (player == null) player = build()
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    player?.let { runCatching { it.release() } }
                    player = null
                }
                else -> {}
            }
        }
        // Already visible when we enter composition (first launch, config
        // change) → build now instead of waiting for the next ON_START.
        if (player == null &&
            lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        ) {
            player = build()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.let { runCatching { it.release() } }
            player = null
        }
    }

    val activePlayer = player
    // On release the cover is snapped back opaque so the NEXT visible state shows
    // the gradient (not a black hole) until the rebuilt player renders its frame.
    LaunchedEffect(activePlayer == null) { if (activePlayer == null) alpha.snapTo(0f) }
    LaunchedEffect(activePlayer, speed) { activePlayer?.setPlaybackSpeed(speed) }

    DisposableEffect(activePlayer) {
        val p = activePlayer ?: return@DisposableEffect onDispose { }
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameGen++
                awaitingFrameAt = null
                latestOnReady(
                    if (prepareStartedAt == 0L) 0L
                    else (android.os.SystemClock.elapsedRealtime() - prepareStartedAt).coerceAtLeast(0L)
                )
                playerScope.launch {
                    transitionVeil.animateTo(0f, tween(650, easing = FastOutSlowInEasing))
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                // Fallback for a one-item queue or an exhausted source list.
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    latestOnEnded()
                    if (latestNextUri == null) {
                        p.seekToDefaultPosition()
                        p.prepare()
                        p.playWhenReady = true
                    }
                }
            }
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                if (reason != androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                prepareStartedAt = android.os.SystemClock.elapsedRealtime()
                awaitingFrameAt = firstFrameGen
                latestOnAdvanced()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val activeUri = p.currentMediaItem?.localConfiguration?.uri ?: uri
                android.util.Log.w("LiteTV", "wallpaper play error ${error.errorCodeName}: $activeUri")
                latestOnError()
            }
        }
        p.addListener(listener)
        onDispose { p.removeListener(listener) }
    }
    // Load (or reload, after a rebuild on return) the current clip. Keyed on the
    // player too, so coming back from the background re-prepares on the fresh one.
    LaunchedEffect(activePlayer, uri, nextUri) {
        val p = activePlayer ?: return@LaunchedEffect
        val playingUri = p.currentMediaItem?.localConfiguration?.uri?.toString()
        if (playingUri == uri && p.mediaItemCount > 0 && p.playerError == null) {
            // The queued item is already playing. Keep its decoded frame, discard
            // playlist history, and append exactly one fresh successor.
            val currentIndex = p.currentMediaItemIndex
            if (currentIndex > 0) p.removeMediaItems(0, currentIndex)
            if (p.mediaItemCount > 1) p.removeMediaItems(1, p.mediaItemCount)
            nextUri?.let { p.addMediaItem(androidx.media3.common.MediaItem.fromUri(it)) }
            return@LaunchedEffect
        }
        // First load or a genuine source change: cover until a frame is decoded.
        if (!remoteCache && alpha.value > 0f) alpha.animateTo(0f, tween(450))
        awaitingFrameAt = firstFrameGen
        runCatching {
            val queue = buildList {
                add(androidx.media3.common.MediaItem.fromUri(uri))
                nextUri?.let { add(androidx.media3.common.MediaItem.fromUri(it)) }
            }
            p.setMediaItems(queue)
            prepareStartedAt = android.os.SystemClock.elapsedRealtime()
            p.prepare()
        }
    }
    LaunchedEffect(activePlayer, awaitingFrameAt) {
        val expected = awaitingFrameAt ?: return@LaunchedEffect
        val p = activePlayer ?: return@LaunchedEffect
        // A restore may prepare while Home is behind the document picker. Start
        // the first-frame timeout only once Home can render, and rearm on resume.
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            if (firstFrameGen != expected) return@repeatOnLifecycle
            delay(20_000L)
            if (firstFrameGen == expected && !p.isPlaying) {
                android.util.Log.w("LiteTV", "wallpaper stalled (no frame in 20s), skipping: $uri")
                latestOnError()
            }
        }
    }
    // The successor is already queued. Near the cut, pass through a shallow
    // black veil and reveal the next decoded frame; the static preset never
    // appears between two playable aerials.
    LaunchedEffect(activePlayer, nextUri, loop) {
        val p = activePlayer ?: return@LaunchedEffect
        if (loop || nextUri == null) return@LaunchedEffect
        while (true) {
            val duration = p.duration
            val remaining = ((duration - p.currentPosition) / p.playbackParameters.speed).toLong()
            if (duration > 0L && remaining in 1L..1_150L && transitionVeil.value < 0.05f) {
                transitionVeil.animateTo(0.82f, tween(850, easing = FastOutSlowInEasing))
            }
            delay(180L)
        }
    }
    // The new clip's first frame is on screen → fade it in slowly.
    LaunchedEffect(firstFrameGen) {
        if (firstFrameGen > 0) alpha.animateTo(1f, tween(1100))
    }
    Box(Modifier.fillMaxSize()) {
        VideoGlassSurface(
            player = activePlayer,
            useFilter = useGpuGlass,
            filterActive = glassActive && firstFrameGen > 0,
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = 1f - alpha.value }
                .background(coverBrush)
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = transitionVeil.value }
                .background(Color.Black)
        )
    }
}

/** Sober startup screen: dark backdrop + the launcher mark, nothing else. */
@Composable
private fun LoadingScreen() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF101216)),
        contentAlignment = Alignment.Center,
    ) {
        val transition = rememberInfiniteTransition(label = "load")
        val scale by transition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                tween(950, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        Image(
            painter = androidx.compose.ui.res.painterResource(com.conreo.couchytv.R.drawable.brand_loading),
            contentDescription = null,
            modifier = Modifier
                .size(200.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

/** Decodes a bitmap with an inSampleSize so its width stays near [maxWidth]. */
private fun decodeDownsampled(file: File, maxWidth: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}
