package com.otakustream.core.database.stremio

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
import java.util.concurrent.atomic.AtomicInteger
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

    // Single-threaded, so the initial load, a save and a clear run in call order instead of
    // racing over one file. See EncryptedTokenStore for the three races this closes.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        ioScope.launch {
            val savedKey = runCatching { prefs?.getString(KEY_AUTH, null) }.getOrNull()
            email = runCatching { prefs?.getString(KEY_EMAIL, null) }.getOrNull()
            if (savedKey != null) _authKey.compareAndSet(null, savedKey)
        }
    }

    // Counts sign-ins. See clear() below — it is how the deferred drop tells the initial load
    // restoring the old key, which it must undo, from the user signing in again, which it must not.
    private val saves = AtomicInteger(0)

    // Held across "bump the counter and set the state" and across "drop the state and read the
    // counter", so those two pairs cannot interleave. Without it the counter is no guard at all: a
    // sign-in landing between clear()'s drop and its read is already counted by the time the read
    // happens, so the deferred half concludes nothing has changed and wipes the new key anyway —
    // the exact failure the counter is here to prevent, through a narrower window.
    private val sessionLock = Any()

    fun save(authKey: String, email: String?) {
        synchronized(sessionLock) {
            saves.incrementAndGet()
            this.email = email
            _authKey.value = authKey
        }
        // Queued on the same scope as clear(), so signing in during a sign-out's write cannot end
        // with memory and disk disagreeing about which one won.
        ioScope.launch {
            runCatching {
                prefs?.edit()
                    ?.putString(KEY_AUTH, authKey)
                    ?.putString(KEY_EMAIL, email)
                    ?.apply()
            }
        }
    }

    // suspend, and commit() rather than apply(), because this is a credential revocation — see
    // EncryptedTokenStore.clear() for the argument in full. Returns whether the authKey is actually
    // gone from disk, rather than discarding commit()'s answer for the one operation where it
    // matters most.
    suspend fun clear(): Boolean {
        val savesAtClear = synchronized(sessionLock) {
            email = null
            _authKey.value = null
            saves.get()
        }
        return ioScope.async {
            // Conditional, and it has to be. The repeat is here to undo the initial disk load,
            // which is queued on this same scope and would otherwise put the old key straight back
            // after the drop above. But unconditional it also undid a *sign-in*: save() sets memory
            // immediately and queues its write behind this body, so this ran first and nulled the
            // brand-new key — memory signed out, disk signed in, and the successful sign-in thrown
            // away until the next launch.
            if (saves.get() == savesAtClear) {
                email = null
                _authKey.value = null
            }
            runCatching { prefs?.edit()?.remove(KEY_AUTH)?.remove(KEY_EMAIL)?.commit() }
                .getOrNull() ?: false
        }.await()
    }

    companion object {
        // Referenced by the backup-rules XML so this file is excluded from backup.
        const val PREFS_FILE_NAME = "stremio_account_prefs"
        private const val KEY_AUTH = "stremio_auth_key"
        private const val KEY_EMAIL = "stremio_email"
    }
}
