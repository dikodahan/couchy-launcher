package com.conreo.couchytv.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val INDEXED_FOLDER = "couchy-video-folder"
private val videos: Uri get() = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

internal data class IndexedWallpaperFolder(val source: String, val name: String, val location: String)

internal fun isIndexedWallpaperFolder(source: String): Boolean = source.startsWith("$INDEXED_FOLDER://")

internal val wallpaperVideoPermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun wallpaperVideoPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 34) {
        arrayOf(wallpaperVideoPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else arrayOf(wallpaperVideoPermission)

internal fun Context.hasFullWallpaperVideoAccess(): Boolean =
    checkSelfPermission(wallpaperVideoPermission) == PackageManager.PERMISSION_GRANTED

internal fun Context.hasWallpaperVideoAccess(): Boolean = hasFullWallpaperVideoAccess() ||
    (Build.VERSION.SDK_INT >= 34 &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

internal suspend fun Context.indexedWallpaperFolders(): List<IndexedWallpaperFolder> {
    if (!hasWallpaperVideoAccess()) return emptyList()
    val path = Build.VERSION.SDK_INT >= 29
    val group = if (path) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.BUCKET_ID
    val columns = if (path) arrayOf(group, MediaStore.Video.Media.VOLUME_NAME)
        else arrayOf(group, MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    val job = currentCoroutineContext()
    val folders = linkedMapOf<String, IndexedWallpaperFolder>()
    contentResolver.query(videos, columns, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            job.ensureActive()
            val directory = cursor.getString(0) ?: continue
            if (directory.isEmpty()) continue
            val volume = if (path) cursor.getString(1) ?: continue else "external"
            val location = if (path) directory.trimEnd('/') else ""
            val label = if (path) location.substringAfterLast('/') else cursor.getString(1) ?: continue
            if (label.isBlank()) continue
            val source = Uri.Builder().scheme(INDEXED_FOLDER).authority(volume).appendPath(directory).build().toString()
            val description = if (path && volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) "$location · $volume" else location
            folders.putIfAbsent(source, IndexedWallpaperFolder(source, label, description))
        }
    }
    return folders.values.sortedWith(compareBy({ it.name.lowercase() }, { it.location }, { it.source }))
}

internal suspend fun Context.indexedWallpaperVideos(source: String): List<String> {
    if (!hasWallpaperVideoAccess()) return emptyList()
    val folder = Uri.parse(source)
    val volume = folder.authority ?: return emptyList()
    val directory = folder.pathSegments.singleOrNull()?.takeIf(String::isNotEmpty) ?: return emptyList()
    val path = Build.VERSION.SDK_INT >= 29
    if (folder.scheme != INDEXED_FOLDER) return emptyList()
    val collection = if (path) {
        if (volume !in MediaStore.getExternalVolumeNames(this)) return emptyList()
        MediaStore.Video.Media.getContentUri(volume)
    } else {
        if (volume != "external") return emptyList()
        videos
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
