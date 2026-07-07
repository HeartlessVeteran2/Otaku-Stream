package com.otakustream.core.database.tracking

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackingDao {
    @Query("SELECT * FROM tracker_links WHERE mediaUrl = :mediaUrl")
    suspend fun getLink(mediaUrl: String): TrackerLink?

    @Query("SELECT * FROM tracker_links WHERE mediaUrl = :mediaUrl")
    fun observeLink(mediaUrl: String): Flow<TrackerLink?>

    @Upsert
    suspend fun upsertLink(link: TrackerLink)

    @Query("DELETE FROM tracker_links WHERE mediaUrl = :mediaUrl")
    suspend fun deleteLink(mediaUrl: String)

    @Query("SELECT * FROM tracker_tokens WHERE id = 0")
    suspend fun getToken(): TrackerToken?

    @Query("SELECT * FROM tracker_tokens WHERE id = 0")
    fun observeToken(): Flow<TrackerToken?>

    @Upsert
    suspend fun upsertToken(token: TrackerToken)

    @Query("DELETE FROM tracker_tokens")
    suspend fun clearToken()
}
