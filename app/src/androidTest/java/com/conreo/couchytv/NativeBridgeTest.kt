package com.conreo.couchytv

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.xbot.tdlib.TdLib
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {
    @Test fun maintainedNativeLibraryLoadsAndExecutesWithoutAnAccount() {
        val response = TdLib.execute("""{"@type":"getOption","name":"version"}""")
        assertNotNull(response)
        assertTrue(response!!, response.contains("1.8.69"))
    }
}
