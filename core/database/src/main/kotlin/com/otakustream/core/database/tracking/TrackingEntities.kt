package com.otakustream.core.database.tracking

import androidx.room.Entity
import androidx.room.PrimaryKey

// Maps a local media item to the corresponding entry on an external tracker (AniList media id).
// sourceId records which installed source the mediaUrl belongs to, so the AniList detail can reopen
// a previously-chosen source directly (0 = unknown, e.g. links created before this column existed
// or via the manual link dialog, which just re-search on the AniList side).
@Entity(tableName = "tracker_links")
data class TrackerLink(
    @PrimaryKey val mediaUrl: String,
    val trackerMediaId: Long,
    val trackerTitle: String,
    val sourceId: Long = 0,
)

// Single-row table holding the user's pasted tracker access token (id is always 0).
@Entity(tableName = "tracker_tokens")
data class TrackerToken(
    @PrimaryKey val id: Int = 0,
    val accessToken: String,
)
