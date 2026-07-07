package com.otakustream.feature.tracking

import android.util.Log
import com.otakustream.core.database.tracking.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TrackingManager"

// Facade the rest of the app calls to sync watch progress — a no-op unless the user has both
// pasted an AniList token and linked the media, so playback never depends on tracking state.
@Singleton
class TrackingManager @Inject constructor(
    private val trackingRepository: TrackingRepository,
    private val aniListClient: AniListClient,
) {
    suspend fun onEpisodeWatched(mediaUrl: String, episodeNumber: Float) {
        val token = trackingRepository.getToken() ?: return
        val link = trackingRepository.getLink(mediaUrl) ?: return
        runCatching {
            aniListClient.saveProgress(token, link.trackerMediaId, episodeNumber.toInt().coerceAtLeast(1))
        }.onFailure { Log.w(TAG, "AniList progress update failed", it) }
    }
}
