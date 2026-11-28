package com.conreo.couchytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperVideosTest {
    @Test
    fun folderFiltersUsingMimeAndRecognizesVideosWithGenericMetadata() {
        assertTrue(isWallpaperVideoDocument("video/mp4", "video"))
        assertTrue(isWallpaperVideoDocument("application/octet-stream", "CLIP.MOV"))
        assertTrue(isWallpaperVideoDocument("", "clip.webm"))
        assertFalse(isWallpaperVideoDocument("vnd.android.document/directory", "folder.mp4"))
        assertFalse(isWallpaperVideoDocument("audio/webm", "audio.webm"))
        assertFalse(isWallpaperVideoDocument("application/octet-stream", "notes.txt"))
    }

    @Test
    fun legacyVideoAndNewWallpaperSourcesSurviveBackupRestore() {
        val legacy = ConfigBackup.decode(
            """{"categories":[{"id":"apps","name":"Apps"}],"useVideoWallpaper":true,"videoUri":"content://test/one"}""",
        )
        assertTrue(legacy.hasLocalVideoWallpaper)
        assertEquals("content://test/one", legacy.videoUri)

        val files = legacy.copy(videoUri = "", videoUris = listOf("content://test/one", "content://test/two"))
        val folder = files.copy(videoUris = emptyList(), videoFolderUri = "content://test/tree/movies")
        for (selection in listOf(files, folder)) {
            val restored = ConfigBackup.decode(ConfigBackup.encode(selection))
            assertEquals(selection, restored)
            assertTrue(restored.hasLocalVideoWallpaper)
            assertFalse(restored.copy(useVideoWallpaper = false).hasLocalVideoWallpaper)
        }
    }
}
