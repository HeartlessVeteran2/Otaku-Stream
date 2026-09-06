package com.otakustream.core.database.stremio

import android.content.Context
import com.otakustream.core.database.security.openEncryptedPrefs
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
    private val prefs by lazy { openEncryptedPrefs(context, PREFS_FILE_NAME) }

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

    // suspend, and commit() rather than apply(), because this is a credential revocation.
    //
    // apply() returns before the write reaches disk. A process death in that window — and the
    // window is wide, since nothing forces the flush until the next lifecycle transition — brings
    // the app back with the credential still on file, and the init block above reads it straight
    // back in. A sign-out that silently un-signs-out is the one failure mode this store exists to
    // prevent, and AppearancePrefs already makes exactly this argument for a theme mode; a revoked
    // bearer token is the stronger case.
    //
    // In-memory state is dropped first, so the flow turns over on this frame and nothing can use
    // the credential while the disk write is in flight. The write runs on this store's own scope
    // rather than the caller's, so it survives the caller being cancelled — the user navigating
    // away the instant they tap sign out — and is joined so a caller that wants to know it landed
    // can wait for it.
    //
    // save() deliberately stays on apply(): losing a token that was just written costs a sign-in,
    // not a leaked credential, and it is on a path that would have to become suspend to gain
    // nothing.
    suspend fun clear() {
        email = null
        _authKey.value = null
        ioScope.launch {
            runCatching { prefs?.edit()?.remove(KEY_AUTH)?.remove(KEY_EMAIL)?.commit() }
        }.join()
    }

    companion object {
        // Referenced by the backup-rules XML so this file is excluded from backup.
        const val PREFS_FILE_NAME = "stremio_account_prefs"
        private const val KEY_AUTH = "stremio_auth_key"
        private const val KEY_EMAIL = "stremio_email"
    }
}
