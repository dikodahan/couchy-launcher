package com.conreo.couchytv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.conreo.couchytv.data.BetaDiagnostics
import com.conreo.couchytv.data.HomeDiagnostics
import com.conreo.couchytv.data.isBetaBuild
import com.conreo.couchytv.ui.LauncherApp
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** Bumped whenever a package is installed/removed so the app grid rescans. */
    var rescanTick by mutableIntStateOf(0)
        private set

    private var artworkGeneration by mutableIntStateOf(0)
    private val screensaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_DREAMING_STARTED) {
                BetaDiagnostics.event("Screensaver started")
            } else if (intent.action == Intent.ACTION_DREAMING_STOPPED) {
                artworkGeneration++
                BetaDiagnostics.event("Screensaver exited; requesting artwork refresh generation=$artworkGeneration")
                android.util.Log.d("Couchy", "Refreshing Home artwork after screensaver exit")
            }
        }
    }

    private var homeIntentTick by mutableIntStateOf(0)

    internal var attachedLanguage: String = ""
        private set

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            // Removals always rescan (the app may be on the grid). For installs and
            // changes, skip the full scan unless the package is actually launchable.
            val launchable = pkg != null && (
                packageManager.getLeanbackLaunchIntentForPackage(pkg) != null ||
                    packageManager.getLaunchIntentForPackage(pkg) != null
                )
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED || launchable) {
                rescanTick++
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Read the chosen UI language synchronously from SharedPreferences — cheap,
        // and off the DataStore (a runBlocking DataStore read here blocked the main
        // thread and made language changes hang until a restart). LauncherApp keeps
        // this mirror in sync with the DataStore config and triggers recreate().
        val lang = newBase.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
            .getString(LOCALE_KEY, "").orEmpty()
        attachedLanguage = lang
        if (lang.isEmpty()) Locale.setDefault(newBase.resources.configuration.locales[0])
        super.attachBaseContext(if (lang.isEmpty()) newBase else applyLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BetaDiagnostics.begin(this)
        if (isBetaBuild) BetaDiagnostics.resetDrawSamples()
        BetaDiagnostics.event("Activity created restoring=${savedInstanceState != null}")
        HomeDiagnostics.beginLaunch(this, restoringActivity = savedInstanceState != null)

        // A home screen never exits on Back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* no-op */ }
        })

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screensaverReceiver,
            IntentFilter(Intent.ACTION_DREAMING_STOPPED).apply {
                if (isBetaBuild) addAction(Intent.ACTION_DREAMING_STARTED)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Compose reads the host view direction, including when the app's
        // language differs from the system language.
        window.decorView.layoutDirection = resources.configuration.layoutDirection
        setContent {
            LauncherApp(rescanTick, homeIntentTick, artworkGeneration)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeIntentTick++
            BetaDiagnostics.event("Home intent sequence=$homeIntentTick artworkGeneration=$artworkGeneration")
        }
    }

    override fun onResume() {
        super.onResume()
        if (isBetaBuild) {
            val runtime = Runtime.getRuntime()
            BetaDiagnostics.event("Activity resumed artworkGeneration=$artworkGeneration " +
                "accelerated=${window.decorView.isHardwareAccelerated} " +
                "heapUsedKiB=${(runtime.totalMemory() - runtime.freeMemory()) / 1024}")
        }
    }

    override fun onStop() {
        BetaDiagnostics.event("Activity stopped")
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        BetaDiagnostics.event("Window focus=$hasFocus artworkGeneration=$artworkGeneration")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BetaDiagnostics.event("Memory trim level=$level")
    }

    override fun onDestroy() {
        unregisterReceiver(screensaverReceiver)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    companion object {
        private const val LOCALE_PREFS = "locale"
        private const val LOCALE_KEY = "lang"

        /** Mirror the chosen language so attachBaseContext can read it synchronously. */
        fun persistLocale(context: Context, lang: String) {
            context.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
                .edit().putString(LOCALE_KEY, lang).apply()
        }

        fun currentLocalePref(context: Context): String =
            context.getSharedPreferences(LOCALE_PREFS, MODE_PRIVATE)
                .getString(LOCALE_KEY, "").orEmpty()

        fun applyLocale(context: Context, lang: String): Context {
            val locale = if (lang.contains('-')) {
                val parts = lang.split('-')
                Locale(parts[0], parts.getOrElse(1) { "" })
            } else Locale.forLanguageTag(lang)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            return context.createConfigurationContext(config)
        }
    }
}
