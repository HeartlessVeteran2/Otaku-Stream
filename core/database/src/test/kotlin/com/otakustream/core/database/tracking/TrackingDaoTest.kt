package com.otakustream.core.database.tracking

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.otakustream.core.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The season-resolution query is a SQL string, so it is invisible to the compiler and to every
// pure-logic test in the repo — it can only be wrong at runtime, on a user's device, and the way it
// goes wrong is silent: a link resolves to the wrong AniList entry and progress is pushed to the
// wrong show. TrackerSeasonTest already covers toTrackerSeason(), the Kotlin half of the rule. This
// covers the SQL half.
//
// Worth stating what the ORDER BY is for, because it is the part that looks redundant and isn't:
// the WHERE clause matches both the exact-season row and the whole-series row, and without the
// ordering SQLite is free to return either. It would pass every test that only ever stores one of
// the two.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TrackingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.trackingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun link(mediaUrl: String, trackerMediaId: Long, season: Int) =
        TrackerLink(
            mediaUrl = mediaUrl,
            trackerMediaId = trackerMediaId,
            trackerTitle = "Title $trackerMediaId",
            sourceId = 1,
            season = season,
        )

    @Test
    fun `an exact season link wins over the whole-series link`() = runTest {
        dao.upsertLink(link(URL, trackerMediaId = 100, season = TRACKER_SEASON_WHOLE_SERIES))
        dao.upsertLink(link(URL, trackerMediaId = 200, season = 2))

        assertEquals(200L, dao.getLink(URL, season = 2)?.trackerMediaId)
    }

    @Test
    fun `a season with no link of its own falls back to the whole-series link`() = runTest {
        dao.upsertLink(link(URL, trackerMediaId = 100, season = TRACKER_SEASON_WHOLE_SERIES))
        dao.upsertLink(link(URL, trackerMediaId = 200, season = 2))

        // Season 3 has no link. This is what makes per-season linking additive rather than a
        // behaviour change: linking season 2 must not unlink season 3.
        assertEquals(100L, dao.getLink(URL, season = 3)?.trackerMediaId)
    }

    @Test
    fun `a lookup with no season resolves the whole-series link`() = runTest {
        // Scripted sources, local files and single-season metas all report no season and arrive here
        // as the sentinel. Left unmatched, tracking would silently stop working for all of them.
        dao.upsertLink(link(URL, trackerMediaId = 100, season = TRACKER_SEASON_WHOLE_SERIES))

        assertEquals(100L, dao.getLink(URL, season = TRACKER_SEASON_WHOLE_SERIES)?.trackerMediaId)
    }

    @Test
    fun `a season-only link does not answer for other seasons`() = runTest {
        // No whole-series row to fall back to. Returning the season 2 link for season 3 would push
        // season 3's progress onto season 2's AniList entry.
        dao.upsertLink(link(URL, trackerMediaId = 200, season = 2))

        assertEquals(200L, dao.getLink(URL, season = 2)?.trackerMediaId)
        assertNull(dao.getLink(URL, season = 3))
        assertNull(dao.getLink(URL, season = TRACKER_SEASON_WHOLE_SERIES))
    }

    @Test
    fun `a link never answers for a different title`() = runTest {
        dao.upsertLink(link(URL, trackerMediaId = 100, season = TRACKER_SEASON_WHOLE_SERIES))

        assertNull(dao.getLink("https://example.test/other", season = TRACKER_SEASON_WHOLE_SERIES))
    }

    @Test
    fun `observeLink re-emits when a more specific link is inserted`() = runTest {
        // The screen and the sync path must agree. If the observing variant kept serving the
        // whole-series link after the user linked the season they are watching, the UI would show one
        // entry while progress went to another.
        dao.upsertLink(link(URL, trackerMediaId = 100, season = TRACKER_SEASON_WHOLE_SERIES))
        assertEquals(100L, dao.observeLink(URL, season = 2).first()?.trackerMediaId)

        dao.upsertLink(link(URL, trackerMediaId = 200, season = 2))

        assertEquals(200L, dao.observeLink(URL, season = 2).first()?.trackerMediaId)
    }

    @Test
    fun `upserting the same title and season replaces rather than duplicates`() = runTest {
        dao.upsertLink(link(URL, trackerMediaId = 100, season = 2))
        dao.upsertLink(link(URL, trackerMediaId = 300, season = 2))

        assertEquals(300L, dao.getLink(URL, season = 2)?.trackerMediaId)
    }

    @Test
    fun `deleting one season leaves the others intact`() = runTest {
        // The reason deleteLink is keyed on season as well as url: unlinking the season in front of
        // the user must not wipe the title's other links.
        dao.upsertLink(link(URL, trackerMediaId = 100, season = TRACKER_SEASON_WHOLE_SERIES))
        dao.upsertLink(link(URL, trackerMediaId = 200, season = 2))

        dao.deleteLink(URL, season = 2)

        assertNull(dao.getLink(URL, season = 2)?.takeIf { it.season == 2 })
        assertEquals(100L, dao.getLink(URL, season = TRACKER_SEASON_WHOLE_SERIES)?.trackerMediaId)
        // And season 2 now falls back, rather than resolving to nothing.
        assertEquals(100L, dao.getLink(URL, season = 2)?.trackerMediaId)
    }

    @Test
    fun `the reverse lookup returns the most recent link for a tracker id`() = runTest {
        // Documented policy for the ambiguous case: the same AniList id linked from two places, most
        // recent wins. Pinned because "ORDER BY rowid DESC" is easy to drop as noise.
        dao.upsertLink(link("https://example.test/first", trackerMediaId = 555, season = TRACKER_SEASON_WHOLE_SERIES))
        dao.upsertLink(link("https://example.test/second", trackerMediaId = 555, season = TRACKER_SEASON_WHOLE_SERIES))

        assertEquals("https://example.test/second", dao.getLinkByTrackerId(555)?.mediaUrl)
    }

    private companion object {
        const val URL = "https://example.test/show"
    }
}
