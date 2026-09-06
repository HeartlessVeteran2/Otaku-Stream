package com.otakustream.core.database.tracking

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
    private val prefs by lazy { openEncryptedPrefs(context, PREFS_FILE_NAME) }

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
        _token.value = null
        ioScope.launch { runCatching { prefs?.edit()?.remove(KEY_TOKEN)?.commit() } }.join()
    }

    companion object {
        // Referenced by the backup-rules XML so this file is excluded from backup.
        const val PREFS_FILE_NAME = "otaku_secure_prefs"
        private const val KEY_TOKEN = "anilist_access_token"
    }
}
