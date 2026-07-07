package com.otakustream.core.database.library

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "library_entries")
data class LibraryEntry(
    @PrimaryKey val mediaUrl: String,
    val sourceId: Long,
    val title: String,
    val coverUrl: String?,
    val addedAtEpochMs: Long,
)
