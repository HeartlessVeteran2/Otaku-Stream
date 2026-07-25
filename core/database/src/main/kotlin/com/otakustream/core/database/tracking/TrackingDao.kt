package com.otakustream.core.database.tracking

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingDao {
    // Resolution rule for both of these: prefer a link for the exact season, otherwise fall back to
    // the whole-series link (season 0). That fallback is what makes per-season linking additive —
    // a single-season show, a source with no season data, and every link made before seasons
    // existed all resolve through season 0 exactly as they did before.
    @Query(
        """
        SELECT * FROM tracker_links
        WHERE mediaUrl = :mediaUrl AND season IN (:season, $TRACKER_SEASON_WHOLE_SERIES)
        ORDER BY CASE WHEN season = :season THEN 0 ELSE 1 END
        LIMIT 1
        """,
    )
    suspend fun getLink(mediaUrl: String, season: Int): TrackerLink?

    @Query(
        """
        SELECT * FROM tracker_links
        WHERE mediaUrl = :mediaUrl AND season IN (:season, $TRACKER_SEASON_WHOLE_SERIES)
        ORDER BY CASE WHEN season = :season THEN 0 ELSE 1 END
        LIMIT 1
        """,
    )
    fun observeLink(mediaUrl: String, season: Int): Flow<TrackerLink?>

    // Every link for a title, for UI that shows which seasons are linked.
    @Query("SELECT * FROM tracker_links WHERE mediaUrl = :mediaUrl ORDER BY season")
    fun observeLinksFor(mediaUrl: String): Flow<List<TrackerLink>>

    // Reverse lookup: the source mapping remembered for an AniList media id. Most recent-ish by
    // rowid; there's normally only one.
    @Query("SELECT * FROM tracker_links WHERE trackerMediaId = :trackerMediaId ORDER BY rowid DESC LIMIT 1")
    suspend fun getLinkByTrackerId(trackerMediaId: Long): TrackerLink?

    @Upsert
    suspend fun upsertLink(link: TrackerLink)

    @Query("DELETE FROM tracker_links WHERE mediaUrl = :mediaUrl AND season = :season")
    suspend fun deleteLink(mediaUrl: String, season: Int)

    // Unlinking a title as a whole, regardless of how many seasons were linked individually.
    @Query("DELETE FROM tracker_links WHERE mediaUrl = :mediaUrl")
    suspend fun deleteAllLinksFor(mediaUrl: String)

    @Query("SELECT * FROM tracker_tokens WHERE id = 0")
    suspend fun getToken(): TrackerToken?

    @Query("SELECT * FROM tracker_tokens WHERE id = 0")
    fun observeToken(): Flow<TrackerToken?>

    @Upsert
    suspend fun upsertToken(token: TrackerToken)

    @Query("DELETE FROM tracker_tokens")
    suspend fun clearToken()
}
