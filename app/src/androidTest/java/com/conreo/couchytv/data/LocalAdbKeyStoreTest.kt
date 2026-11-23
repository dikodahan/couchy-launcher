package com.conreo.couchytv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.security.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAdbKeyStoreTest {
    @Test fun persistentNonexportableKeySignsTheAdbSha1Token() {
        val alias = InstrumentationRegistry.getInstrumentation().targetContext.packageName + ".test.local-adb"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val first = AndroidAdbAuthKey(alias)
            val packet = first.publicKeyPacket()
            assertArrayEquals(packet, AndroidAdbAuthKey(alias).publicKeyPacket())
            val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            assertNull(entry.privateKey.encoded)
            val token = ByteArray(20) { it.toByte() }
            val signed = first.signToken(token)
            assertEquals(256, signed.size)
            assertTrue(Signature.getInstance("NONEwithRSA").run {
                initVerify(entry.certificate.publicKey)
                update(AdbRsaAuth.SHA1_DIGEST_INFO); update(token); verify(signed)
            })
        } finally { store.deleteEntry(alias) }
    }
}
