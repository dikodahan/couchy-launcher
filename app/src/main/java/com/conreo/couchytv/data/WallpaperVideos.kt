package com.conreo.couchytv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val videoExtensions = setOf(
    "mp4", "m4v", "mov", "webm", "mkv", "ts", "mts", "m2ts", "mpg", "mpeg", "vob", "flv", "3gp", "3g2", "avi", "ogv",
)

internal fun isWallpaperVideoDocument(mime: String, name: String): Boolean {
    if (mime.startsWith("video/", ignoreCase = true)) return true
    if (mime.isNotEmpty() && !mime.equals("application/octet-stream", ignoreCase = true)) return false
    return name.substringAfterLast('.', "").lowercase() in videoExtensions
}

internal val LauncherConfig.hasLocalVideoWallpaper: Boolean
    get() = useVideoWallpaper &&
        (videoFolderUri.isNotEmpty() || indexedVideoFiles.isNotEmpty() || videoUris.isNotEmpty() || videoUri.isNotEmpty())

internal val LauncherConfig.needsIndexedVideoAccess: Boolean
    get() = useVideoWallpaper && (isIndexedWallpaperFolder(videoFolderUri) || indexedVideoFiles.isNotEmpty())

internal fun Context.keepWallpaperAccess(uri: Uri) {
    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Read the selected directory in one provider query. Subfolders remain independent sources. */
internal suspend fun Context.wallpaperFolderMedia(tree: Uri, photo: Boolean = false): List<String> {
    if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree)) return emptyList()
    val directory = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME)
    val job = currentCoroutineContext()
    return buildList {
        contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                job.ensureActive()
                val mime = cursor.getString(1).orEmpty()
                val name = cursor.getString(2).orEmpty()
                val supported = if (photo) mime.startsWith("image/") ||
                    (mime in listOf("", "application/octet-stream") && isWallpaperPhoto(name))
                else isWallpaperVideoDocument(mime, name)
                if (!supported) continue
                val id = cursor.getString(0) ?: continue
                add(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())
            }
        }
    }.distinct()
}

internal suspend fun LauncherConfig.localWallpaperVideos(context: Context): List<String> = when {
    isSmbWallpaper(videoFolderUri) -> {
        val profile = SmbProfiles.matching(context, smbSetup)?.takeIf { it.verified }
        if (profile == null || Uri.parse(videoFolderUri).authority != profile.id) emptyList()
        else SmbConnection(profile).use { smb ->
            smb.list(videoFolderUri).filterNot { it.folder }.map { it.uri }
        }
    }
    videoUris.any(::isSmbWallpaper) -> {
        val profile = SmbProfiles.matching(context, smbSetup)?.takeIf { it.verified }
        videoUris.filter { isSmbWallpaper(it) && Uri.parse(it).authority == profile?.id }.distinct()
    }
    isIndexedWallpaperFolder(videoFolderUri) -> context.indexedWallpaperFolderUris(videoFolderUri, photo = false)
    videoFolderUri.isNotEmpty() -> context.wallpaperFolderMedia(Uri.parse(videoFolderUri))
    indexedVideoFiles.isNotEmpty() -> context.indexedWallpaperFileUris(indexedVideoFiles)
    videoUris.isNotEmpty() -> videoUris.filter { Uri.parse(it).scheme == "content" }.distinct()
    videoUri.isNotEmpty() -> listOf(videoUri)
    else -> emptyList()
}
