package com.conreo.couchytv.data

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.conreo.couchytv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.compose.runtime.Immutable
import java.io.File

@Serializable
data class CategoryCfg(
    val id: String,
    val name: String,
    val iconName: String? = null,
    val iconEnabled: Boolean = false,
) {
    val visibleIcon: String? get() = iconName.takeIf { iconEnabled }
}

@Immutable
@Serializable
data class LauncherConfig(
    val categories: List<CategoryCfg> = listOf(
        CategoryCfg("streaming", "Streaming"),
        CategoryCfg("games", "Games"),
        CategoryCfg("music", "Music"),
        CategoryCfg("apps", "Apps"),
    ),
    /** package -> section ids the user assigned (an app may be in several) */
    val sections: Map<String, Set<String>> = emptyMap(),
    /** category id -> explicit package order */
    val order: Map<String, List<String>> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    /** packages seen on the last scan — new installs are auto-added to the first section */
    val knownApps: Set<String> = emptySet(),
    val wallpaper: Int = 1, // Aurora
    val useCustomWallpaper: Boolean = false,
    val useTopShelf: Boolean = false,
    /** video wallpaper: persisted content-URI of a local video, looped muted */
    val videoUri: String = "",
    val useVideoWallpaper: Boolean = false,
    /** built-in aerials source */
    val useBuiltinAerials: Boolean = false,
    /** which built-in collection (index into BuiltinAerials.SOURCES; 0 = all) */
    val builtinSource: Int = 0,
    val aerialQuality: Int = BuiltinAerials.QUALITY_1080P,
    /** video wallpaper playback speed: index into VIDEO_SPEEDS */
    val videoSpeed: Int = 3, // 1x
    val accent: Int = 0,
    val h24: Boolean = true,
    val showHidden: Boolean = false,
    val setupDone: Boolean = false,
    val recommendedAppsEnabled: Boolean = false,
    val recommendedAppsUrl: String = "",
    // ----- status bar -----
    val showStatusBar: Boolean = true,
    /** package of the app the VPN icon opens; empty = system VPN settings */
    val vpnApp: String = "",
    val showVpnButton: Boolean = true,
    /** wrap the status-bar icons in the same glass panel as dock mode */
    val statusBarGlass: Boolean = true,
    /** index into DATE_FORMATS; 0 = no date shown */
    val dateFormat: Int = 0,
    // ----- display options -----
    /** wallpaper dimming: 0 = top & bottom, 1 = top, 2 = bottom, 3 = full, 4 = off */
    val scrimMode: Int = 1, // Top
    val showCategoryNames: Boolean = true,
    val showAppLabels: Boolean = true,
    /** spacing step 0..4 (see GAP_SIZES) */
    val spacing: Int = 1, // Small
    /** icon size step 0..4 (see ICON_SIZES) */
    val iconScale: Int = 1, // Small
    /** icon/panel corner roundness step 0..4 (see CORNER_RADII) */
    val cornerRadius: Int = 3, // Large
    /** UI scale: 0 = Auto (compact high-DPI TVs), 1..5 = fixed (see UI_SCALES) */
    val uiScale: Int = 0, // Auto
    /** language code (e.g. "fr", "de"); empty = system default */
    val language: String = "",
    /** 0 = carousel (fixed selection), 1 = grid, 2 = dock */
    val layout: Int = 2, // Dock
    // ----- weather -----
    val weatherEnabled: Boolean = false,
    // Forecasts use IP-based location only when the user opts into that source.
    /** 0 = GeoIP, 1 = manual lat/long, 2 = zip/postal code */
    val weatherLocationSource: Int = 1,
    val weatherGeoJsEnabled: Boolean = false,
    val weatherLat: Double = 0.0,
    val weatherLon: Double = 0.0,
    val weatherCityLabel: String = "",
    val weatherZip: String = "",
    /** 2-letter country code for zip lookup */
    val weatherCountry: String = "",
    /** 0 = Celsius, 1 = Fahrenheit */
    val weatherUnit: Int = 0,
    /** 0 = icon only, 1 = icon + temperature, 2 = icon + low/high */
    val weatherDisplay: Int = 1,
    /** 0 = open Settings, 1 = forecast dialog, 2 = open weatherApp */
    val weatherClickAction: Int = WEATHER_CLICK_FORECAST,
    val weatherForecastView: Int = WEATHER_FORECAST_BOTH,
    val weatherForecastIconStyle: Int = WEATHER_ICONS_MONOCHROME,
    /** package opened when weatherClickAction = 2 (open app) */
    val weatherApp: String = "",
    // ----- app reskinning -----
    /** package -> custom label */
    val labelOverrides: Map<String, String> = emptyMap(),
    /** package of the selected icon-pack app; empty = none */
    val iconPackPkg: String = "",
)

// Keep serialization defaults stable so saved settings and legacy backups decode as before.
internal fun newInstallDefaults() = LauncherConfig(
    useBuiltinAerials = true,
    h24 = false,
    showVpnButton = false,
    dateFormat = 2,
    weatherForecastIconStyle = WEATHER_ICONS_ANIMATED,
)

internal fun LauncherConfig.reconcileInstalledApps(installed: Set<String>): LauncherConfig {
    if (knownApps.isEmpty()) return copy(knownApps = installed)
    if (knownApps == installed) return this
    // Backups retain placement for apps that have not been installed on this TV yet.
    val ordered = order.values.flatten().toSet()
    val unplaced = installed - knownApps - sections.keys - ordered
    val firstSection = categories.first().id
    return copy(
        knownApps = installed,
        sections = sections + unplaced.associateWith { setOf(firstSection) },
    )
}

const val LAYOUT_CAROUSEL = 0
const val LAYOUT_GRID = 1
const val LAYOUT_DOCK = 2

/** Status bar date formats (SimpleDateFormat patterns); index 0 = off. */
val DATE_FORMATS = listOf("", "EEE d", "EEE d MMM", "d MMM yyyy", "dd/MM", "MM/dd", "yyyy-MM-dd")

const val WEATHER_SRC_GEOIP = 0
const val WEATHER_SRC_LATLONG = 1
const val WEATHER_SRC_ZIP = 2
const val WEATHER_DISPLAY_ICON = 0
const val WEATHER_DISPLAY_TEMP = 1
const val WEATHER_DISPLAY_LOWHIGH = 2
const val WEATHER_CLICK_SETTINGS = 0
const val WEATHER_FORECAST_BOTH = 0
const val WEATHER_FORECAST_HOURLY = 1
const val WEATHER_FORECAST_DAILY = 2
const val WEATHER_ICONS_MONOCHROME = 0
const val WEATHER_ICONS_COLOR = 1
const val WEATHER_ICONS_ANIMATED = 2
const val WEATHER_CLICK_FORECAST = 1
const val WEATHER_CLICK_APP = 2

/** Representative flags for the language picker. The system option has no fixed country. */
internal val LANGUAGE_REGIONS = linkedMapOf(
    "" to "",
    "en" to "US",
    "zh" to "CN", "es" to "ES", "ja" to "JP", "de" to "DE", "fr" to "FR",
    "pt" to "BR", "ru" to "RU", "ko" to "KR", "ar" to "SA", "it" to "IT",
    "tr" to "TR", "pl" to "PL", "nl" to "NL", "hi" to "IN", "th" to "TH",
    "in" to "ID", "vi" to "VN", "he" to "IL",
)
val LANGUAGES = LANGUAGE_REGIONS.keys.toList()

private val Context.dataStore by preferencesDataStore(name = "launcher")
private val KEY_CONFIG = stringPreferencesKey("config")
private val KEY_INITIAL_WEATHER_UNIT_PENDING = booleanPreferencesKey("initial_weather_unit_pending")
private val KEY_INITIAL_WEATHER_LOCATION_PENDING = booleanPreferencesKey("initial_weather_location_pending")
private val KEY_RESTORE_REVISION = longPreferencesKey("restore_revision")

internal data class StoredConfig(
    val config: LauncherConfig,
    val restoreRevision: Long,
    val useInitialIpLocation: Boolean,
)
private val json = Json { ignoreUnknownKeys = true }

class ConfigStore(private val context: Context) {

    internal val storedConfig: Flow<StoredConfig> = context.dataStore.data.map { prefs ->
        StoredConfig(
            decodeConfig(prefs[KEY_CONFIG]),
            prefs[KEY_RESTORE_REVISION] ?: 0L,
            prefs[KEY_INITIAL_WEATHER_LOCATION_PENDING] == true,
        )
    }

    val flow: Flow<LauncherConfig> = storedConfig.map { it.config }

    private fun decodeConfig(saved: String?): LauncherConfig = saved?.let {
        runCatching { json.decodeFromString<LauncherConfig>(it) }.getOrNull()
    } ?: newInstallDefaults().copy(categories = defaultCategories(context))

    /** Record initial defaults without waiting for the network or changing an existing installation. */
    internal suspend fun prepareFirstLaunch(): Boolean {
        val existing = context.dataStore.data.first()
        if (existing[KEY_CONFIG] != null) return existing[KEY_INITIAL_WEATHER_UNIT_PENDING] == true
        val prepared = context.dataStore.edit { prefs ->
            if (prefs[KEY_CONFIG] == null) {
                prefs[KEY_CONFIG] = json.encodeToString(decodeConfig(null))
                prefs[KEY_INITIAL_WEATHER_UNIT_PENDING] = true
                prefs[KEY_INITIAL_WEATHER_LOCATION_PENDING] = true
            }
        }
        return prepared[KEY_INITIAL_WEATHER_UNIT_PENDING] == true
    }

    internal suspend fun finishInitialWeatherUnit() {
        val unit = withContext(Dispatchers.IO) { WeatherRepository.initialWeatherUnit() }
        context.dataStore.edit { prefs ->
            if (prefs.remove(KEY_INITIAL_WEATHER_UNIT_PENDING) == true) {
                val current = decodeConfig(prefs[KEY_CONFIG])
                prefs[KEY_CONFIG] = json.encodeToString(current.copy(weatherUnit = unit))
            }
        }
    }

    private fun defaultCategories(ctx: Context) = listOf(
        CategoryCfg("streaming", ctx.getString(R.string.cat_streaming)),
        CategoryCfg("games", ctx.getString(R.string.cat_games)),
        CategoryCfg("music", ctx.getString(R.string.cat_music)),
        CategoryCfg("apps", ctx.getString(R.string.cat_apps)),
    )

    suspend fun update(transform: (LauncherConfig) -> LauncherConfig) {
        context.dataStore.edit { prefs ->
            val current = decodeConfig(prefs[KEY_CONFIG])
            val updated = transform(current)
            if (updated.weatherUnit != current.weatherUnit) prefs.remove(KEY_INITIAL_WEATHER_UNIT_PENDING)
            prefs[KEY_CONFIG] = json.encodeToString(updated)
        }
    }

    internal suspend fun saveSetupWeather(location: WeatherLocation?) {
        context.dataStore.edit { prefs ->
            val current = decodeConfig(prefs[KEY_CONFIG])
            val updated = if (location == null) current.copy(weatherEnabled = false)
                else current.withWeatherLocation(location).copy(weatherEnabled = true)
            prefs[KEY_CONFIG] = json.encodeToString(updated)
            prefs.remove(KEY_INITIAL_WEATHER_LOCATION_PENDING)
        }
    }

    suspend fun snapshot(): LauncherConfig = flow.first()

    /** Apply portable settings atomically while keeping destination scan state. */
    suspend fun restore(loaded: LauncherConfig) {
        context.dataStore.edit { prefs ->
            val current = decodeConfig(prefs[KEY_CONFIG])
            prefs[KEY_CONFIG] = json.encodeToString(loaded.copy(knownApps = current.knownApps, setupDone = true))
            prefs.remove(KEY_INITIAL_WEATHER_UNIT_PENDING)
            prefs.remove(KEY_INITIAL_WEATHER_LOCATION_PENDING)
            // A restore must refresh runtime state even when the settings are identical.
            prefs[KEY_RESTORE_REVISION] = (prefs[KEY_RESTORE_REVISION] ?: 0L) + 1L
        }
    }

    /** Same JSON the settings Save locally button writes. */
    suspend fun exportTo(file: File) {
        val cfg = flow.first()
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val bytes = ConfigBackup.encode(cfg).toByteArray(Charsets.UTF_8)
            val target = android.util.AtomicFile(file)
            val output = target.startWrite()
            try { output.write(bytes); target.finishWrite(output) }
            catch (error: Exception) { target.failWrite(output); throw error }
            check(target.openRead().use { it.readBytes().contentEquals(bytes) })
        }
    }

    /**
     * Replace stored settings from a backup. Keeps this device's [LauncherConfig.knownApps]
     * and marks setup done, matching the in-app Load path.
     */
    suspend fun importFrom(file: File): Boolean {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use(ConfigBackup::read)
            }.getOrNull()
        } ?: return false
        restore(loaded)
        return true
    }

    companion object {
        const val LOCAL_FILE = "CouchyBackup.json"

        fun defaultLocalFile(): File {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return File(dir, LOCAL_FILE)
        }
    }
}
