package com.conreo.couchytv.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream

private val photoExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif", "bmp")
private const val MAX_PHOTO_BYTES = 64L * 1024 * 1024

internal fun isWallpaperPhoto(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in photoExtensions

internal const val WALLPAPER_PHOTO_INTERVAL_MS = 30_000L

internal val LauncherConfig.needsIndexedPhotoAccess: Boolean
    get() = useCustomWallpaper && (isIndexedWallpaperFolder(photoFolderUri) ||
        photoFiles.any { it.startsWith("couchy-photo-file://") })

internal suspend fun LauncherConfig.localWallpaperPhotos(context: Context): List<String> = when {
    isSmbWallpaper(photoFolderUri) -> {
        val profile = SmbProfiles.matching(context, smbSetup)?.takeIf { it.verified }
        if (profile == null || Uri.parse(photoFolderUri).authority != profile.id) emptyList()
        else SmbConnection(profile).use { smb ->
            smb.list(photoFolderUri, photo = true).filterNot { it.folder }.map { it.uri }
        }
    }
    isIndexedWallpaperFolder(photoFolderUri) -> context.indexedWallpaperFolderUris(photoFolderUri, photo = true)
    photoFolderUri.startsWith("content://") -> context.wallpaperFolderMedia(Uri.parse(photoFolderUri), photo = true)
    photoFiles.any(::isSmbWallpaper) -> {
        val profile = SmbProfiles.matching(context, smbSetup)?.takeIf { it.verified }
        photoFiles.filter { isSmbWallpaper(it) && Uri.parse(it).authority == profile?.id }.distinct()
    }
    else -> context.indexedWallpaperFileUris(photoFiles, photo = true) +
        photoFiles.filter { it.startsWith("content://") }.distinct()
}

/** Stage one slide at a time to bound storage for large folders and remote sources. */
internal suspend fun <T> Context.withWallpaperPhotoFile(source: String, setup: SmbSetup?, read: (File) -> T): T {
    val staging = File.createTempFile("wallpaper", ".image", cacheDir)
    try {
        suspend fun copyPhoto(input: InputStream) {
            val job = currentCoroutineContext()
            staging.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    job.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_PHOTO_BYTES) throw IOException("Wallpaper photo exceeds size limit")
                    output.write(buffer, 0, count)
                }
            }
        }
        if (isSmbWallpaper(source)) {
            val profile = SmbProfiles.matching(this, setup)?.takeIf { it.verified }
                ?: throw IOException("SMB setup is unverified")
            SmbConnection(profile).use { it.readPhoto(source, ::copyPhoto) }
        } else {
            val uri = Uri.parse(source)
            require(uri.scheme == "content")
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Wallpaper photo could not be opened")
            input.use { copyPhoto(it) }
        }
        currentCoroutineContext().ensureActive()
        return read(staging)
    } finally {
        staging.delete()
    }
}
