package com.otakustream.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.otakustream.core.database.tracking.TRACKER_SEASON_WHOLE_SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Runs the hand-written migrations against a real SQLite database, on the JVM runners CI already
// has — no emulator.
//
// MigrationSchemaGuardTest, the test that came before this one, diffs the exported schema JSONs. That
// proves a migration produces the right *shape*, and it is worth having, but it is blind to the thing
// that actually costs a user something: whether their rows made it across. Issue #84 named the gap
// precisely — MIGRATION_11_12 rebuilds tracker_links to widen its primary key, and a table rebuild
// whose INSERT...SELECT silently copied nothing would still leave a v12 table of exactly the right
// shape. Every AniList link on the device would be gone, and the shape guard would be green.
//
// Room's own validateMigration() runs at the end of each test here, so these keep the shape check
// too; what they add is the data.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationDataTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val dbName = "migration-test.db"

    @Test
    fun `11 to 12 carries every tracker link across the table rebuild`() {
        // Two links that differ in every column, so a copy that dropped or transposed one would show
        // up rather than being masked by identical values.
        helper.createDatabase(dbName, 11).use { db ->
            db.insertV11Link(mediaUrl = "https://example.test/frieren", trackerMediaId = 154587, trackerTitle = "Frieren", sourceId = 7)
            db.insertV11Link(mediaUrl = "https://example.test/bebop", trackerMediaId = 1, trackerTitle = "Cowboy Bebop", sourceId = 0)
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        val rows = db.query("SELECT mediaUrl, trackerMediaId, trackerTitle, sourceId, season FROM tracker_links ORDER BY mediaUrl").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor else null }.map {
                listOf(it.getString(0), it.getLong(1), it.getString(2), it.getInt(3), it.getInt(4))
            }.toList()
        }

        assertEquals(
            listOf(
                listOf("https://example.test/bebop", 1L, "Cowboy Bebop", 0, TRACKER_SEASON_WHOLE_SERIES),
                listOf("https://example.test/frieren", 154587L, "Frieren", 7, TRACKER_SEASON_WHOLE_SERIES),
            ),
            rows,
        )
    }

    @Test
    fun `11 to 12 leaves pre-season links resolvable exactly as they were`() {
        // The behavioural promise season-awareness was built on: a link made before seasons existed
        // must still resolve for a lookup that now carries a season. It only does if the migration
        // lands it on the whole-series sentinel, which is what the fallback arm of the DAO query
        // matches. A migration that defaulted season to, say, 1 would pass the shape guard and the
        // row-count check above, and quietly unlink every title on the device.
        helper.createDatabase(dbName, 11).use { db ->
            db.insertV11Link(mediaUrl = "https://example.test/legacy", trackerMediaId = 42, trackerTitle = "Legacy", sourceId = 3)
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        // The resolution rule the DAO uses, run against the migrated table.
        val resolved = db.query(
            """
            SELECT trackerMediaId FROM tracker_links
            WHERE mediaUrl = 'https://example.test/legacy' AND season IN (2, $TRACKER_SEASON_WHOLE_SERIES)
            ORDER BY CASE WHEN season = 2 THEN 0 ELSE 1 END
            LIMIT 1
            """.trimIndent(),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

        assertEquals(42L, resolved)
    }

    @Test
    fun `12 accepts one link per season for the same title`() {
        // The point of widening the key. On v11 this was a single-row table for a given mediaUrl and
        // the second insert would have replaced the first.
        helper.createDatabase(dbName, 11).use { db ->
            db.insertV11Link(mediaUrl = "https://example.test/show", trackerMediaId = 100, trackerTitle = "Show", sourceId = 1)
        }

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)
        db.execSQL(
            "INSERT INTO tracker_links (mediaUrl, trackerMediaId, trackerTitle, sourceId, season) " +
                "VALUES ('https://example.test/show', 200, 'Show Season 2', 1, 2)",
        )

        val seasons = db.query("SELECT season FROM tracker_links WHERE mediaUrl = 'https://example.test/show' ORDER BY season")
            .use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor else null }.map { it.getInt(0) }.toList()
            }

        assertEquals(listOf(TRACKER_SEASON_WHOLE_SERIES, 2), seasons)
    }

    @Test
    fun `the whole migration path from 6 to 12 preserves data and validates`() {
        // Each migration is checked in isolation above and in MigrationSchemaGuardTest. This is the
        // upgrade a real device performs — someone who installed an early build and updates once —
        // and it is the path where an ordering mistake between two individually-correct migrations
        // shows up. The seeded rows are the ones that survive the whole way: tracker_links exists at
        // v6 and is rebuilt at v12, which makes it the most fragile thing on the path.
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                "INSERT INTO tracker_links (mediaUrl, trackerMediaId, trackerTitle) " +
                    "VALUES ('https://example.test/ancient', 9, 'Ancient')",
            )
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            12,
            true,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
        )

        db.query("SELECT trackerTitle, sourceId, season FROM tracker_links WHERE trackerMediaId = 9").use { cursor ->
            assertTrue("the v6 link should still be there at v12", cursor.moveToFirst())
            assertEquals("Ancient", cursor.getString(0))
            // sourceId arrived at v9 with a default of 0 for rows that predate it.
            assertEquals(0, cursor.getInt(1))
            assertEquals(TRACKER_SEASON_WHOLE_SERIES, cursor.getInt(2))
        }
    }

    @Test
    fun `11 to 12 drops the scratch table it builds`() {
        // A rebuild that leaves tracker_links_new behind would validate fine — Room only checks the
        // tables it knows about — and then collide with itself if the migration ever ran twice.
        helper.createDatabase(dbName, 11).close()

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        val leftovers = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'tracker_links_new'")
            .use { it.count }
        assertEquals(0, leftovers)
    }

    @Test
    fun `an empty v11 database migrates cleanly`() {
        // The common case — most users have no AniList links at all — and the one where an
        // INSERT...SELECT over zero rows must still leave a valid table behind.
        helper.createDatabase(dbName, 11).close()

        val db = helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12)

        assertFalse(db.query("SELECT * FROM tracker_links").use { it.moveToFirst() })
    }

    // v11's tracker_links, keyed on mediaUrl alone. Written out rather than built through Room so the
    // test seeds the schema the user actually had, not whatever the current entity says.
    private fun SupportSQLiteDatabase.insertV11Link(
        mediaUrl: String,
        trackerMediaId: Long,
        trackerTitle: String,
        sourceId: Int,
    ) {
        execSQL(
            "INSERT INTO tracker_links (mediaUrl, trackerMediaId, trackerTitle, sourceId) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(mediaUrl, trackerMediaId, trackerTitle, sourceId),
        )
    }
}
