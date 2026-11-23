package com.conreo.couchytv.data

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.conreo.couchytv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class HomeChoice(val component: ComponentName, val label: String, val system: Boolean, val enabled: Boolean)
internal data class HomeStatus(val currentPackage: String?, val currentLabel: String, val choices: List<HomeChoice>, val pausedPackages: Set<String>)

internal class StockHomeStillActive(val activeHome: ComponentName) : IllegalStateException()

/** The command surface accepts installed HOME components, not free-form user shell input. */
internal object HomeCommands {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val classPattern = Regex("[A-Za-z0-9_.$]+")
    fun validPackage(pkg: String) = pkg.matches(packagePattern)
    fun component(value: ComponentName): String {
        require(validPackage(value.packageName) && value.className.matches(classPattern))
        return "'${value.flattenToString()}'"
    }
    fun enable(pkg: String, user: Int, original: Int = PackageManager.COMPONENT_ENABLED_STATE_ENABLED): String {
        require(validPackage(pkg) && user >= 0)
        val verb = when (original) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default-state"
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
            else -> error("Only previously enabled stock launchers can be restored")
        }
        return "pm $verb --user $user '$pkg'"
    }
    fun disable(pkg: String, user: Int): String {
        require(validPackage(pkg) && user >= 0)
        return "pm disable-user --user $user '$pkg'"
    }
    fun setHome(target: ComponentName, user: Int): String {
        require(user >= 0)
        return "cmd package set-home-activity --user $user ${component(target)}"
    }
    fun resolveHome(user: Int) = "cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.HOME"
    fun resolvedComponent(output: String): ComponentName? = output.lineSequence().map(String::trim)
        .mapNotNull(ComponentName::unflattenFromString).lastOrNull()
}

/**
 * One owner for Home changes and the recovery journal. The journal is deliberately excluded
 * from portable/OS backups: package state and ADB authorization belong to this TV/user.
 */
internal class HomeLauncherManager(
    context: Context,
    preferencesName: String = "home_launcher",
    private val execute: suspend (String) -> AdbShellResult = LocalAdb(context.applicationContext)::shell,
) {
    private val app = context.applicationContext
    private val pm = app.packageManager
    private val prefs = app.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val stockLaunchers = setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")
    private val stockPackages = stockLaunchers + "com.google.android.tungsten.setupwraith"
    private val homeIntent get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    suspend fun status(): HomeStatus = withContext(Dispatchers.IO) {
        val choices = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DISABLED_COMPONENTS)
            .mapNotNull { result ->
                val activity = result.activityInfo ?: return@mapNotNull null
                if (!activity.exported) return@mapNotNull null
                val component = ComponentName(activity.packageName, activity.name)
                runCatching { HomeCommands.component(component) }.getOrNull() ?: return@mapNotNull null
                HomeChoice(component, result.loadLabel(pm).toString(),
                    activity.applicationInfo.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    activity.enabled && activity.applicationInfo.enabled)
            }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
        val current = currentHome()
        HomeStatus(current?.packageName,
            current?.let { c -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull() }.orEmpty(),
            choices, readJournal().keys)
    }

    fun currentHome(): ComponentName? = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.let { if (it.packageName == "android") null else ComponentName(it.packageName, it.name) }

    fun systemPickerIntent(): Intent {
        return couchyRoleIntent() ?: Intent(Settings.ACTION_HOME_SETTINGS)
    }

    fun couchyRoleIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 29) return null
        val roles = app.getSystemService(RoleManager::class.java) ?: return null
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return null
        return roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
    }

    /** Restores stock packages before changing away from Couchy. Never disables an arbitrary app. */
    suspend fun switchTo(choice: HomeChoice, pauseStock: Boolean) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val installed = status().choices.firstOrNull { it.component == choice.component }
                ?: error(app.getString(R.string.home_error_removed))
            val target = installed.component
            val ownHome = target.packageName == app.packageName
            require(!pauseStock || ownHome)
            val user = foregroundAppUser()
            if (readJournal().isNotEmpty()) check(journalUser() == user) { app.getString(R.string.home_error_user) }
            val previous = currentHome()
            val originalJournal = readJournal()
            val targetWasDisabled = !installed.enabled
            try {
                // A previous interrupted operation is always recoverable before another switch.
                if (!ownHome || !pauseStock) restoreJournal(user)
                check(checked("am get-current-user").output.trim().toIntOrNull() == user) {
                    app.getString(R.string.home_error_user)
                }
                // Launcher Manager may have paused Google's provisioning companion too.
                if (target.packageName in stockLaunchers && isInstalled("com.google.android.tungsten.setupwraith") &&
                    !isEnabled("com.google.android.tungsten.setupwraith"))
                    checked(HomeCommands.enable("com.google.android.tungsten.setupwraith", user))
                if (targetWasDisabled) checked(HomeCommands.enable(target.packageName, user))
                val setHome = shell(HomeCommands.setHome(target, user))
                if (!pauseStock) {
                    check(setHome.exitCode == 0) { setHome.output.take(300).ifBlank { app.getString(R.string.home_error_rejected) } }
                    val resolved = resolveHome(user)
                    if (ownHome && resolved != null && resolved.packageName in stockLaunchers &&
                        isSystemPackage(resolved.packageName)
                    ) throw StockHomeStillActive(resolved)
                    check(resolved == target) { app.getString(R.string.home_error_rejected) }
                } else {
                    // Some Google TV builds keep their privileged stock Home until it is paused.
                    // Before touching it, independently verify Couchy's enabled HOME entry exists.
                    val candidate = checked("cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.HOME -p '${target.packageName}'")
                    check(HomeCommands.resolvedComponent(candidate.output) == target) {
                        app.getString(R.string.home_error_rejected)
                    }
                }
                if (pauseStock) {
                    val activeStock = previous?.packageName?.takeIf { it in stockLaunchers }
                    val candidates = if (activeStock == "com.google.android.tvlauncher" ||
                        (activeStock == null && !isEnabled("com.google.android.apps.tv.launcherx"))
                    ) setOf("com.google.android.tvlauncher") else stockPackages - "com.google.android.tvlauncher"
                    for (pkg in candidates) {
                        if (!isSystemPackage(pkg) || !isEnabled(pkg)) continue
                        val state = pm.getApplicationEnabledSetting(pkg)
                        // Persist before mutating package state, so process death leaves a recovery path.
                        writeJournal(readJournal() + (pkg to state), user)
                        checked(HomeCommands.disable(pkg, user))
                    }
                    verifyHome(target, user)
                }
            } catch (failure: Exception) {
                // Skip rollback only when nothing was paused and the same Home is still active.
                if (failure is StockHomeStillActive && originalJournal.isEmpty() &&
                    previous == failure.activeHome
                ) throw failure
                // Repair is bounded by the ADB client's timeouts, even when the UI was closed.
                withContext(NonCancellable) {
                    HomeDiagnostics.record(app, "Home change failed; attempting recovery", failure)
                    val recovery = runCatching {
                        restoreJournal(user)
                        previous?.takeIf { it != target }?.let {
                            checked(HomeCommands.setHome(it, user))
                            verifyHome(it, user)
                        }
                    }
                    if (recovery.isFailure) {
                        // Keep the durable journal rather than claiming the stock apps were restored.
                        HomeDiagnostics.record(app, "Home recovery requires retry", recovery.exceptionOrNull())
                    } else {
                        HomeDiagnostics.record(app, "Home recovery completed")
                        if (originalJournal.isEmpty()) writeJournal(emptyMap(), user)
                    }
                }
                throw failure
            }
        }
    }

    /** Recovery never turns a stock package off and does not require a selected replacement. */
    suspend fun restorePausedPackages() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val user = journalUser() ?: foregroundAppUser()
            verifyAppUser(user)
            restoreJournal(user)
        }
    }

    private suspend fun foregroundAppUser(): Int {
        val user = checked("am get-current-user").output.trim().toIntOrNull()
            ?.takeIf { it >= 0 } ?: error(app.getString(R.string.home_error_user))
        verifyAppUser(user)
        return user
    }
    private suspend fun verifyAppUser(user: Int) {
        check(user >= 0 && HomeCommands.validPackage(app.packageName))
        // Public SDK28 UserHandle has no numeric identifier. Compare exact package UIDs instead.
        val result = checked("pm list packages -U --user $user '${app.packageName}'")
        val pattern = Regex("(?m)^package:${Regex.escape(app.packageName)}\\s+uid:([0-9]+)\\s*$")
        val uid = pattern.find(result.output)?.groupValues?.get(1)?.toIntOrNull()
        check(uid == app.applicationInfo.uid) { app.getString(R.string.home_error_user) }
    }
    private suspend fun verifyHome(target: ComponentName, user: Int) {
        check(resolveHome(user) == target) { app.getString(R.string.home_error_rejected) }
    }
    private suspend fun resolveHome(user: Int): ComponentName? =
        HomeCommands.resolvedComponent(checked(HomeCommands.resolveHome(user)).output)

    private suspend fun checked(command: String): AdbShellResult {
        val result = shell(command)
        check(result.exitCode == 0) { result.output.trim().take(300).ifBlank { app.getString(R.string.home_error_rejected) } }
        return result
    }
    private suspend fun shell(command: String): AdbShellResult {
        HomeDiagnostics.record(app, "ADB command: $command")
        val started = android.os.SystemClock.elapsedRealtime()
        val result = execute(command)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        HomeDiagnostics.record(app, "ADB exit ${result.exitCode} after ${elapsed} ms: ${result.output}")
        return result
    }

    private suspend fun restoreJournal(user: Int) {
        if (readJournal().isNotEmpty()) check(journalUser() == user) { app.getString(R.string.home_error_user) }
        for ((pkg, state) in readJournal()) {
            if (isInstalled(pkg)) checked(HomeCommands.enable(pkg, user, state))
            writeJournal(readJournal() - pkg, user)
        }
    }
    private fun isInstalled(pkg: String) = runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.isSuccess
    private fun isSystemPackage(pkg: String) = runCatching {
        val flags = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)
    private fun isEnabled(pkg: String) = runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled }.getOrDefault(false)
    private fun readJournal(): Map<String, Int> = runCatching {
        val json = JSONObject(prefs.getString("paused", "{}") ?: "{}")
        json.keys().asSequence().filter { it in stockPackages }.associateWith { json.getInt(it) }
            .filterValues { it == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || it == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
    }.getOrDefault(emptyMap())
    private fun journalUser(): Int? = prefs.getInt("user", -1).takeIf { it >= 0 }
    private fun writeJournal(states: Map<String, Int>, user: Int) {
        check(user >= 0 && states.keys.all { it in stockPackages })
        val editor = prefs.edit().putString("paused", JSONObject(states).toString())
        if (states.isEmpty()) editor.remove("user") else editor.putInt("user", user)
        check(editor.commit()) { app.getString(R.string.home_error_not_saved) }
    }

    companion object {
        @Volatile private var instance: HomeLauncherManager? = null
        fun get(context: Context): HomeLauncherManager = instance ?: synchronized(this) {
            instance ?: HomeLauncherManager(context).also { instance = it }
        }
    }
}
