package com.conreo.couchytv

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.xbot.tdlib.TdLib
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {
    @Test fun maintainedNativeLibraryLoadsAndExecutesWithoutAnAccount() {
        val response = TdLib.execute("""{"@type":"getOption","name":"version"}""")
        assertNotNull(response)
        assertTrue(response!!, response.contains("1.8.69"))
    }

    @Test fun firstRequestStartsAuthorizationUpdates() {
        val clientId = TdLib.createClientId()
        try {
            TdLib.requestAuthorizationState(clientId)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val response = TdLib.receive(0.25)?.let(::JSONObject) ?: continue
                if (response.optInt("@client_id") == clientId &&
                    response.optString("@type") == "updateAuthorizationState"
                ) {
                    assertEquals(
                        "authorizationStateWaitTdlibParameters",
                        response.getJSONObject("authorization_state").getString("@type"),
                    )
                    return
                }
            }
            fail("TDLib did not send its initial authorization update")
        } finally {
            TdLib.send(clientId, """{"@type":"close"}""")
        }
    }
}
