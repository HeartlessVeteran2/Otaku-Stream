package com.otakustream.core.database.skip

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "skip_segments")
data class SkipSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaUrl: String,
    val startMs: Long,
    val endMs: Long,
    val type: String,
)
