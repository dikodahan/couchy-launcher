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
private const val MAX_SLIDES = 10
private const val MAX_ART_BYTES = 8 * 1024 * 1024

internal data class AppPreviewProgram(
    val images: List<Uri>,
    val trailer: Uri?,
    val fallback: TrailerLookup?,
)

internal object TopShelf {
    private val projection = arrayOf(
        "package_name",
        PreviewPrograms.COLUMN_TYPE,
        PreviewPrograms.COLUMN_SEARCHABLE,
        PreviewPrograms.COLUMN_BROWSABLE,
        PreviewPrograms.COLUMN_THUMBNAIL_URI,
        PreviewPrograms.COLUMN_POSTER_ART_URI,
    )

    private val trailerProjection = projection + PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI
    private val metadataProjection = projection + arrayOf(
        PreviewPrograms.COLUMN_TITLE,
        PreviewPrograms.COLUMN_RELEASE_DATE,
        PreviewPrograms.COLUMN_CONTENT_ID,
    )

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    suspend fun programs(context: Context, packageName: String, includeTrailers: Boolean, includeFallback: Boolean): List<AppPreviewProgram> {
        val job = currentCoroutineContext()
        if (!hasPermission(context)) return emptyList()
        // Let the system launcher handle ratings on devices with parental controls.
        if (runCatching { context.getSystemService(TvInputManager::class.java)?.isParentalControlsEnabled }
                .getOrNull() != false) return emptyList()

        val art = linkedMapOf<Uri, AppPreviewProgram>()
        val tables = listOf(
            PreviewPrograms.CONTENT_URI to "${PreviewPrograms.COLUMN_WEIGHT} DESC, _id ASC",
            WatchNextPrograms.CONTENT_URI to "${WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC, _id ASC",
        )
        for ((table, order) in tables) {
            job.ensureActive()
            val uri = table.buildUpon().appendQueryParameter("package", packageName).build()
            fun query(columns: Array<String>) = try {
                context.contentResolver.query(uri, columns, null, null, order)
            } catch (_: RuntimeException) {
                null // An OEM provider may omit optional columns or restrict cross-package listings.
            }
            val cursor = when {
                includeFallback -> query(metadataProjection + PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI)
                    ?: query(metadataProjection)
                    ?: query(trailerProjection)
                    ?: query(projection)
                includeTrailers -> query(trailerProjection) ?: query(projection)
                else -> query(projection)
            }
            if (cursor == null) continue
            cursor.use { rows ->
                val titleColumn = rows.getColumnIndex(PreviewPrograms.COLUMN_TITLE)
                val releaseDateColumn = rows.getColumnIndex(PreviewPrograms.COLUMN_RELEASE_DATE)
                val contentIdColumn = rows.getColumnIndex(PreviewPrograms.COLUMN_CONTENT_ID)
                val trailerColumn = rows.getColumnIndex(PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI)
                fun string(column: Int) = if (column >= 0) rows.getString(column) else null
                fun media(column: Int) = string(column)?.let(Uri::parse)?.takeIf {
                    isSupported(context, it, packageName)
                }
                var scanned = 0
                while (art.size < MAX_SLIDES && scanned++ < 80 && rows.moveToNext()) {
                    job.ensureActive()
                    if (rows.getString(0) != packageName || !isMovieOrShow(rows.getInt(1)) ||
                        rows.getInt(2) != 1 || rows.getInt(3) != 1
                    ) continue
                    val images = (4..5).mapNotNull(::media).distinct()
                    val primary = images.firstOrNull() ?: continue
                    if (primary in art) continue
                    val type = rows.getInt(1)
                    val fallback = if (includeFallback) {
                        trailerLookup(
                            packageName,
                            film = type == PreviewPrograms.TYPE_MOVIE,
                            episodic = type == PreviewPrograms.TYPE_TV_SEASON || type == PreviewPrograms.TYPE_TV_EPISODE,
                            contentId = string(contentIdColumn),
                            title = string(titleColumn),
                            releaseDate = string(releaseDateColumn),
                        )
                    } else null
                    art[primary] = AppPreviewProgram(images, media(trailerColumn), fallback)
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
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
