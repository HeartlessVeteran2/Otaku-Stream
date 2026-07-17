package com.otakustream.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.otakustream.core.database.library.LibraryDao
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.WatchHistoryDao
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.database.playback.PlaybackProgressDao
import com.otakustream.core.database.playback.PlaybackProgressEntity
import com.otakustream.core.database.scripted.ScriptedSourceDao
import com.otakustream.core.database.scripted.ScriptedSourceEntity
import com.otakustream.core.database.skip.SkipSegmentDao
import com.otakustream.core.database.skip.SkipSegmentEntity
import com.otakustream.core.database.stremio.StremioAddonEntity
import com.otakustream.core.database.stremio.StremioCatalogToggleEntity
import com.otakustream.core.database.stremio.StremioDao
import com.otakustream.core.database.stremio.StremioServerConfigEntity
import com.otakustream.core.database.tracking.TrackerLink
import com.otakustream.core.database.tracking.TrackerToken
import com.otakustream.core.database.tracking.TrackingDao

@Database(
    entities = [
        PlaybackProgressEntity::class,
        SkipSegmentEntity::class,
        ScriptedSourceEntity::class,
        LibraryEntry::class,
        WatchHistoryEntry::class,
        TrackerLink::class,
        TrackerToken::class,
        StremioAddonEntity::class,
        StremioServerConfigEntity::class,
        StremioCatalogToggleEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun skipSegmentDao(): SkipSegmentDao
    abstract fun scriptedSourceDao(): ScriptedSourceDao
    abstract fun libraryDao(): LibraryDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun trackingDao(): TrackingDao
    abstract fun stremioDao(): StremioDao
}
