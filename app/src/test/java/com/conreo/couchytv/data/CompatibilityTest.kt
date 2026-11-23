package com.conreo.couchytv.data

import org.junit.Assert.*
import org.junit.Test

class CompatibilityTest {
    @Test fun originalBackupsGetSafeDefaultsForNewFeatures() {
        val old = """{"categories":[{"id":"apps","name":"Apps"}],"layout":2,"setupDone":true,"futureOption":true}"""
        val config = ConfigBackup.decode(old)
        assertFalse(config.weatherEnabled); assertTrue(config.labelOverrides.isEmpty()); assertEquals("", config.iconPackPkg)
        assertTrue(config.setupDone); assertEquals(2, config.layout)
    }
    @Test fun portableSettingsRoundTripThroughTheTelegramDocumentCodec() {
        val config = LauncherConfig(
            categories = listOf(CategoryCfg("home", "Home"), CategoryCfg("video", "Video")),
            sections = mapOf("test.app" to setOf("home", "video")),
            order = mapOf("video" to listOf("test.other", "test.app")),
            hidden = setOf("test.hidden"),
            knownApps = setOf("test.app"),
            wallpaper = 2, useCustomWallpaper = true, videoUri = "content://test/video",
            useVideoWallpaper = true, useBuiltinAerials = true, builtinSource = 1,
            videoSpeed = 2, accent = 1, h24 = false, showHidden = true, setupDone = true,
            showStatusBar = false, vpnApp = "test.vpn", showVpnButton = false,
            statusBarGlass = false, dateFormat = 2, scrimMode = 2, showCategoryNames = false,
            showAppLabels = false, spacing = 3, iconScale = 2, cornerRadius = 1,
            uiScale = 3, language = "fr", layout = LAYOUT_GRID,
            weatherEnabled = true, weatherLocationSource = WEATHER_SRC_ZIP, weatherGeoJsEnabled = true, weatherLat = 40.7,
            weatherLon = -74.0, weatherCityLabel = "New York, United States", weatherZip = "07030", weatherCountry = "US", weatherUnit = 1,
            weatherDisplay = WEATHER_DISPLAY_LOWHIGH, weatherClickAction = WEATHER_CLICK_APP,
            weatherForecastView = WEATHER_FORECAST_DAILY, weatherForecastIconStyle = WEATHER_ICONS_ANIMATED,
            weatherApp = "test.weather", labelOverrides = mapOf("test.app" to "My TV"),
            iconPackPkg = "test.pack",
        )
        assertEquals(config, ConfigBackup.read(ConfigBackup.encode(config).byteInputStream()))
    }

    @Test fun installingAppsAfterRestoreKeepsTheirBackedUpPlacement() {
        val cfg = LauncherConfig(
            knownApps = setOf("test.existing"),
            sections = mapOf("test.assigned" to setOf("games", "music")),
            order = mapOf("apps" to listOf("test.ordered")),
        )
        val scanned = cfg.reconcileInstalledApps(
            cfg.knownApps + setOf("test.assigned", "test.ordered", "test.new"),
        )
        assertEquals(setOf("games", "music"), scanned.sections["test.assigned"])
        assertEquals(cfg.order, scanned.order)
        assertFalse("test.ordered" in scanned.sections)
        assertEquals(setOf("streaming"), scanned.sections["test.new"])
    }

    @Test fun badBackupsAreRejectedBeforeReplacingSettings() {
        for (bad in listOf("{}", "not json", """{"categories":[]}""",
            """{"categories":[{"id":"a","name":"One"},{"id":"a","name":"Two"}]}""",
            "[".repeat(20) + "]".repeat(20))) {
            assertTrue(bad, runCatching { ConfigBackup.decode(bad) }.isFailure)
        }
    }
    @Test fun oversizedStreamsAreRejected() {
        assertTrue(runCatching { ConfigBackup.read(ByteArray(ConfigBackup.MAX_BYTES + 1) { 32 }.inputStream()) }.isFailure)
    }
    @Test fun panoramicImagesAreSampledBeforeDecode() {
        assertEquals(32, ImageSizing.sampleSize(40000, 1000, 720))
        assertEquals(720 to 18, ImageSizing.fit(40000, 1000, 720))
        assertEquals(18 to 720, ImageSizing.fit(1000, 40000, 720))
        assertEquals(32 to 32, ImageSizing.fit(32, 32, 720))
        assertTrue(runCatching { ImageSizing.sampleSize(-1, -1, 720) }.isFailure)
    }
    @Test fun cacheBudgetAdaptsToMemoryAndFreeSpace() {
        val mib = 1024L * 1024L
        assertEquals(0L, AerialCachePolicy.budgetBytes(false, 511 * mib))
        assertEquals(64 * mib, AerialCachePolicy.budgetBytes(false, 512 * mib))
        assertEquals(128 * mib, AerialCachePolicy.budgetBytes(true, 4096 * mib))
        assertEquals(256 * mib, AerialCachePolicy.budgetBytes(false, 4096 * mib))
    }
}
