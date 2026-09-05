package com.otakustream.core.sources.stremio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Whether adult add-ons appear in the directory. Off until switched on, and it stays where it was
// put.
//
// Deliberately *not* the shape of StremioDirectorySettings next door, which caches the value in a
// StateFlow filled in by a startup coroutine. That pattern has a race — a save landing while the
// startup read is in flight can be overwritten by the stale read — which for a URL field is a
// visible annoyance and here would mean adult content reappearing after someone switched it off.
//
// So there is no cached state to get stale: both the directory and the screen read through, and the
// only copy of the answer is the one on disk. It is a single boolean read from a warm
// SharedPreferences file, not something worth a cache and a race.
//
// The default matters more than the mechanism. Someone who has not asked for pornography should
// never be shown it while looking for a way to watch anime, so this is false on a fresh install and
// only a deliberate act changes it.
@Singleton
class AdultContentSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Defaults to false on any failure, which is the safe direction: a preferences file that cannot
    // be read hides adult content rather than revealing it.
    suspend fun get(): Boolean = withContext(Dispatchers.IO) {
        runCatching { prefs.getBoolean(KEY_SHOW_ADULT, false) }.getOrDefault(false)
    }

    // Returns whether the value is now actually stored.
    //
    // commit(), not apply(), and its result is checked rather than discarded: the caller reloads the
    // directory straight after and that reload reads this store, so an asynchronous or failed write
    // would let the reload use the previous value — showing the user the opposite of what they just
    // chose, with a switch that says otherwise. Reporting the failure lets the caller keep the
    // screen honest instead.
    suspend fun set(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().putBoolean(KEY_SHOW_ADULT, enabled).commit() }.getOrDefault(false)
    }

    private companion object {
        const val PREFS_NAME = "stremio_directory_prefs"
        const val KEY_SHOW_ADULT = "show_adult_addons"
    }
}
