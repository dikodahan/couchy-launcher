package com.conreo.couchytv.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdatesTest {
    private val sha = "2".repeat(64)

    private fun release(
        tag: String = "v1.10.0",
        prerelease: Boolean = false,
        draft: Boolean = false,
        assetName: String = "Couchy-Launcher.apk",
        url: String = "https://github.com/dikodahan/couchy-launcher/releases/download/$tag/$assetName",
        digest: String = "sha256:$sha",
        size: Long = 99_387_302L,
    ) = """
        {
          "tag_name": "$tag", "draft": $draft, "prerelease": $prerelease,
          "assets": [{"name": "$assetName", "state": "uploaded", "size": $size,
                      "browser_download_url": "$url", "digest": "$digest"}]
        }
    """.trimIndent()

    @Test
    fun parsesNumericReleaseVersionsAndUsesAVersionSpecificApk() {
        val expected = AppUpdate(
            "1.10.0", "https://github.com/dikodahan/couchy-launcher/releases/download/v1.10.0/Couchy-Launcher.apk",
            99_387_302L, sha,
        )
        assertEquals(expected, parseAppUpdate(release(), "1.9.9"))
        assertNull(parseAppUpdate(release(), "1.10.0"))
        assertNull(parseAppUpdate(release(), "2.0.0"))
    }

    @Test
    fun keepsOptionalNotesFromTheSameValidatedRelease() {
        val notes = "## Changes\n- One\n- Two"
        val withNotes = release().replace("\"assets\"", "\"body\": \"## Changes\\n- One\\n- Two\", \"assets\"")
        val nullNotes = release().replace("\"assets\"", "\"body\": null, \"assets\"")
        assertEquals(notes, parseAppUpdate(withNotes, "1.9.9")?.releaseNotes)
        assertEquals("", parseAppUpdate(nullNotes, "1.9.9")?.releaseNotes)
        assertEquals("", parseAppUpdate(release(), "1.9.9")?.releaseNotes)
    }

    @Test
    fun ignoresPreviewAndDraftReleases() {
        assertNull(parseAppUpdate(release(prerelease = true), "1.1.0"))
        assertNull(parseAppUpdate(release(draft = true), "1.1.0"))
        assertNull(parseAppUpdate(release(tag = "v2.0.0-rc.1"), "1.1.0"))
    }

    @Test
    fun reportsMissingOrInvalidAssetsForANewerRelease() {
        assertThrows(IOException::class.java) { parseAppUpdate(release(assetName = "random.apk"), "1.1.0") }
        assertThrows(IOException::class.java) { parseAppUpdate(release(url = "https://example.org/Couchy-Launcher.apk"), "1.1.0") }
        assertThrows(IOException::class.java) { parseAppUpdate(release(digest = "sha256:corrupt"), "1.1.0") }
        assertThrows(IOException::class.java) { parseAppUpdate(release(size = 0), "1.1.0") }
        assertThrows(IOException::class.java) { parseAppUpdate(release(size = 800_000_000), "1.1.0") }
    }
}
