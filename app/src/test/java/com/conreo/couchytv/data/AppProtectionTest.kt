package com.conreo.couchytv.data

import com.conreo.couchytv.Actions
import com.conreo.couchytv.Actions.CloseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProtectionTest {
    @Test
    fun appClosingChecksForCacheOnlySupportAndNeverInvokesFullDataClear() {
        val command = Actions.closeCacheCommand("org.example.tv")!!
        assertTrue(command.startsWith("am force-stop --user current org.example.tv || exit 1"))
        assertTrue(command.contains("grep -Fq -- --cache-only || exit 1"))
        assertTrue(command.contains("android.permission.INTERNAL_DELETE_CACHE_FILES"))
        assertEquals(
            listOf("timeout -k 1 8 pm clear --user current --cache-only org.example.tv"),
            command.lines().filter { it.contains("pm clear") },
        )
        assertNull(Actions.closeCacheCommand("org.example.tv; echo unsafe"))
    }

    @Test
    fun closingRequiresBothAStopMarkerAndCacheSuccess() {
        assertEquals(CloseResult.Complete, Actions.closeCacheResult(AdbShellResult(0, "COUCHY_STOPPED\nSuccess\n")))
        assertEquals(CloseResult.Stopped, Actions.closeCacheResult(AdbShellResult(1, "COUCHY_STOPPED\n")))
        assertEquals(CloseResult.Unavailable, Actions.closeCacheResult(AdbShellResult(0, "Success\n")))
    }

    @Test
    fun pinLengthAndEffectiveLockMatchTheLauncherRules() {
        assertTrue(validPin("0000"))
        assertTrue(validPin("00000000"))
        assertFalse(validPin("123"))
        assertFalse(validPin("123456789"))
        assertFalse(validPin("123a"))
        val state = ParentalState(true, true, setOf("org.example.tv"))
        assertTrue(state.isLocked("org.example.tv"))
        assertFalse(state.copy(enabled = false).isLocked("org.example.tv"))
        assertFalse(state.isLocked("org.example.other"))
    }
}
