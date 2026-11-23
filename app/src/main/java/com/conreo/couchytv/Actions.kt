package com.conreo.couchytv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.conreo.couchytv.data.AdbShellResult
import com.conreo.couchytv.data.LocalAdb
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

object Actions {

    fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun launchApp(context: Context, pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
                .onFailure { toast(context, context.getString(R.string.toast_cannot_open)) }
        } else toast(context, context.getString(R.string.toast_no_launchable))
    }

    fun openAppInfo(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg")
                )
            )
        }
    }

    fun uninstall(context: Context, pkg: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }
    }

    internal enum class CloseResult { Complete, Stopped, AdbUnavailable, Unavailable }

    internal suspend fun closeAndClearCache(context: Context, pkg: String): CloseResult {
        val command = closeCacheCommand(pkg) ?: return CloseResult.Unavailable
        if (pkg == context.packageName || !isInstalled(context, pkg)) return CloseResult.Unavailable
        return try {
            closeCacheResult(LocalAdb(context).shell(command))
        } catch (_: IOException) {
            CloseResult.AdbUnavailable
        } catch (_: TimeoutCancellationException) {
            CloseResult.AdbUnavailable
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            CloseResult.Unavailable
        }
    }

    internal fun closeCacheResult(result: AdbShellResult): CloseResult {
        val lines = result.output.lineSequence().map(String::trim).toSet()
        if ("COUCHY_STOPPED" !in lines) return CloseResult.Unavailable
        return if (result.exitCode == 0 && "Success" in lines) CloseResult.Complete else CloseResult.Stopped
    }

    internal fun closeCacheCommand(pkg: String): String? {
        if (!packageName.matches(pkg)) return null
        val permission = "android.permission.INTERNAL_DELETE_CACHE_FILES"
        // Some firmware advertises cache-only but silently ignores it for the shell UID.
        return """
            am force-stop --user current $pkg || exit 1
            echo COUCHY_STOPPED
            pm help | grep -Fq -- --cache-only || exit 1
            if [ "${'$'}(id -u)" = 2000 ] && pm list permissions | grep -Fxq 'permission:$permission'; then
                dumpsys package com.android.shell | grep -Fq '$permission: granted=true' || exit 1
            fi
            timeout -k 1 8 pm clear --user current --cache-only $pkg
        """.trimIndent()
    }

    private val packageName = Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")

    fun openDebuggingSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .onFailure { openSystemSettings(context) }
    }

    fun openSystemSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    fun openNetworkSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .onFailure { openSystemSettings(context) }
    }

    fun openVpnSettings(context: Context) {
        runCatching { context.startActivity(Intent("android.settings.VPN_SETTINGS")) }
            .onFailure { openSystemSettings(context) }
    }

    /** AerialViews screensaver (github.com/theothernt/AerialViews) */
    const val AERIAL_PKG = "com.neilturner.aerialviews"

    fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun openAppStore(context: Context, pkg: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        }.onFailure {
            toast(context, context.getString(R.string.toast_no_store))
        }
    }

}
