package com.otakustream.core.download

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// The on-disk store every downloaded episode lives in.
//
// A singleton because it has to be: SimpleCache takes an exclusive lock on its directory, and a
// second instance over the same folder throws. Both the download manager (which writes) and the
// player (which reads) go through this one.
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class DownloadStore @Inject constructor(@ApplicationContext private val context: Context) {

    // App-private external storage, not filesDir. Episodes are hundreds of megabytes each, and
    // internal storage is the space the system complains about first — putting them there makes the
    // app look like it is hoarding. External app-private needs no permission on any supported API
    // level and is removed with the app, so nothing is left behind on uninstall.
    //
    // getExternalFilesDir returns null when external storage is unavailable (unmounted, or a device
    // in an odd state), so filesDir is the fallback rather than a crash.
    private val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY_NAME)

    val databaseProvider: DatabaseProvider by lazy { StandaloneDatabaseProvider(context) }

    // NoOpCacheEvictor, emphatically not an LRU evictor.
    //
    // This is the difference between a cache and a download. An LRU evictor would silently delete
    // the episode the user saved for a flight to make room for the one they saved after it — the
    // exact moment the feature is supposed to work. Nothing here is evicted except by the user
    // removing it, which means the store can grow without bound and that is the user's call to
    // make, not a policy this class should apply behind their back.
    val cache: Cache by lazy {
        SimpleCache(directory, NoOpCacheEvictor(), databaseProvider)
    }

    // What the downloads currently occupy, for the settings/library UI to show. Reported rather
    // than enforced, for the reason above.
    fun usedBytes(): Long = cache.cacheSpace

    private companion object {
        const val DIRECTORY_NAME = "episode-downloads"
    }
}
