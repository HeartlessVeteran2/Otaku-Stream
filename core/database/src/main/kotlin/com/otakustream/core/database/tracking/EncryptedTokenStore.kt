package com.otakustream.core.database.tracking

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

// Keystore-backed store for the AniList access token. Replaces the previous plaintext Room row:
// the token is a bearer credential, so it lives in EncryptedSharedPreferences (AES256, key held in
// the Android Keystore) and is excluded from cloud/device backup. Exposes a StateFlow so observers
// react to sign-in/sign-out without a database.
//
// Defensive: if the Keystore is somehow unavailable (rare OEM breakage), preference access is
// wrapped so token ops degrade to in-memory rather than crashing the app.
@Singleton
class EncryptedTokenStore @Inject constructor(
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

    // Starts null and loads asynchronously: `prefs` (lazy) forces EncryptedSharedPreferences.create,
    // which derives the Keystore master key and reads a file — that must not run on the main thread
    // while Hilt builds this singleton. Observers treat null as "signed out", so a brief
    // signed-out→signed-in flip on cold start is acceptable.
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ioScope.launch {
            val saved = runCatching { prefs?.getString(KEY_TOKEN, null) }.getOrNull() ?: return@launch
            // compareAndSet so a token saved between construction and this load completing (a
            // sign-in racing cold start) isn't clobbered by the stale disk value.
            _token.compareAndSet(null, saved)
        }
    }

    fun current(): String? = _token.value

    fun save(token: String) {
        runCatching { prefs?.edit()?.putString(KEY_TOKEN, token)?.apply() }
        _token.value = token
    }

    fun clear() {
        runCatching { prefs?.edit()?.remove(KEY_TOKEN)?.apply() }
        _token.value = null
    }

    companion object {
        // Referenced by the backup-rules XML so this file is excluded from backup.
        const val PREFS_FILE_NAME = "otaku_secure_prefs"
        private const val KEY_TOKEN = "anilist_access_token"
    }
}
