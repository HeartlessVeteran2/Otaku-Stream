package com.otakustream.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// v6 → v7: index watch_history.mediaUrl (the per-title watched-episode lookup). First explicit
// migration — from here upgrades ship a Migration rather than wiping data (see docs/architecture.md).
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_history_mediaUrl` ON `watch_history` (`mediaUrl`)")
    }
}
