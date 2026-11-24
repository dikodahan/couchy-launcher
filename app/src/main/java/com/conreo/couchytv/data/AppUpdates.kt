package com.conreo.couchytv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class AppUpdate(
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubAsset>,
)

@Serializable
private data class GitHubAsset(
    val name: String,
    val size: Long,
    val state: String,
    val digest: String? = null,
    @SerialName("browser_download_url") val url: String,
)

private const val RELEASE_REPO = "dikodahan/couchy-launcher"
private const val RELEASE_APK = "Couchy-Launcher.apk"
private const val MAX_APK_BYTES = 200L * 1024 * 1024
private val versionPattern = Regex("(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})\\.(0|[1-9][0-9]{0,7})")
private val digestPattern = Regex("sha256:[a-f0-9]{64}")
private val releaseJson = Json { ignoreUnknownKeys = true }

internal fun parseAppUpdate(json: String, installed: String): AppUpdate? {
    val release = releaseJson.decodeFromString<GitHubRelease>(json)
    if (release.draft || release.prerelease || !release.tag.startsWith("v")) return null
    val version = release.tag.drop(1)
    val currentParts = versionPattern.matchEntire(installed)?.groupValues?.drop(1) ?: return null
    val candidateParts = versionPattern.matchEntire(version)?.groupValues?.drop(1) ?: return null
    val firstDifference = candidateParts.indices.firstOrNull { candidateParts[it] != currentParts[it] } ?: return null
    if (candidateParts[firstDifference].toInt() <= currentParts[firstDifference].toInt()) return null
    val url = "https://github.com/$RELEASE_REPO/releases/download/${release.tag}/$RELEASE_APK"
    val asset = release.assets.singleOrNull { it.name == RELEASE_APK } ?: throw IOException("Missing release APK")
    val digest = asset.digest?.takeIf(digestPattern::matches) ?: throw IOException("Invalid release APK checksum")
    if (asset.state != "uploaded" || asset.url != url || asset.size !in 1..MAX_APK_BYTES) {
        throw IOException("Invalid release APK metadata")
    }
    return AppUpdate(version, url, asset.size, digest.removePrefix("sha256:"))
}

internal class AppUpdates(context: Context) {
    private val app = context.applicationContext
    private val packageManager = app.packageManager
    private val installed: PackageInfo by lazy {
        packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    // The GitHub build cannot update APKs signed with a test or distribution key.
    fun isSupported(): Boolean = runCatching {
        installed.signerDigests() == setOf(RELEASE_CERTIFICATE)
    }.getOrDefault(false)

    suspend fun check(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.github.com/repos/$RELEASE_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json").build()
        metadataClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("Missing release metadata")
            if (source.request(MAX_METADATA_BYTES + 1)) throw IOException("Release metadata exceeds its size limit")
            parseAppUpdate(source.readUtf8(), installed.versionName.orEmpty())
        }
    }

    suspend fun download(update: AppUpdate, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(app.cacheDir, "app-updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Missing update cache")
        val apk = File(directory, RELEASE_APK)
        if (apk.length() == update.size && sha256(apk) == update.sha256 && isMatchingUpdate(apk, update)) {
            return@withContext apk
        }

        val temp = File.createTempFile("update-", ".apk", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        val job = currentCoroutineContext()
        try {
            openDownload(update.url).use { response ->
                val body = response.body ?: throw IOException("Missing APK")
                if (!response.isSuccessful || (body.contentLength() >= 0 && body.contentLength() != update.size)) {
                    throw IOException("Unexpected APK response")
                }
                body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        var lastPercent = -1
                        while (true) {
                            job.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > update.size) throw IOException("APK was larger than advertised")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            val percent = (size * 100 / update.size).toInt()
                            if (percent != lastPercent) {
                                progress(percent)
                                lastPercent = percent
                            }
                        }
                        if (size != update.size || digest.digest().hex() != update.sha256) {
                            throw IOException("APK checksum did not match")
                        }
                    }
                }
            }
            job.ensureActive()
            if (!isMatchingUpdate(temp, update)) throw IOException("APK does not match the installed app")
            if (!temp.renameTo(apk)) throw IOException("Could not save APK")
            apk
        } finally {
            temp.delete()
        }
    }

    @Suppress("DEPRECATION")
    fun installerIntent(apk: File): Intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        setDataAndType(
            FileProvider.getUriForFile(app, "${app.packageName}.updates", apk),
            "application/vnd.android.package-archive",
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun isMatchingUpdate(apk: File, update: AppUpdate): Boolean {
        val archive = packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return false
        val installedSigners = installed.signerDigests()
        return archive.packageName == app.packageName &&
            archive.versionName == update.version &&
            archive.longVersionCode > installed.longVersionCode &&
            installedSigners.isNotEmpty() && archive.signerDigests() == installedSigners
    }

    private fun openDownload(downloadUrl: String): Response {
        var url = downloadUrl.toHttpUrl()
        repeat(4) {
            if (!url.isHttps || url.port != 443 || url.host !in downloadHosts ||
                url.username.isNotEmpty() || url.password.isNotEmpty()
            ) throw IOException("Unexpected update download host")
            val response = downloadClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isRedirect) return response
            val location = response.use { it.header("Location") }
            url = location?.let(url::resolve) ?: throw IOException("Invalid APK redirect")
        }
        throw IOException("Too many APK redirects")
    }

    private fun PackageInfo.signerDigests(): Set<String> =
        signingInfo?.apkContentsSigners.orEmpty().map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).hex()
        }.toSet()

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        // Public SHA-256 fingerprint of the fork's GitHub release signing certificate.
        const val RELEASE_CERTIFICATE = "f322fa522b0ad0ef93cb36e07f3d37fb1dc98e7058f5ba4f712fb64696b535cf"
        const val MAX_METADATA_BYTES = 512L * 1024
        val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        val metadataClient = OkHttpClient.Builder().followRedirects(false)
            .callTimeout(12, TimeUnit.SECONDS).build()
        val downloadClient = metadataClient.newBuilder().readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.MINUTES).build()
    }
}

// A subclass avoids provider-class collisions with third-party manifest entries.
class UpdateFileProvider : FileProvider()
