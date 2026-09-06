package com.otakustream.core.database.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_entries ORDER BY addedAtEpochMs DESC")
    fun observeAll(): Flow<List<LibraryEntry>>

    @Query("SELECT EXISTS(SELECT 1 FROM library_entries WHERE mediaUrl = :mediaUrl)")
    fun observeInLibrary(mediaUrl: String): Flow<Boolean>

    @Query("SELECT status FROM library_entries WHERE mediaUrl = :mediaUrl")
    fun observeStatus(mediaUrl: String): Flow<String?>

    @Query("SELECT status FROM library_entries WHERE mediaUrl = :mediaUrl")
    suspend fun getStatus(mediaUrl: String): String?

    @Query("UPDATE library_entries SET status = :status WHERE mediaUrl = :mediaUrl")
    suspend fun setStatus(mediaUrl: String, status: String)

    @Upsert
    suspend fun upsert(entry: LibraryEntry)

    // Insert only if nothing holds this mediaUrl, reported by the rowid (-1 when the conflict
    // clause dropped it). For undoing a removal: a read-then-upsert leaves a window in which the
    // title is saved again between the check and the write, and the restore then overwrites the
    // newer entry — and whatever status was just set — with a snapshot from before the delete.
    // SQLite does the check and the insert in one statement; nothing can interleave.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: LibraryEntry): Long

    @Query("DELETE FROM library_entries WHERE mediaUrl = :mediaUrl")
    suspend fun delete(mediaUrl: String)
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedAtEpochMs DESC LIMIT 100")
    fun observeRecent(): Flow<List<WatchHistoryEntry>>

    @Query("SELECT DISTINCT episodeUrl FROM watch_history WHERE mediaUrl = :mediaUrl")
    fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>>

    // The name this media was last filed under. Used when replaying a url the app can't derive a
    // readable name from — torrent://<hash>/auto has none — so re-watching keeps the title it already
    // had instead of degrading to a path segment.
    @Query("SELECT mediaTitle FROM watch_history WHERE mediaUrl = :mediaUrl ORDER BY watchedAtEpochMs DESC, id DESC LIMIT 1")
    suspend fun lastTitleFor(mediaUrl: String): String?

    @Insert
    suspend fun insert(entry: WatchHistoryEntry)

    @Query("DELETE FROM watch_history")
    suspend fun clear()
}
