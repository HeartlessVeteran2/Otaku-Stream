package com.otakustream.core.database.library

import androidx.room.Entity
import androidx.room.PrimaryKey

// Where the user is with a saved title. Kept as plain strings (not a Room enum converter) so the
// migration is a trivial column default and the values are stable across versions.
const val LIBRARY_STATUS_PLANNED = "PLANNED"
const val LIBRARY_STATUS_WATCHING = "WATCHING"
const val LIBRARY_STATUS_COMPLETED = "COMPLETED"

@Entity(tableName = "library_entries")
data class LibraryEntry(
    @PrimaryKey val mediaUrl: String,
    val sourceId: Long,
    val title: String,
    val coverUrl: String?,
    val addedAtEpochMs: Long,
    // Watch status bucket the Library groups by. New saves default to "Plan to watch"; starting an
    // episode auto-promotes to "Watching" (see LibraryRepositoryImpl.recordWatch).
    val status: String = LIBRARY_STATUS_PLANNED,
)
