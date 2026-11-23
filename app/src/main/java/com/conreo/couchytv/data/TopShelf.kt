package com.conreo.couchytv.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.tv.TvContract.PreviewPrograms
import android.media.tv.TvContract.WatchNextPrograms
import android.media.tv.TvInputManager
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

internal const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
private const val MAX_SLIDES = 4
private const val MAX_ART_BYTES = 8 * 1024 * 1024

internal object TopShelf {
    private val projection = arrayOf(
        "package_name",
        PreviewPrograms.COLUMN_TYPE,
        PreviewPrograms.COLUMN_SEARCHABLE,
        PreviewPrograms.COLUMN_BROWSABLE,
        PreviewPrograms.COLUMN_THUMBNAIL_URI,
        PreviewPrograms.COLUMN_POSTER_ART_URI,
    )

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    suspend fun artwork(context: Context, packageName: String): List<List<Uri>> {
        val job = currentCoroutineContext()
        if (!hasPermission(context)) return emptyList()
        // Let the system launcher handle ratings on devices with parental controls.
        if (runCatching { context.getSystemService(TvInputManager::class.java)?.isParentalControlsEnabled }
                .getOrNull() != false) return emptyList()

        val art = linkedMapOf<Uri, List<Uri>>()
        val tables = listOf(
            PreviewPrograms.CONTENT_URI to "${PreviewPrograms.COLUMN_WEIGHT} DESC, _id ASC",
            WatchNextPrograms.CONTENT_URI to "${WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC, _id ASC",
        )
        for ((table, order) in tables) {
            job.ensureActive()
            val uri = table.buildUpon().appendQueryParameter("package", packageName).build()
            val cursor = try {
                context.contentResolver.query(uri, projection, null, null, order)
            } catch (_: RuntimeException) {
                null // Some OEM providers restrict cross-package listings.
            } ?: continue
            cursor.use { rows ->
                var scanned = 0
                while (art.size < MAX_SLIDES && scanned++ < 80 && rows.moveToNext()) {
                    job.ensureActive()
                    if (rows.getString(0) != packageName || !isMovieOrShow(rows.getInt(1)) ||
                        rows.getInt(2) != 1 || rows.getInt(3) != 1
                    ) continue
                    val images = (4..5).mapNotNull { column ->
                        rows.getString(column)?.let(Uri::parse)?.takeIf { isSupported(context, it, packageName) }
                    }
                    images.firstOrNull()?.let { art.putIfAbsent(it, images.distinct()) }
                }
            }
            if (art.size >= MAX_SLIDES) break
        }
        return art.values.toList()
    }

    private fun isMovieOrShow(type: Int): Boolean = when (type) {
        PreviewPrograms.TYPE_MOVIE, PreviewPrograms.TYPE_TV_SERIES,
        PreviewPrograms.TYPE_TV_SEASON, PreviewPrograms.TYPE_TV_EPISODE -> true
        else -> false
    }

    private fun isSupported(context: Context, uri: Uri, pkg: String): Boolean = when (uri.scheme) {
        "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null
        "android.resource" -> uri.authority == pkg
        "content" -> uri.authority?.let {
            context.packageManager.resolveContentProvider(it, 0)?.packageName == pkg
        } == true
        else -> false
    }

    suspend fun load(context: Context, uri: Uri): Bitmap? {
        val job = currentCoroutineContext()
        val source = if (uri.scheme == "https") {
            val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
            val bytes = try {
                connection.connectTimeout = 4_000
                connection.readTimeout = 4_000
                if (connection.responseCode !in 200..299 || connection.contentLength > MAX_ART_BYTES) return null
                connection.inputStream.use { input -> readArtwork(input) { job.ensureActive() } } ?: return null
            } finally {
                connection.disconnect()
            }
            ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        } else {
            ImageDecoder.createSource(context.contentResolver, uri)
        }
        job.ensureActive()
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = minOf(1f, 1280f / info.size.width, 720f / info.size.height)
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }

    private fun readArtwork(input: InputStream, checkActive: () -> Unit): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val bytes = ByteArrayOutputStream()
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return bytes.toByteArray()
            if (bytes.size() + count > MAX_ART_BYTES) return null
            bytes.write(buffer, 0, count)
        }
    }
}
