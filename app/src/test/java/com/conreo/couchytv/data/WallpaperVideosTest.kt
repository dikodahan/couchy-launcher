package com.conreo.couchytv.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperVideosTest {
    @Test
    fun photoSelectionsAndFoldersSurviveBackupWithCorrectPermissionRequirements() {
        val local = LauncherConfig(
            useCustomWallpaper = true,
            photoFiles = listOf(
                "couchy-photo-file://external_primary/Pictures%2F/one.jpg",
                "couchy-photo-file://external_primary/Download%2F/two.png",
            ),
        )
        val localFolder = local.copy(photoFiles = emptyList(), photoFolderUri = "couchy-video-folder://external_primary/Pictures%2F")
        val smbFolder = localFolder.copy(photoFolderUri = "couchy-smb://home/Photos")
        assertTrue(local.needsIndexedPhotoAccess)
        assertTrue(localFolder.needsIndexedPhotoAccess)
        assertFalse(smbFolder.needsIndexedPhotoAccess)
        assertFalse(local.copy(useCustomWallpaper = false).needsIndexedPhotoAccess)
        for (selection in listOf(local, localFolder, smbFolder)) {
            assertEquals(selection, ConfigBackup.decode(ConfigBackup.encode(selection)))
        }
    }

    @Test
    fun smbServerAcceptsAHostOrAddressWithOptionalPort() {
        listOf("nas.local", "192.168.1.5", "nas.local:1445", "[fd00::2]:445", "nas.local/Shared Videos").forEach {
            assertTrue(it, SmbProfile.validServer(it))
        }
        listOf("smb://nas.local", "user@nas.local", "nas.local/videos/other", "nas.local?x=1",
            "nas.local:", "nas.local:0", "nas.local:65536", "nas.local#x").forEach {
            assertFalse(it, SmbProfile.validServer(it))
        }
    }

    @Test
    fun smbSetupAndSelectedFolderSurviveBackupWithoutAStoredPassword() {
        val config = LauncherConfig(
            useVideoWallpaper = true,
            videoFolderUri = "couchy-smb://home/Clips/Vacation",
            smbSetup = SmbSetup("example.local/Media", "Home videos", "viewer"),
        )
        val backup = ConfigBackup.encode(config)
        assertFalse(backup.contains("password", ignoreCase = true))
        assertEquals(config, ConfigBackup.decode(backup))
        assertTrue(config.hasLocalVideoWallpaper)
        assertFalse(config.needsIndexedVideoAccess)
    }

    @Test
    fun failedSmbAttemptCanBeRestoredForEditing() {
        val details = SmbSetup("smb://nas.local", "NAS", "viewer")
        assertTrue(details.isStorable())
        assertFalse(details.isValid())
        val attempt = SmbProfile(details, "test-password", verified = false)
        val local = Json.decodeFromString<SmbProfile>(Json.encodeToString(SmbProfile.serializer(), attempt))
        assertEquals(details, local.setup)
        assertEquals(attempt.password, local.password)
        assertFalse(local.verified)

        val backup = ConfigBackup.encode(LauncherConfig(smbSetup = local.setup))
        assertFalse(backup.contains(attempt.password))
        assertEquals(details, ConfigBackup.decode(backup).smbSetup)
    }

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
        val indexed = folder.copy(videoFolderUri = "couchy-video-folder://external_primary/Movies%2FAerial%2F")
        val indexedFiles = legacy.copy(
            videoUri = "",
            indexedVideoFiles = listOf(
                "couchy-video-file://external_primary/Movies%2FAerial%2F/one.mp4",
                "couchy-video-file://external_primary/Download%2F/two.mp4",
            ),
        )
        assertTrue(indexed.needsIndexedVideoAccess)
        assertTrue(indexedFiles.needsIndexedVideoAccess)
        assertFalse(indexedFiles.copy(useVideoWallpaper = false).needsIndexedVideoAccess)
        assertFalse(files.needsIndexedVideoAccess)
        assertTrue(isIndexedWallpaperFolder(indexed.videoFolderUri))
        assertFalse(isIndexedWallpaperFolder(folder.videoFolderUri))
        for (selection in listOf(files, folder, indexed, indexedFiles)) {
            val restored = ConfigBackup.decode(ConfigBackup.encode(selection))
            assertEquals(selection, restored)
            assertTrue(restored.hasLocalVideoWallpaper)
            assertFalse(restored.copy(useVideoWallpaper = false).hasLocalVideoWallpaper)
        }
    }
}
