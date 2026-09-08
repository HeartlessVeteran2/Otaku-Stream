package com.otakustream.feature.tracking

import android.util.Log
import com.otakustream.core.database.tracking.toTrackerSeason
import com.otakustream.core.sources.api.UiMessages
import com.otakustream.core.database.tracking.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TrackingManager"

// Facade the rest of the app calls to sync watch progress — a no-op unless the user has both
// signed in to AniList and linked the media, so playback never depends on tracking state.
//
// An interface, following LibraryRepository's shape in core/database, for one reason: the two
// ViewModels that depend on it could not be unit-tested while it was a concrete class reaching
// AniList over the network. That is not hypothetical — the undo guard that shipped in #135 was
// wrong twice, and both versions were untestable for exactly this reason.
//
// The interface carries only what callers use. Everything else about the sync — which link a
// season resolves to, how a token failure is handled — stays private to the implementation.
interface TrackingManager {

    // Called when an episode is actually watched to the end (see PlaybackCompletion).
    suspend fun onEpisodeWatched(mediaUrl: String, episodeNumber: Float, season: Int? = null)

    // Called when the user changes a title's status locally, to mirror it up.
    suspend fun onLibraryStatusChanged(mediaUrl: String, localStatus: String, season: Int? = null)
}

@Singleton
class TrackingManagerImpl @Inject constructor(
    private val trackingRepository: TrackingRepository,
    private val aniListClient: AniListClient,
) : TrackingManager {
    // Called when an episode is actually watched to the end (see PlaybackCompletion). Reads the
    // viewer's current AniList entry and only writes a forward move — it never lowers progress and
    // never downgrades a COMPLETED/REPEATING entry, so rewatching an earlier episode can't erase
    // the user's completion. Whole episodes only; specials / episode 0 are skipped.
    //
    // `season` picks which AniList entry to write to, since AniList models each season as its own
    // media. It resolves to the season's own link when one exists and to the whole-series link
    // otherwise, so the episode number goes to the entry it actually counts against instead of
    // being pushed at season 1 forever.
    override suspend fun onEpisodeWatched(
        mediaUrl: String,
        episodeNumber: Float,
        season: Int?,
    ) {
        // Stremio numbers specials as season 0, and they carry ordinary positive episode numbers
        // ("special 3"). Those don't count against any season's progress, so watching one must not
        // advance an AniList entry — without this, season 0 would resolve to the whole-series link
        // and push 3 at it. A source with no season data reports null, not 0, and is unaffected.
        if (season == 0) return
        val token = trackingRepository.getToken() ?: return
        val link = trackingRepository.getLink(mediaUrl, season.toTrackerSeason()) ?: return
        val episode = episodeNumber.toWholeEpisodeOrNull() ?: return
        sync("AniList progress update", token) {
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
        }
    }

    // Called when the user explicitly changes a title's Library status (the Library is the single
    // source of truth). If the title is linked to AniList and the viewer is signed in, mirror the
    // new bucket up — Plan↔PLANNING, Watching↔CURRENT, Completed↔COMPLETED. Unlinked titles never
    // force-create an AniList entry, and a finished entry is never silently downgraded (see
    // decideStatusMirror), so this can't erase a completion.
    // `season` resolves which AniList entry the new status lands on, exactly as onEpisodeWatched
    // already did — the season's own link when one exists, the whole-series link otherwise.
    //
    // It used to call getLink(mediaUrl) with no season, and the resolution query reads
    // `season IN (:season, 0)`, so passing the whole-series sentinel matched *only* the
    // whole-series link. A show linked per-season therefore resolved to null here and returned
    // early: watching its episodes updated AniList, and marking it Completed did nothing at all.
    // Two paths, the same title, opposite outcomes, no error either way.
    //
    // Callers with no season concept — the Library screen, where status is per-title — pass
    // nothing and land on the whole-series link, which is what they were getting before.
    override suspend fun onLibraryStatusChanged(mediaUrl: String, localStatus: String, season: Int?) {
        val token = trackingRepository.getToken() ?: return
        val link = trackingRepository.getLink(mediaUrl, season.toTrackerSeason()) ?: return
        val desired = libraryStatusToAniList(localStatus) ?: return
        sync("AniList status mirror", token) {
            val current = aniListClient.fetchViewerListEntry(token, link.trackerMediaId)
            val status = decideStatusMirror(current?.status, desired) ?: return@sync
            aniListClient.saveMediaListEntry(
                token = token,
                mediaId = link.trackerMediaId,
                status = status,
            )
        }
    }

    // One place where a background sync failure is decided on, because there are two callers and
    // they were drifting: both logged and dropped everything, including a rejected token.
    //
    // A dead credential is not a transient failure and must not be retried forever in silence. The
    // app clears it, which is the only honest thing it can do — Settings stops claiming to be
    // signed in and offers to sign in again — and says so, because a sign-out the user did not ask
    // for is exactly the kind of change that has to be announced rather than discovered.
    // Takes the token the work was done with, so a rejection can be checked against the token
    // still in use. Requests outlive the credential they were sent with: sign in again while an
    // older sync is in flight and its 401 lands after the new token is stored, and an
    // unconditional clear would revoke a credential nothing had rejected — signing the user out
    // moments after they signed in, with no explanation.
    private suspend fun sync(what: String, token: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { failure ->
            when (syncFailureAction(failure)) {
                SyncFailureAction.Rethrow -> throw failure
                SyncFailureAction.SignOut -> {
                    Log.w(TAG, "$what: AniList rejected the token", failure)
                    if (trackingRepository.clearTokenIfCurrent(token)) {
                        UiMessages.show(
                            "Your AniList sign-in expired. Sign in again in Settings to resume syncing.",
                        )
                    } else {
                        // A newer token is already in use; this rejection was about the old one.
                        Log.i(TAG, "$what: the rejected token is no longer current, leaving sign-in alone")
                    }
                }
                SyncFailureAction.LogAndIgnore -> Log.w(TAG, "$what failed", failure)
            }
        }
    }
}
