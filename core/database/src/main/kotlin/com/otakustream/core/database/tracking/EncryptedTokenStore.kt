package com.otakustream.core.database.tracking

import android.content.Context
import com.otakustream.core.database.security.openEncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

    // Every touch of this store's disk state runs here, one at a time, in the order it was asked
    // for. Same shape AppearancePrefs uses, and it is what makes the initial load, a save and a
    // clear mutually exclusive instead of three coroutines racing over one file.
    //
    // Three concrete races it closes, all of which end with a revoked credential still usable:
    //  - the initial load finishing after clear() nulled the flow, putting the token straight back
    //    in memory via compareAndSet;
    //  - a save landing between clear() dropping memory and clear()'s commit, leaving memory
    //    signed in and disk signed out;
    //  - a clear queued behind a save whose write had not happened yet.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

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
        _token.value = token
        // Queued on the same single-threaded scope as clear(), so the two can never invert. apply()
        // rather than commit(): losing a token that was just written costs a sign-in, not a leaked
        // credential.
        ioScope.launch { runCatching { prefs?.edit()?.putString(KEY_TOKEN, token)?.apply() } }
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
    // In-memory state is dropped twice on purpose: once here, so the flow turns over on this frame
    // and nothing can use the credential while the write is in flight, and once inside the scope,
    // where it is ordered against the initial load and any queued save. Only the second is enough
    // for correctness; only the first is fast enough for the UI.
    //
    // Returns whether the credential is actually gone from disk. commit() reports failure by
    // returning false and the earlier version discarded it, so a clear that did not happen was
    // indistinguishable from one that did — for the one operation where that distinction is the
    // whole point.
    suspend fun clear(): Boolean {
        _token.value = null
        return ioScope.async {
            _token.value = null
            runCatching { prefs?.edit()?.remove(KEY_TOKEN)?.commit() }.getOrNull() ?: false
        }.await()
    }

    // Clears only if the token being revoked is still the one in use.
    //
    // The AniList sync path signs the user out when a request comes back rejected, and requests
    // outlive the token they were sent with: sign in again while an older request is in flight, and
    // that request's 401 arrives after the new credential is stored. An unconditional clear then
    // revokes a token that was never rejected, and the user is signed out moments after signing in
    // with no explanation at all.
    //
    // Compared inside the write scope so the comparison and the removal cannot be separated by a
    // save landing between them.
    suspend fun clearIfCurrent(expected: String): Boolean = ioScope.async {
        if (_token.value != expected) return@async false
        _token.value = null
        runCatching { prefs?.edit()?.remove(KEY_TOKEN)?.commit() }
        true
    }.await()

    companion object {
        // Referenced by the backup-rules XML so this file is excluded from backup.
        const val PREFS_FILE_NAME = "otaku_secure_prefs"
        private const val KEY_TOKEN = "anilist_access_token"
    }
}
