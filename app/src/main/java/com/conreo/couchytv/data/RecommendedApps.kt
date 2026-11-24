package com.conreo.couchytv.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class RecommendedApp(
    val category: String,
    val name: String,
    val downloadUrl: String,
    val iconUrl: String? = null,
) {
    val key get() = name.lowercase(Locale.ROOT)
}

internal fun parseRecommendedApps(content: String): List<RecommendedApp> =
    catalogJson.decodeFromString<List<RecommendedApp>>(content).also { apps ->
        require(apps.size <= 200 && apps.distinctBy(RecommendedApp::key).size == apps.size)
        require(apps.all { entry ->
            entry.name.length in 1..80 && entry.name == entry.name.trim() &&
                entry.category.isNotBlank() && entry.category.length <= 80 &&
                entry.name.none(Char::isISOControl) && entry.category.none(Char::isISOControl) &&
                isRecommendedAppsUrl(entry.downloadUrl) && (entry.iconUrl == null || isRecommendedAppsUrl(entry.iconUrl))
        })
    }

internal fun isRecommendedAppsUrl(value: String): Boolean =
    value.length in 1..2048 && value.none(Char::isWhitespace) && value.toHttpUrlOrNull()?.let {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    } == true

internal sealed interface RecommendedDownload {
    data class Active(val progress: Float?) : RecommendedDownload
    data object Ready : RecommendedDownload
    data object Failed : RecommendedDownload
}

private const val APK_TYPE = "application/vnd.android.package-archive"
private val catalogJson = Json { ignoreUnknownKeys = true }

internal class RecommendedApps(context: Context, private val catalogUrl: String) {
    private val catalogKey = MessageDigest.getInstance("SHA-256")
        .digest(catalogUrl.toByteArray()).joinToString("") { "%02x".format(it) }
    private val app = context.applicationContext
    private val manager = app.getSystemService(DownloadManager::class.java)
    private val preferences = app.getSharedPreferences("recommended_downloads", Context.MODE_PRIVATE)
    private val cachedCatalog = AtomicFile(File(app.cacheDir, "recommended-$catalogKey.json"))

    suspend fun cached(): List<RecommendedApp>? = withContext(Dispatchers.IO) {
        if (!isRecommendedAppsUrl(catalogUrl)) return@withContext null
        synchronized(catalogLock) {
            runCatching { parseRecommendedApps(cachedCatalog.readFully().decodeToString()) }.getOrNull()
        }
    }

    suspend fun refresh(): List<RecommendedApp> = withContext(Dispatchers.IO) {
        require(isRecommendedAppsUrl(catalogUrl))
        val data = fetch(catalogUrl, 256L * 1024)
        val entries = parseRecommendedApps(data.decodeToString())
        synchronized(catalogLock) {
            val stream = cachedCatalog.startWrite()
            try {
                stream.write(data)
                cachedCatalog.finishWrite(stream)
            } catch (error: IOException) {
                cachedCatalog.failWrite(stream)
                throw error
            }
        }
        entries
    }

    suspend fun fallbackIcon(entry: RecommendedApp): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$catalogKey:${entry.key}"
        icons.get(key) ?: runCatching {
            val library = IconLibrary.load(app).search("")
            val index = Math.floorMod(key.hashCode(), library.size)
            library[index].render(96).also { icons.put(key, it) }
        }.getOrNull()
    }

    suspend fun icon(url: String): Bitmap? = withContext(Dispatchers.IO) {
        icons.get(url) ?: runCatching {
            val bytes = fetch(url, 2L * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
            var sample = 1
            val side = maxOf(bounds.outWidth, bounds.outHeight)
            while (side / sample > 256) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { icons.put(url, it) }
        }.getOrNull()
    }

    suspend fun downloads(entries: List<RecommendedApp>): Map<String, RecommendedDownload> =
        withContext(Dispatchers.IO) {
            synchronized(downloadLock) {
                val keys = entries.mapNotNull { entry -> id(entry)?.let { it to entry.key } }.toMap()
                readDownloads(keys)
            }
        }

    suspend fun enqueue(entry: RecommendedApp): Unit = withContext(Dispatchers.IO) {
        synchronized(downloadLock) {
            val existing = id(entry)
            if (existing != null) {
                when (readDownloads(mapOf(existing to entry.key))[entry.key]) {
                    is RecommendedDownload.Active, RecommendedDownload.Ready -> return@synchronized
                    else -> manager.remove(existing)
                }
            }
            val slug = entry.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('.', '-', '_').ifEmpty { "app" }
            val filename = "Couchy-$slug-${UUID.randomUUID().toString().take(8)}.apk"
            require(isRecommendedAppsUrl(entry.downloadUrl))
            val request = DownloadManager.Request(Uri.parse(entry.downloadUrl))
                .setTitle(entry.name)
                .setMimeType(APK_TYPE)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val downloadId = manager.enqueue(request)
            if (!preferences.edit().putLong(downloadKey(entry), downloadId).commit()) {
                manager.remove(downloadId)
                throw IOException("Could not record download")
            }
        }
    }

    suspend fun delete(entry: RecommendedApp): Unit = withContext(Dispatchers.IO) {
        synchronized(downloadLock) {
            val downloadId = id(entry) ?: return@synchronized
            manager.remove(downloadId)
            if (!preferences.edit().remove(downloadKey(entry)).commit()) throw IOException("Could not remove download record")
        }
    }

    suspend fun installer(entry: RecommendedApp): Intent = withContext(Dispatchers.IO) {
        val downloadId = synchronized(downloadLock) { id(entry) } ?: throw IOException("Download is missing")
        val uri = manager.getUriForDownloadedFile(downloadId) ?: throw IOException("Download is incomplete")
        manager.openDownloadedFile(downloadId).use { require(it.statSize != 0L) }
        Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun downloadKey(entry: RecommendedApp) = "$catalogKey:${entry.key}"

    private fun id(entry: RecommendedApp): Long? = preferences.getLong(downloadKey(entry), -1L).takeIf { it >= 0L }

    private fun readDownloads(keys: Map<Long, String>): Map<String, RecommendedDownload> {
        if (keys.isEmpty()) return emptyMap()
        return buildMap {
            manager.query(DownloadManager.Query().setFilterById(*keys.keys.toLongArray()))?.use { rows ->
                val idColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val stateColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val bytesColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val sizeColumn = rows.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (rows.moveToNext()) {
                    val downloadId = rows.getLong(idColumn)
                    val key = keys[downloadId] ?: continue
                    val state = when (rows.getInt(stateColumn)) {
                        DownloadManager.STATUS_SUCCESSFUL -> if (runCatching {
                            manager.openDownloadedFile(downloadId).use { it.statSize != 0L }
                        }.getOrDefault(false)) RecommendedDownload.Ready else continue
                        DownloadManager.STATUS_FAILED -> RecommendedDownload.Failed
                        else -> {
                            val size = rows.getLong(sizeColumn)
                            val progress = if (size > 0) (rows.getLong(bytesColumn).toFloat() / size).coerceIn(0f, 1f) else null
                            RecommendedDownload.Active(progress)
                        }
                    }
                    put(key, state)
                }
            }
        }
    }

    private fun fetch(url: String, limit: Long): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("Empty response")
            if (source.request(limit + 1)) throw IOException("Response exceeds size limit")
            source.readByteArray()
        }

    private companion object {
        val downloadLock = Any()
        val catalogLock = Any()
        val http = OkHttpClient.Builder().followSslRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()
        val icons = LruCache<String, Bitmap>(32)
    }
}
