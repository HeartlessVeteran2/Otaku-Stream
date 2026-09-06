package com.otakustream.core.database.tracking

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// Prefer a link for the exact season, otherwise fall back to the whole-series link (season 0). That
// fallback is what makes per-season linking additive — a single-season show, a source with no season
// data, and every link made before seasons existed all resolve through season 0 exactly as they did
// before. Shared by the one-shot and observing variants as a constant so the sync path and the UI
// can't drift apart: the resolution rule has to be identical, or the row the screen shows isn't the
// row progress is pushed to.
private const val RESOLVE_LINK_QUERY = """
    SELECT * FROM tracker_links
    WHERE mediaUrl = :mediaUrl AND season IN (:season, $TRACKER_SEASON_WHOLE_SERIES)
    ORDER BY CASE WHEN season = :season THEN 0 ELSE 1 END
    LIMIT 1
"""

@Dao
interface TrackingDao {
    @Query(RESOLVE_LINK_QUERY)
    suspend fun getLink(mediaUrl: String, season: Int): TrackerLink?

    @Query(RESOLVE_LINK_QUERY)
    fun observeLink(mediaUrl: String, season: Int): Flow<TrackerLink?>

    // Reverse lookup: which local title an AniList media id was linked from, used to reopen it in
    // its source. A title can now hold one link per season, but each of those points at a *different*
    // AniList media id (AniList models seasons as separate entries), so a given id still normally
    // matches exactly one row. When it doesn't — the same id linked from two sources, or linked to
    // both a season and the whole series — the policy is the most recently created link wins, which
    // is what ORDER BY rowid DESC gives.
    @Query("SELECT * FROM tracker_links WHERE trackerMediaId = :trackerMediaId ORDER BY rowid DESC LIMIT 1")
    suspend fun getLinkByTrackerId(trackerMediaId: Long): TrackerLink?

    // Every title linked to one AniList entry, newest link first — the join that lets the app treat
    // the same show in four different sources as one show.
    //
    // Deliberately not the LIMIT 1 above, and the reason is the whole point: that query answers
    // "which title do I reopen for this AniList id", where exactly one answer is wanted and the most
    // recent link is the best guess. This one answers "who else has this show", where every answer
    // matters — each row is a source that can contribute streams for the episode being played.
    @Query("SELECT * FROM tracker_links WHERE trackerMediaId = :trackerMediaId ORDER BY rowid DESC")
    suspend fun getLinksByTrackerId(trackerMediaId: Long): List<TrackerLink>

    @Upsert
    suspend fun upsertLink(link: TrackerLink)

    // Removes exactly one season's link (the whole-series sentinel included) rather than the title's
    // links wholesale, so unlinking a season the user linked separately leaves the rest intact.
    @Query("DELETE FROM tracker_links WHERE mediaUrl = :mediaUrl AND season = :season")
    suspend fun deleteLink(mediaUrl: String, season: Int)

    @Query("SELECT * FROM tracker_tokens WHERE id = 0")
    suspend fun getToken(): TrackerToken?

    @Query("SELECT * FROM tracker_tokens WHERE id = 0")
    fun observeToken(): Flow<TrackerToken?>

    @Upsert
    suspend fun upsertToken(token: TrackerToken)

    @Query("DELETE FROM tracker_tokens")
    suspend fun clearToken()
}
