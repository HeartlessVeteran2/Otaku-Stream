package com.otakustream.core.database.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    // Newest request first: the Downloads list is a record of what the user asked for, and the most
    // recent ask is the one they are most likely looking for.
    @Query("SELECT * FROM downloads ORDER BY requestedAtEpochMs DESC")
    fun observeAll(): Flow<List<DownloadEntry>>

    @Query("SELECT * FROM downloads WHERE mediaUrl = :mediaUrl")
    fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>>

    @Query("SELECT * FROM downloads WHERE episodeUrl = :episodeUrl LIMIT 1")
    suspend fun entryForEpisode(episodeUrl: String): DownloadEntry?

    // REPLACE rather than IGNORE: re-downloading an episode after removing it should pick up the
    // current title and cover, not silently keep whatever was stored the first time.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DownloadEntry)

    @Query("DELETE FROM downloads WHERE videoUrl = :videoUrl")
    suspend fun delete(videoUrl: String)
}
