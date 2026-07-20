package com.otakustream.core.database.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_entries ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<LibraryEntry>>

    @Query("SELECT EXISTS(SELECT 1 FROM library_entries WHERE mediaUrl = :mediaUrl)")
    fun observeInLibrary(mediaUrl: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entry: LibraryEntry)

    @Query("DELETE FROM library_entries WHERE mediaUrl = :mediaUrl")
    suspend fun delete(mediaUrl: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAtEpochMs DESC LIMIT 100")
    fun observeRecent(): Flow<List<WatchHistoryEntry>>

    @Query("SELECT DISTINCT episodeUrl FROM watch_history WHERE mediaUrl = :mediaUrl")
    fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>>

    @Insert
    suspend fun insert(entry: WatchHistoryEntry)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
