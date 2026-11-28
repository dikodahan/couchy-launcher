package com.conreo.couchytv.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val INDEXED_FOLDER = "couchy-video-folder"
private val videos: Uri get() = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

internal data class IndexedWallpaperEntry(val source: String, val name: String, val path: List<String>, val folder: String)

internal fun isIndexedWallpaperFolder(source: String): Boolean = source.startsWith("$INDEXED_FOLDER://")

internal val wallpaperVideoPermission: String get() = wallpaperMediaPermission(photo = false)

internal fun wallpaperMediaPermission(photo: Boolean): String = when {
    Build.VERSION.SDK_INT < 33 -> Manifest.permission.READ_EXTERNAL_STORAGE
    photo -> Manifest.permission.READ_MEDIA_IMAGES
    else -> Manifest.permission.READ_MEDIA_VIDEO
}

internal fun wallpaperVideoPermissions(): Array<String> = wallpaperMediaPermissions(photo = false)

internal fun wallpaperMediaPermissions(photo: Boolean): Array<String> =
    if (Build.VERSION.SDK_INT >= 34) {
        arrayOf(wallpaperMediaPermission(photo), Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else arrayOf(wallpaperMediaPermission(photo))

internal fun Context.hasFullWallpaperMediaAccess(photo: Boolean): Boolean =
    checkSelfPermission(wallpaperMediaPermission(photo)) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasWallpaperVideoAccess(): Boolean = hasWallpaperMediaAccess(photo = false)

internal fun Context.hasWallpaperMediaAccess(photo: Boolean): Boolean = hasFullWallpaperMediaAccess(photo) ||
    (Build.VERSION.SDK_INT >= 34 &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

internal suspend fun Context.indexedWallpaperFolderUris(source: String, photo: Boolean): List<String> {
    if (!hasWallpaperMediaAccess(photo)) return emptyList()
    val folder = Uri.parse(source)
    val volume = folder.authority ?: return emptyList()
    val directory = folder.pathSegments.singleOrNull()?.takeIf(String::isNotEmpty) ?: return emptyList()
    val path = Build.VERSION.SDK_INT >= 29
    if (folder.scheme != INDEXED_FOLDER) return emptyList()
    val collection = if (path) {
        if (volume !in MediaStore.getExternalVolumeNames(this)) return emptyList()
        mediaCollection(volume, photo)
    } else {
        if (volume != "external") return emptyList()
        mediaCollection(volume, photo)
    }
    val column = if (path) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.BUCKET_ID
    val job = currentCoroutineContext()
    return buildList {
        contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID), "$column = ?", arrayOf(directory), null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    job.ensureActive()
                    add(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString())
                }
            }
    }
}

internal suspend fun Context.indexedWallpaperFiles(photo: Boolean): List<IndexedWallpaperEntry> =
    indexedWallpaperFilesBySource(photo).keys.sortedWith(compareBy({ it.name.lowercase() }, { it.source }))

internal suspend fun Context.indexedWallpaperFileUris(sources: List<String>, photo: Boolean = false): List<String> {
    val media = indexedWallpaperFilesBySource(photo).mapKeys { (entry, _) -> entry.source }
    return sources.distinct().mapNotNull(media::get)
}

private fun mediaCollection(volume: String, photo: Boolean): Uri =
    if (photo) MediaStore.Images.Media.getContentUri(volume) else MediaStore.Video.Media.getContentUri(volume)

@Suppress("DEPRECATION") // Android 9 has no RELATIVE_PATH column.
private suspend fun Context.indexedWallpaperFilesBySource(photo: Boolean): Map<IndexedWallpaperEntry, String> {
    if (!hasWallpaperMediaAccess(photo)) return emptyMap()
    val collection = if (photo) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else videos
    val hasRelativePath = Build.VERSION.SDK_INT >= 29
    val columns = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        if (hasRelativePath) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.BUCKET_ID,
        if (hasRelativePath) MediaStore.Video.Media.VOLUME_NAME else MediaStore.MediaColumns.DATA,
    )
    val job = currentCoroutineContext()
    return buildMap {
        contentResolver.query(collection, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                job.ensureActive()
                val name = cursor.getString(1)?.takeIf(String::isNotBlank) ?: continue
                val directory = cursor.getString(2).orEmpty()
                val volume = if (hasRelativePath) cursor.getString(3) ?: continue else "external"
                val path = if (hasRelativePath) {
                    val prefix = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) emptyList() else listOf(volume)
                    prefix + directory.split('/').filter(String::isNotEmpty)
                } else {
                    cursor.getString(3).orEmpty().substringBeforeLast('/', "")
                        .removePrefix(Environment.getExternalStorageDirectory().path).split('/').filter(String::isNotEmpty)
                }
                val uri = ContentUris.withAppendedId(mediaCollection(volume, photo), cursor.getLong(0)).toString()
                val source = Uri.Builder().scheme(if (photo) "couchy-photo-file" else "couchy-video-file").authority(volume)
                    .appendPath(directory).appendPath(name).build().toString()
                val folder = Uri.Builder().scheme(INDEXED_FOLDER).authority(volume).appendPath(directory).build().toString()
                put(IndexedWallpaperEntry(source, name, path, folder), uri)
            }
        }
    }
}
