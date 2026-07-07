package com.otakustream.core.database.library

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watch_history")
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
