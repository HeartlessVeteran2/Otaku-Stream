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

// v8 → v9: add tracker_links.sourceId so an AniList entry can remember which installed source it was
// watched from (0 = unknown for pre-existing rows). Must match TrackerLink's generated column exactly.
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tracker_links` ADD COLUMN `sourceId` INTEGER NOT NULL DEFAULT 0")
    }
}

// v9 → v10: add library_entries.status so the Library can group saved titles by watch status
// (Plan to watch / Watching / Completed). Pre-existing saves default to PLANNED. Must match
// LibraryEntry's generated column exactly.
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `library_entries` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'PLANNED'")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Index name must match what Room generates for Index("addedAtEpochMs"), or the schema
        // validation on open will fail.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_entries_addedAtEpochMs` ON `library_entries` (`addedAtEpochMs`)")
    }
}

// v11 → v12: tracker_links gains a `season` column and a composite (mediaUrl, season) primary key,
// so a multi-season series can link each season to its own AniList media entry (issue #9).
//
// A table rebuild rather than an ALTER: SQLite cannot change a primary key in place. Every existing
// row is preserved at season = 0 ("whole series"), which is also the fallback lookups land on — so
// links made before this migration keep behaving exactly as they did.
//
// The CREATE must match Room's generated schema for the new entity exactly (column order, types,
// NOT NULL, and the composite PK), or validation fails when the database is next opened.
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tracker_links_new` (
                `mediaUrl` TEXT NOT NULL,
                `trackerMediaId` INTEGER NOT NULL,
                `trackerTitle` TEXT NOT NULL,
                `sourceId` INTEGER NOT NULL,
                `season` INTEGER NOT NULL,
                PRIMARY KEY(`mediaUrl`, `season`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `tracker_links_new` (`mediaUrl`, `trackerMediaId`, `trackerTitle`, `sourceId`, `season`)
            SELECT `mediaUrl`, `trackerMediaId`, `trackerTitle`, `sourceId`, 0 FROM `tracker_links`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `tracker_links`")
        db.execSQL("ALTER TABLE `tracker_links_new` RENAME TO `tracker_links`")
    }
}

// v12 → v13: a `downloads` table holding what a downloaded episode is called and what it belongs
// to. Deliberately not its state or progress — Media3's own download index owns those, and it is
// the component that writes them, so a second copy here would drift. What Media3 has no notion of
// is a title, which is why a Downloads list built on it alone could only show urls.
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `downloads` (
                `videoUrl` TEXT NOT NULL,
                `mediaUrl` TEXT NOT NULL,
                `episodeUrl` TEXT NOT NULL,
                `sourceId` INTEGER NOT NULL,
                `mediaTitle` TEXT NOT NULL,
                `episodeName` TEXT,
                `episodeNumber` REAL,
                `coverUrl` TEXT,
                `requestedAtEpochMs` INTEGER NOT NULL,
                `headersJson` TEXT,
                `isM3U8` INTEGER NOT NULL,
                PRIMARY KEY(`videoUrl`)
            )
            """.trimIndent(),
        )
        // Name must match what Room generates for Index("requestedAtEpochMs"), or schema validation
        // fails on the next open.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_downloads_requestedAtEpochMs` " +
                "ON `downloads` (`requestedAtEpochMs`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_mediaUrl` ON `downloads` (`mediaUrl`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_episodeUrl` ON `downloads` (`episodeUrl`)")
    }
}
