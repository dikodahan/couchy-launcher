package com.conreo.couchytv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conreo.couchytv.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CompatibilityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun invalidImportPreservesConfigAndValidImportPreservesKnownApps() = runBlocking {
        val store = ConfigStore(context)
        val original = store.flow.first()
        val file = File(context.cacheDir, "compatibility-test.json")
        try {
            store.update { it.copy(knownApps = setOf("test.seen"), weatherEnabled = true,
                weatherUnit = 1, labelOverrides = mapOf("test.app" to "My app"),
                recommendedAppsEnabled = true, recommendedAppsUrl = "https://example.org/catalog.json") }
            store.exportTo(file)
            val exported = store.flow.first()
            file.writeText("{}")
            assertFalse(store.importFrom(file)); assertEquals(exported, store.flow.first())
            store.exportTo(file)
            store.update { it.copy(knownApps = setOf("test.new"), weatherUnit = 0,
                recommendedAppsEnabled = false, recommendedAppsUrl = "") }
            assertTrue(store.importFrom(file))
            val imported = store.flow.first()
            assertEquals(1, imported.weatherUnit)
            assertEquals(setOf("test.new"), imported.knownApps)
            assertEquals("My app", imported.labelOverrides["test.app"])
            assertTrue(imported.recommendedAppsEnabled)
            assertEquals("https://example.org/catalog.json", imported.recommendedAppsUrl)
        } finally { store.update { original }; file.delete() }
    }

    @Test fun panoramaImportIsBoundedAndResetRestoresOriginalArt() = runBlocking {
        val pkg = "com.example.couchy.compatibility"
        val input = File(context.cacheDir, "test-panorama.png")
        val bitmap = Bitmap.createBitmap(8000, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        try {
            input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(AppRepository.saveOverrideBanner(context, pkg, Uri.fromFile(input)))
            val saved = File(AppRepository.overridesDir(context), "$pkg-banner.webp")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(saved.path, bounds)
            assertEquals(720, bounds.outWidth); assertEquals(18, bounds.outHeight)
            val original = AppEntry(pkg, "Original", null, null, "apps", Color.Black, 0, 0)
            val config = LauncherConfig(labelOverrides = mapOf(pkg to "Renamed"))
            val custom = AppRepository.overlay(context, listOf(original), config).single()
            assertEquals("Renamed", custom.label); assertNotNull(custom.banner)
            AppRepository.clearOverrideImages(context, pkg)
            assertSame(original, AppRepository.overlay(context, listOf(original), LauncherConfig()).single())
        } finally {
            bitmap.recycle(); input.delete(); AppRepository.clearOverrideImages(context, pkg)
        }
    }
}
