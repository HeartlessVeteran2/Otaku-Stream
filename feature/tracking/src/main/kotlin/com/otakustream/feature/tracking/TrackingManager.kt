package com.otakustream.feature.tracking

import android.util.Log
import com.otakustream.core.database.tracking.toTrackerSeason
import com.otakustream.core.database.tracking.TrackingRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TrackingManager"

// Facade the rest of the app calls to sync watch progress — a no-op unless the user has both
// signed in to AniList and linked the media, so playback never depends on tracking state.
@Singleton
class TrackingManager @Inject constructor(
    private val trackingRepository: TrackingRepository,
    private val aniListClient: AniListClient,
) {
    // Called when an episode is actually watched to the end (see PlaybackCompletion). Reads the
    // viewer's current AniList entry and only writes a forward move — it never lowers progress and
    // never downgrades a COMPLETED/REPEATING entry, so rewatching an earlier episode can't erase
    // the user's completion. Whole episodes only; specials / episode 0 are skipped.
    //
    // `season` picks which AniList entry to write to, since AniList models each season as its own
    // media. It resolves to the season's own link when one exists and to the whole-series link
    // otherwise, so the episode number goes to the entry it actually counts against instead of
    // being pushed at season 1 forever.
    suspend fun onEpisodeWatched(
        mediaUrl: String,
        episodeNumber: Float,
        season: Int? = null,
    ) {
        // Stremio numbers specials as season 0, and they carry ordinary positive episode numbers
        // ("special 3"). Those don't count against any season's progress, so watching one must not
        // advance an AniList entry — without this, season 0 would resolve to the whole-series link
        // and push 3 at it. A source with no season data reports null, not 0, and is unaffected.
        if (season == 0) return
        val token = trackingRepository.getToken() ?: return
        val link = trackingRepository.getLink(mediaUrl, season.toTrackerSeason()) ?: return
        val episode = episodeNumber.toWholeEpisodeOrNull() ?: return
        runCatching {
            val current = aniListClient.fetchViewerListEntry(token, link.trackerMediaId)
            val update = decideProgressUpdate(current?.status, current?.progress ?: 0, episode)
            if (update != null) {
                aniListClient.saveMediaListEntry(
                    token = token,
                    mediaId = link.trackerMediaId,
                    status = update.status,
                    progress = update.progress,
                )
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "AniList progress update failed", it)
        }
    }

    // Called when the user explicitly changes a title's Library status (the Library is the single
    // source of truth). If the title is linked to AniList and the viewer is signed in, mirror the
    // new bucket up — Plan↔PLANNING, Watching↔CURRENT, Completed↔COMPLETED. Unlinked titles never
    // force-create an AniList entry, and a finished entry is never silently downgraded (see
    // decideStatusMirror), so this can't erase a completion.
    suspend fun onLibraryStatusChanged(mediaUrl: String, localStatus: String) {
        val token = trackingRepository.getToken() ?: return
        val link = trackingRepository.getLink(mediaUrl) ?: return
        val desired = libraryStatusToAniList(localStatus) ?: return
        runCatching {
            val current = aniListClient.fetchViewerListEntry(token, link.trackerMediaId)
            val status = decideStatusMirror(current?.status, desired) ?: return@runCatching
            aniListClient.saveMediaListEntry(
                token = token,
                mediaId = link.trackerMediaId,
                status = status,
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "AniList status mirror failed", it)
        }
    }
}
