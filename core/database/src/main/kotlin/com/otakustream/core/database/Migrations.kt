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

// v7 → v8: add the mangayomi_sources table backing installed Mangayomi/AnymeX JS extensions.
// Columns/types must match MangayomiSourceEntity exactly so Room's schema validation passes.
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mangayomi_sources` (" +
                "`id` INTEGER NOT NULL, " +
                "`repoUrl` TEXT NOT NULL, " +
                "`sourceCodeUrl` TEXT NOT NULL, " +
                "`scriptContent` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`lang` TEXT NOT NULL, " +
                "`baseUrl` TEXT NOT NULL, " +
                "`iconUrl` TEXT, " +
                "`version` TEXT NOT NULL, " +
                "`isNsfw` INTEGER NOT NULL, " +
                "`itemType` INTEGER NOT NULL, " +
                "`sourceCodeLanguage` INTEGER NOT NULL, " +
                "`prefsJson` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
    }
}
