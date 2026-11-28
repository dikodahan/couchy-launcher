package com.conreo.couchytv.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.conreo.couchytv.BuildConfig
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Beta-only local log. Callers supply diagnostic fields, never URLs, credentials or user content. */
internal object BetaDiagnostics {
    const val FILE_NAME = "CouchyLauncher-beta-debug.txt"
    private const val LIMIT = 384 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<String>(64, BufferOverflow.DROP_OLDEST)
    private val lock = Any()
    private var started = false
    private val drawnGenerations = mutableMapOf<String, Int>()

    fun begin(context: Context) {
        if (!isBetaBuild || started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            val file = logFile(app)
            runCatching {
                synchronized(lock) { file.writeText("") }
            }
            for (event in events) {
                delay(350)
                val batch = buildString {
                    appendLine(event)
                    repeat(64) {
                        val next = events.tryReceive().getOrNull() ?: return@buildString
                        appendLine(next)
                    }
                }
                runCatching {
                    synchronized(lock) {
                        file.appendText(batch)
                        if (file.length() > LIMIT) {
                            val bytes = file.readBytes()
                            file.writeText(bytes.decodeToString(bytes.size - LIMIT / 2).substringAfter('\n', ""))
                        }
                        publish(app, file)
                    }
                }.onFailure { Log.w("CouchyBeta", "Could not write beta diagnostics: ${it.javaClass.simpleName}") }
            }
        }
        event("Launch version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
            "android=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} pid=${android.os.Process.myPid()}")
    }

    private fun monotonicMillis() = System.nanoTime() / 1_000_000L

    fun event(message: String) {
        if (isBetaBuild) events.trySend("${Instant.now()} monotonicMs=${monotonicMillis()}  $message")
    }

    fun failure(operation: String, error: Throwable) {
        if (!isBetaBuild) return
        val frames = error.stackTrace.take(6).joinToString(" <- ")
        event("$operation failed type=${error.javaClass.simpleName} cause=${error.cause?.javaClass?.simpleName} at=$frames")
    }

    inline fun <T> operation(name: String, block: () -> T): T {
        if (!isBetaBuild) return block()
        val startedAt = monotonicMillis()
        event("$name started")
        return try {
            block().also { event("$name completed elapsedMs=${monotonicMillis() - startedAt}") }
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (error is kotlinx.coroutines.TimeoutCancellationException) failure(name, error)
            throw error
        } catch (error: Exception) {
            failure(name, error)
            throw error
        }
    }

    /** Called on the UI thread. One sample per layer and artwork generation. */
    fun artworkDrawn(layer: String, generation: Int, width: Int, height: Int) {
        if (drawnGenerations[layer] == generation) return
        drawnGenerations[layer] = generation
        event("Artwork draw submitted layer=$layer generation=$generation size=${width}x$height")
    }

    fun resetDrawSamples() = drawnGenerations.clear()

    fun appendTo(context: Context, writer: java.io.Writer) {
        if (!isBetaBuild) return
        synchronized(lock) {
            val file = logFile(context)
            if (file.isFile) {
                writer.appendLine().appendLine("Beta diagnostics")
                file.bufferedReader().use { it.copyTo(writer) }
            }
        }
    }

    fun copyReport(context: Context, destination: File) {
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(memory)
        val config = context.resources.configuration
        destination.bufferedWriter().use { writer ->
            writer.appendLine("Couchy Launcher ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            writer.appendLine("Collected ${Instant.now()} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            writer.appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL} | CPU ${Build.SUPPORTED_ABIS.joinToString()}")
            writer.appendLine("Display ${config.screenWidthDp}x${config.screenHeightDp}dp dpi=${config.densityDpi}")
            writer.appendLine("RAM availableMiB=${memory.availMem / (1024 * 1024)} low=${memory.lowMemory}")
            writer.appendLine("Storage availableMiB=${context.filesDir.usableSpace / (1024 * 1024)}")
            appendTo(context, writer)
        }
    }

    private fun logFile(context: Context) = File(context.cacheDir, "beta-diagnostics.log")

    @Suppress("DEPRECATION")
    private fun publish(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (directory.isDirectory || directory.mkdirs()) file.copyTo(File(directory, FILE_NAME), overwrite = true)
            }
            return
        }
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences("beta_diagnostics", Context.MODE_PRIVATE)
        val existing = prefs.getString("downloads_uri", null)?.let(Uri::parse)
        fun write(uri: Uri) {
            val output = resolver.openOutputStream(uri, "wt") ?: error("Missing diagnostics output")
            output.use { sink -> file.inputStream().use { it.copyTo(sink) } }
        }
        if (existing != null && runCatching { write(existing) }.isSuccess) return
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: return
        try {
            write(uri)
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null) == 1)
            prefs.edit().putString("downloads_uri", uri.toString()).apply()
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }
}
