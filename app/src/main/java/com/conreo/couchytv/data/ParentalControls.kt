package com.conreo.couchytv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

internal data class ParentalState(
    val hasPin: Boolean,
    val enabled: Boolean,
    val lockedApps: Set<String>,
) {
    fun isLocked(pkg: String) = enabled && pkg in lockedApps
}

internal enum class PinResult { Accepted, Incorrect, Wait, Unavailable }
internal fun validPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

internal class ParentalControls(context: Context) {
    private val store = dataStore(File(context.applicationContext.noBackupFilesDir, "parental.preferences_pb"))
    val state = store.data.map {
        ParentalState(it[verifier] != null, it[enabled] == true, it[lockedApps].orEmpty())
    }

    suspend fun setup(pin: String): PinResult = protect {
        var result = PinResult.Unavailable
        store.edit { prefs ->
            if (prefs[verifier] == null && validPin(pin)) {
                prefs[verifier] = mac(pin, create = true)
                prefs[enabled] = true
                result = PinResult.Accepted
            }
        }
        result
    }

    suspend fun verify(pin: String) = authorize(pin) {}
    suspend fun enable(pin: String, value: Boolean) = authorize(pin) { it[enabled] = value }
    suspend fun delete(pin: String) = authorize(pin) { it.clear() }
    suspend fun lock(pin: String, pkg: String, value: Boolean) = authorize(pin) { prefs ->
        if (prefs[enabled] == true) {
            val packages = prefs[lockedApps].orEmpty()
            prefs[lockedApps] = if (value) packages + pkg else packages - pkg
        }
    }

    private suspend fun authorize(pin: String, action: (MutablePreferences) -> Unit): PinResult = protect {
        var result = PinResult.Unavailable
        store.edit { prefs ->
            val expected = prefs[verifier] ?: return@edit
            val now = System.currentTimeMillis()
            if ((prefs[retryAt] ?: 0L) > now) {
                result = PinResult.Wait
            } else if (validPin(pin) && MessageDigest.isEqual(expected.toByteArray(), mac(pin, create = false).toByteArray())) {
                prefs.remove(failures)
                prefs.remove(retryAt)
                action(prefs)
                result = PinResult.Accepted
            } else {
                val count = (prefs[failures] ?: 0) + 1
                if (count >= 5) {
                    prefs[failures] = 0
                    prefs[retryAt] = now + 30_000L
                    result = PinResult.Wait
                } else {
                    prefs[failures] = count
                    result = PinResult.Incorrect
                }
            }
        }
        result
    }

    private fun mac(pin: String, create: Boolean): String {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keys.getKey(KEY_ALIAS, null) as? SecretKey ?: run {
            check(create)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
            }.generateKey()
        }
        val hash = Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(pin.toByteArray())
        return android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
    }

    private suspend fun protect(block: suspend () -> PinResult): PinResult = withContext(Dispatchers.IO) {
        try {
            block().also { BetaDiagnostics.event("Parental control operation outcome=$it") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BetaDiagnostics.failure("Parental controls", error)
            PinResult.Unavailable
        }
    }

    private companion object {
        const val KEY_ALIAS = "couchy.parental-pin.v1"
        val verifier = stringPreferencesKey("verifier")
        val enabled = booleanPreferencesKey("enabled")
        val lockedApps = stringSetPreferencesKey("packages")
        val failures = intPreferencesKey("failed_attempts")
        val retryAt = longPreferencesKey("retry_at")
        private var instance: androidx.datastore.core.DataStore<Preferences>? = null

        @Synchronized
        fun dataStore(file: File) = instance ?: PreferenceDataStoreFactory.create { file }.also { instance = it }
    }
}
