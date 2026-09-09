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

    // A list, not LIMIT 1. episodeUrl is not unique and cannot be: many sources hand back a signed
    // or rotating stream url, so downloading the same episode twice legitimately produces two rows
    // with different videoUrls. Taking the first would cancel one and leave the other running, with
    // the episode still showing as downloaded.
    @Query("SELECT * FROM downloads WHERE episodeUrl = :episodeUrl")
    suspend fun entriesForEpisode(episodeUrl: String): List<DownloadEntry>

    // Blocking on purpose. This is read by the downloader's data source factory, which Media3 calls
    // on its own executor — there is no coroutine to suspend in, and Room refuses main-thread
    // queries anyway, so a call from the wrong thread fails loudly rather than silently.
    @Query("SELECT headersJson FROM downloads WHERE videoUrl = :videoUrl LIMIT 1")
    fun headersJsonForBlocking(videoUrl: String): String?

    // Every row that stored headers, so a request whose url is not itself a download can still find
    // the download it belongs to. An HLS download fetches a playlist and then hundreds of segments,
    // and only the playlist's url is in this table — see DownloadHeaders.
    //
    // Whole rows rather than a LIKE prefix: matching an origin in SQL means escaping `%` and `_` in
    // a url, and getting that subtly wrong silently applies one host's headers to another. The table
    // holds one row per saved episode, so scanning it costs nothing measurable, and the comparison
    // happens in Kotlin where it can be tested.
    //
    // Blocking for the same reason as the query above.
    @Query("SELECT videoUrl, headersJson FROM downloads WHERE headersJson IS NOT NULL")
    fun headerRowsBlocking(): List<DownloadHeaderRow>

    // REPLACE rather than IGNORE: re-downloading an episode after removing it should pick up the
    // current title and cover, not silently keep whatever was stored the first time.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DownloadEntry)

    @Query("DELETE FROM downloads WHERE videoUrl = :videoUrl")
    suspend fun delete(videoUrl: String)
}
