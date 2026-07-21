package com.otakustream.core.database.library

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// sourceId for plays that didn't come from a catalog source (local file, pasted URL,
// "Open with"). Rows carrying it route back to the player directly — there's no details
// page to open for them.
const val DIRECT_PLAY_SOURCE_ID = -1L

@Entity(
    tableName = "watch_history",
    // mediaUrl index speeds the per-title watched-episode lookup (observeWatchedEpisodeUrls) as
    // history grows; watchedAtEpochMs backs the recent-history ordering.
    indices = [Index(value = ["watchedAtEpochMs"]), Index(value = ["mediaUrl"])],
)
data class WatchHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val mediaUrl: String,
    val mediaTitle: String,
    val episodeUrl: String,
    val episodeName: String,
    val episodeNumber: Float,
    val watchedAtEpochMs: Long,
    val coverUrl: String? = null,
)
