package com.otakustream.core.database.stremio

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Keystore-backed store for the Stremio account session. The authKey is a bearer credential (it
// authorizes library reads/writes on the user's account), so it lives in EncryptedSharedPreferences
// like the AniList token — never in Room, never in a plaintext pref. The password is never stored;
// only the authKey returned by login is kept. Degrades to in-memory if the Keystore is unavailable.
@Singleton
class StremioAccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    // Loaded off the main thread (Keystore derivation + file read) so it can't stall cold start.
    private val _authKey = MutableStateFlow<String?>(null)
    val authKey: StateFlow<String?> = _authKey.asStateFlow()

    @Volatile
    var email: String? = null
        private set

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ioScope.launch {
            val savedKey = runCatching { prefs?.getString(KEY_AUTH, null) }.getOrNull()
            email = runCatching { prefs?.getString(KEY_EMAIL, null) }.getOrNull()
            if (savedKey != null) _authKey.compareAndSet(null, savedKey)
        }
    }

    fun save(authKey: String, email: String?) {
        runCatching {
            prefs?.edit()
                ?.putString(KEY_AUTH, authKey)
                ?.putString(KEY_EMAIL, email)
                ?.apply()
        }
        this.email = email
        _authKey.value = authKey
    }

    fun clear() {
        runCatching { prefs?.edit()?.remove(KEY_AUTH)?.remove(KEY_EMAIL)?.apply() }
        email = null
        _authKey.value = null
    }

    companion object {
        // Referenced by the backup-rules XML so this file is excluded from backup.
        const val PREFS_FILE_NAME = "stremio_account_prefs"
        private const val KEY_AUTH = "stremio_auth_key"
        private const val KEY_EMAIL = "stremio_email"
    }
}
