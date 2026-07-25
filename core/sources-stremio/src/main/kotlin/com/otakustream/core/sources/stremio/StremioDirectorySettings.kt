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

// An optional user-supplied add-on list URL, browsed alongside the two built-in Stremio collections
// (issue #10). SharedPreferences rather than a Room column: it's one non-sensitive string, and the
// repo already keeps this kind of setting in prefs (CloudflareSettings, PlayerSettingsPrefs) instead
// of paying for a schema migration.
@Singleton
class StremioDirectorySettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val _customListUrl = MutableStateFlow<String?>(null)
    val customListUrl: StateFlow<String?> = _customListUrl.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Set once the user has saved something this process. The startup read must not publish its
    // (by then stale) value over a save that landed while the read was still in flight — that would
    // revert the field in front of the user despite the newer value being the one on disk.
    @Volatile
    private var locallyModified = false

    init {
        // Off the main thread: touching `prefs` forces the file read, and this is constructed during
        // DI graph setup. Starts null and fills in shortly after.
        ioScope.launch {
            val stored = runCatching { prefs.getString(KEY_CUSTOM_LIST_URL, null) }.getOrNull()
            if (!locallyModified) _customListUrl.value = stored
        }
    }

    // Reads the store rather than the flow. The flow starts null and fills in asynchronously, so a
    // caller that fetched before it settled would silently skip a saved list on every cold start —
    // this is suspend and authoritative instead, and the flow exists only to drive the UI.
    suspend fun get(): String? = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(KEY_CUSTOM_LIST_URL, null) }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    // Blank clears it, so the UI's text field doubles as the remove control.
    //
    // suspend, and commit() rather than apply(): the caller re-fetches the directory immediately
    // after saving, and that fetch reads this store. An asynchronous write would let the reload race
    // the save and use the *previous* URL — showing the user the old list right after they changed it.
    // Returning only once the value is durable makes the sequence deterministic.
    suspend fun set(url: String?) {
        val cleaned = url?.trim()?.takeIf { it.isNotEmpty() }
        locallyModified = true
        _customListUrl.value = cleaned
        withContext(Dispatchers.IO) {
            runCatching {
                prefs.edit().apply {
                    if (cleaned == null) remove(KEY_CUSTOM_LIST_URL) else putString(KEY_CUSTOM_LIST_URL, cleaned)
                }.commit()
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "stremio_directory_prefs"
        const val KEY_CUSTOM_LIST_URL = "custom_addon_list_url"
    }
}
