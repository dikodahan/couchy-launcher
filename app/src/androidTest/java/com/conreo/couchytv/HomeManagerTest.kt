package com.conreo.couchytv

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.conreo.couchytv.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeManagerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun rejectedHomeChangeNeverDisablesAStockLauncher() = runBlocking {
        val commands = mutableListOf<String>()
        val prefs = "home_test_rejection"
        context.getSharedPreferences(prefs, 0).edit().clear().commit()
        val manager = HomeLauncherManager(context, prefs) { command ->
            commands += command
            when {
                command == "am get-current-user" -> AdbShellResult(0, "0")
                command.startsWith("pm list packages -U") -> AdbShellResult(0, "package:${context.packageName} uid:${context.applicationInfo.uid}")
                command.contains("set-home-activity") -> AdbShellResult(1, "Permission denied")
                else -> AdbShellResult(0, "")
            }
        }
        val couchy = manager.status().choices.first { it.component.packageName == context.packageName }
        assertTrue(runCatching { manager.switchTo(couchy, false) }.isFailure)
        assertFalse(commands.any { it.contains("disable-user") })
        assertTrue(manager.status().pausedPackages.isEmpty())
    }
    @Test fun stockOverrideReusesOnlyARecordedApproval() = runBlocking {
        val stock = context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo
        assumeTrue("Requires Android TV Home as the active launcher",
            stock?.packageName == "com.google.android.tvlauncher")
        requireNotNull(stock)
        val stockComponent = ComponentName(stock.packageName, stock.name)
        val couchyComponent = ComponentName(context.packageName, MainActivity::class.java.name)
        val commands = mutableListOf<String>()
        var paused = false
        val prefs = "home_test_stock_override"
        context.getSharedPreferences(prefs, 0).edit().clear().commit()
        val manager = HomeLauncherManager(context, prefs) { command ->
            commands += command
            when {
                command == "am get-current-user" -> AdbShellResult(0, "0")
                command.startsWith("pm list packages -U") ->
                    AdbShellResult(0, "package:${context.packageName} uid:${context.applicationInfo.uid}")
                command.contains("disable-user") -> { paused = true; AdbShellResult(0, "") }
                command.startsWith("pm default-state") || command.startsWith("pm enable") -> {
                    paused = false
                    AdbShellResult(0, "")
                }
                command.contains("resolve-activity") -> {
                    val home = if (paused || command.contains(" -p ")) couchyComponent else stockComponent
                    AdbShellResult(0, home.flattenToString())
                }
                else -> AdbShellResult(0, "Success")
            }
        }
        val couchy = manager.status().choices.first { it.component == couchyComponent }
        try {
            assertFalse(manager.status().couchyConfigured)
            assertTrue(runCatching { manager.switchToConfiguredCouchy(couchy) }.exceptionOrNull() is StockHomeStillActive)
            assertFalse(commands.any { it.contains("disable-user") })
            assertTrue(manager.status().pausedPackages.isEmpty())
            manager.switchTo(couchy, true)
            assertTrue(paused)
            assertEquals(setOf(stock.packageName), manager.status().pausedPackages)
            assertTrue(manager.status().couchyConfigured)

            val stockChoice = manager.status().choices.first { it.component == stockComponent }
            manager.switchTo(stockChoice, pauseStock = false)
            assertFalse(paused)
            assertTrue(manager.status().pausedPackages.isEmpty())
            assertTrue(manager.status().couchyConfigured)

            commands.clear()
            manager.switchToConfiguredCouchy(couchy)
            assertTrue(paused)
            assertEquals(setOf(stock.packageName), manager.status().pausedPackages)
            assertEquals(listOf("pm disable-user --user 0 '${stock.packageName}'"),
                commands.filter { it.contains("disable-user") })
        } finally {
            context.getSharedPreferences(prefs, 0).edit().clear().commit()
        }
    }

    @Test fun failedStockPauseRestoresRecordedState() = runBlocking {
        val enabledStock = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher").any {
            runCatching { context.packageManager.getApplicationInfo(it, 0).enabled }.getOrDefault(false)
        }
        assumeTrue("Recovery test needs an enabled stock launcher fixture", enabledStock)
        val commands = mutableListOf<String>()
        val prefs = "home_test_recovery"
        context.getSharedPreferences(prefs, 0).edit().clear().commit()
        val couchyComponent = ComponentName(context.packageName, MainActivity::class.java.name)
        val manager = HomeLauncherManager(context, prefs) { command ->
            commands += command
            when {
                command == "am get-current-user" -> AdbShellResult(0, "0")
                command.startsWith("pm list packages -U") -> AdbShellResult(0, "package:${context.packageName} uid:${context.applicationInfo.uid}")
                command.contains("resolve-activity") -> AdbShellResult(0, couchyComponent.flattenToString())
                command.contains("disable-user") -> AdbShellResult(1, "Test failure")
                else -> AdbShellResult(0, "Success")
            }
        }
        val couchy = manager.status().choices.first { it.component.packageName == context.packageName }
        // The TV emulator has an enabled stock launcher; commands are mocked, so it is never changed.
        assertTrue(runCatching { manager.switchTo(couchy, true) }.isFailure)
        assertTrue(commands.any { it.contains("disable-user") })
        assertTrue(commands.any { it.startsWith("pm default-state") || it.startsWith("pm enable") })
        assertTrue(manager.status().pausedPackages.isEmpty())
    }
    @Test fun anotherAndroidUserIsRejectedBeforeAnyMutation() = runBlocking {
        val commands = mutableListOf<String>()
        val prefs = "home_test_user"
        context.getSharedPreferences(prefs, 0).edit().clear().commit()
        val manager = HomeLauncherManager(context, prefs) { command ->
            commands += command
            if (command == "am get-current-user") AdbShellResult(0, "10")
            else AdbShellResult(0, "package:${context.packageName} uid:${context.applicationInfo.uid + 1000000}")
        }
        val couchy = manager.status().choices.first { it.component.packageName == context.packageName }
        assertTrue(runCatching { manager.switchTo(couchy, true) }.isFailure)
        assertFalse(commands.any { it.contains("set-home-activity") || it.contains("disable-user") || it.startsWith("pm enable") })
    }

    @Test fun commandComponentsRejectInjection() {
        val component = ComponentName("com.example.tv", "com.example.tv.MainActivity")
        assertEquals("cmd package set-home-activity --user 10 'com.example.tv/com.example.tv.MainActivity'", HomeCommands.setHome(component, 10))
        assertTrue(runCatching { HomeCommands.setHome(ComponentName("com.example.tv", "Main;reboot"), 0) }.isFailure)
    }
}
