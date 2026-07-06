package com.otakustream.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.otakustream.core.database.playback.PlaybackProgressDao
import com.otakustream.core.database.playback.PlaybackProgressEntity
import com.otakustream.core.database.skip.SkipSegmentDao
import com.otakustream.core.database.skip.SkipSegmentEntity

@Database(
    entities = [PlaybackProgressEntity::class, SkipSegmentEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun skipSegmentDao(): SkipSegmentDao
}
