package com.conreo.couchytv.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.conreo.couchytv.BuildConfig
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local-only diagnostics for Home selection, excluding ADB credentials and unrelated app data. */
internal object HomeDiagnostics {
    const val FILE_NAME = "CouchyLauncher-debug.txt"
    private const val LIMIT = 256 * 1024
    private val lock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var launchReset: Job? = null

    /** New launcher instances start a log. Activity recreation and Home intents preserve it. */
    fun beginLaunch(context: Context, restoringActivity: Boolean) {
        if (restoringActivity && launchReset != null) return
        val app = context.applicationContext
        launchReset = scope.launch {
            synchronized(lock) {
                val reset = runCatching {
                    logFile(app).writeText("${Instant.now()}  Couchy Launcher started\n")
                }.onFailure { android.util.Log.w("CouchyHome", "Could not reset Home diagnostics", it) }
                if (reset.isFailure) return@synchronized
                val prefs = preferences(app)
                for (key in listOf("downloads_uri", "document_uri")) {
                    val uri = prefs.getString(key, null)?.let(Uri::parse) ?: continue
                    runCatching { write(app, uri) }
                        .onFailure { android.util.Log.w("CouchyHome", "Could not reset exported Home diagnostics", it) }
                }
            }
        }
    }

    suspend fun record(context: Context, event: String, error: Throwable? = null) = withContext(Dispatchers.IO) {
        launchReset?.join()
        synchronized(lock) {
            runCatching {
                val file = logFile(context)
                val detail = error?.stackTraceToString()?.take(8192)
                file.appendText(buildString {
                    append(Instant.now()).append("  ").appendLine(event.take(4096))
                    if (detail != null) appendLine(detail)
                })
                if (file.length() > LIMIT) {
                    val bytes = file.readBytes()
                    val tail = bytes.decodeToString(startIndex = bytes.size - LIMIT / 2)
                    file.writeText(tail.substringAfter('\n', ""))
                }
            }.onFailure { android.util.Log.w("CouchyHome", "Could not record Home diagnostics", it) }
        }
        Unit
    }

    /** Returns the actual display name, which Android may suffix if an older file already exists. */
    suspend fun publishToDownloads(context: Context): String? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 29) return@withContext null
        launchReset?.join()
        synchronized(lock) {
            runCatching {
                val app = context.applicationContext
                val preferences = preferences(app)
                val resolver = app.contentResolver
                val remembered = preferences.getString("downloads_uri", null)?.let(Uri::parse)
                if (remembered != null) {
                    val name = runCatching {
                        resolver.query(remembered, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?.also { write(app, remembered) }
                    }.getOrNull()
                    if (name != null) return@synchronized name
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }) ?: error("Could not create Downloads log")
                try {
                    write(app, uri)
                    val updated = resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    check(updated == 1) { "Could not publish Downloads log" }
                } catch (failure: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw failure
                }
                preferences.edit().putString("downloads_uri", uri.toString()).apply()
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: FILE_NAME
            }.onFailure { android.util.Log.w("CouchyHome", "Could not publish Downloads log", it) }.getOrNull()
        }
    }

    suspend fun writeDocument(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        launchReset?.join()
        synchronized(lock) {
            val saved = runCatching { write(context, uri) }.isSuccess
            if (saved) {
                val flag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flag) }
                    .onSuccess {
                        val prefs = preferences(context)
                        val old = prefs.getString("document_uri", null)?.let(Uri::parse)
                        prefs.edit().putString("document_uri", uri.toString()).apply()
                        if (old != null && old != uri) {
                            runCatching { context.contentResolver.releasePersistableUriPermission(old, flag) }
                        }
                    }
            }
            saved
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("home_diagnostics", Context.MODE_PRIVATE)

    private fun write(context: Context, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
        output.bufferedWriter().use { writer ->
            writer.appendLine("Couchy Launcher ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) Home diagnostics")
            writer.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MANUFACTURER} ${Build.MODEL}")
            writer.appendLine("Exported ${Instant.now()} | All event timestamps are UTC")
            writer.appendLine()
            val file = logFile(context)
            if (file.isFile) file.bufferedReader().use { it.copyTo(writer) }
            BetaDiagnostics.appendTo(context, writer)
        }
    }

    private fun logFile(context: Context) = File(context.applicationContext.cacheDir, "home-diagnostics.log")
}
