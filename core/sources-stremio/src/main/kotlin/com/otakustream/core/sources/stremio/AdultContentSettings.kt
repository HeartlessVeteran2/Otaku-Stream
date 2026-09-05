package com.otakustream.core.sources.stremio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Whether adult add-ons appear in the directory. Off until switched on, and it stays where it was
// put.
//
// Same shape as StremioDirectorySettings next door, and for the same reasons: one non-sensitive
// value, kept in SharedPreferences rather than paying for a schema migration, read off the main
// thread because touching prefs forces a file read during DI graph setup.
//
// The default matters more than the mechanism. Someone who has not asked for pornography should
// never be shown it while looking for a way to watch anime, so this starts false on a fresh install
// and only a deliberate act changes it.
@Singleton
class AdultContentSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _showAdult = MutableStateFlow(false)
    val showAdult: StateFlow<Boolean> = _showAdult.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A save landing while the startup read is still in flight must win. Publishing the stale read
    // over it would flip the switch back under the user — and this is the one setting where a
    // surprise flip in that direction shows them something they turned off.
    @Volatile
    private var locallyModified = false

    init {
        ioScope.launch {
            val stored = runCatching { prefs.getBoolean(KEY_SHOW_ADULT, false) }.getOrDefault(false)
            if (!locallyModified) _showAdult.value = stored
        }
    }

    // Authoritative, unlike the flow, which starts false and fills in asynchronously. A caller that
    // read the flow before it settled would hide adult add-ons on every cold start even with the
    // setting on — the flow is for driving the UI, this is for deciding.
    suspend fun get(): Boolean = withContext(Dispatchers.IO) {
        runCatching { prefs.getBoolean(KEY_SHOW_ADULT, false) }.getOrDefault(false)
    }

    // commit(), not apply(): the caller reloads the directory straight after, and that reload reads
    // this store. An asynchronous write would let the reload race the save and filter on the
    // previous value, showing the user the opposite of what they just chose.
    suspend fun set(enabled: Boolean) {
        locallyModified = true
        _showAdult.value = enabled
        withContext(Dispatchers.IO) {
            runCatching { prefs.edit().putBoolean(KEY_SHOW_ADULT, enabled).commit() }
        }
    }

    private companion object {
        const val PREFS_NAME = "stremio_directory_prefs"
        const val KEY_SHOW_ADULT = "show_adult_addons"
    }
}
