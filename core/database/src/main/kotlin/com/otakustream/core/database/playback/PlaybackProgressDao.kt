package com.otakustream.core.database.playback

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE mediaUrl = :mediaUrl")
    suspend fun get(mediaUrl: String): PlaybackProgressEntity?

    @Upsert
    suspend fun upsert(entity: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE mediaUrl = :mediaUrl")
    suspend fun delete(mediaUrl: String)
}
