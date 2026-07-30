package com.otakustream.core.torrent

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Subdirectory of cacheDir holding downloaded torrent data.
//
// Defined once and shared, because the writer (the reader's save path) and the reaper (the cache
// sweep) have to agree exactly. Two copies of this string that drift apart would mean sweeping a
// directory nothing writes to while the real one grows unbounded — a bug with no symptom until the
// disk fills.
const val TORRENT_CACHE_DIR = "torrents"

// Under cacheDir on purpose: the OS may reclaim it under storage pressure, which is correct for data
// that can always be re-fetched from the swarm.
fun torrentCacheDir(context: Context): File =
    File(context.cacheDir, TORRENT_CACHE_DIR).apply { mkdirs() }

// User-facing torrent preferences. Plain SharedPreferences, matching how the other source settings in
// this project are stored — nothing here is a credential, so there's no reason to reach for the
// encrypted store.
@Singleton
class TorrentSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // On by default only when the device can actually do it. A toggle that defaults to on and then
    // silently does nothing on a 32-bit device would be worse than one that reads as unavailable.
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var quotaBytes: Long
        get() = prefs.getLong(KEY_QUOTA, TorrentCachePolicy.DEFAULT_QUOTA_BYTES)
        set(value) = prefs.edit { putLong(KEY_QUOTA, value.coerceAtLeast(MIN_QUOTA_BYTES)) }

    // Defaults to on. Torrent streaming moves far more data than a direct stream — it fetches ahead
    // of the read head and, unlike a single HTTP response, talks to many peers — so defaulting to
    // metered-connection use would be spending the user's data allowance without asking.
    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

    private companion object {
        const val PREFS_NAME = "torrent_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_QUOTA = "quota_bytes"
        const val KEY_WIFI_ONLY = "wifi_only"

        // Below this the cache can't hold enough read-ahead to play anything smoothly, so allowing it
        // would just look like the feature is broken.
        const val MIN_QUOTA_BYTES = 256L * 1024 * 1024
    }
}
