package com.otakustream.core.database.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.otakustream.core.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The difference between the two ways of saving a title, pinned against a real SQLite database.
//
// It matters because the bookmark button picks between them from a StateFlow that is two async hops
// behind Room and starts false. On that stale read the "save" branch runs against a row that
// already exists — so whichever call it makes has to be the one that leaves an existing row alone.
// upsert does not; insertIfAbsent does. Nothing about that is visible in Kotlin: both are one line,
// both return without complaint, and the difference only shows up as a Completed show quietly
// becoming Plan-to-watch some time later.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.libraryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // A show the user finished a year ago — the state a stray bookmark tap used to destroy.
    private val finished = LibraryEntry(
        mediaUrl = URL,
        sourceId = 7,
        title = "Frieren",
        coverUrl = "https://images.example/frieren.jpg",
        addedAtEpochMs = 1_700_000_000_000,
        status = LIBRARY_STATUS_COMPLETED,
    )

    // What the bookmark button builds when it thinks the title isn't saved: default status,
    // added-at of now.
    private fun freshSave(addedAtEpochMs: Long = 1_760_000_000_000) = LibraryEntry(
        mediaUrl = URL,
        sourceId = 7,
        title = "Frieren",
        coverUrl = "https://images.example/frieren.jpg",
        addedAtEpochMs = addedAtEpochMs,
    )

    @Test
    fun `insertIfAbsent leaves an existing entry exactly as it was`() = runTest {
        dao.upsert(finished)

        val inserted = dao.insertIfAbsent(freshSave())

        assertEquals("nothing should have been inserted", -1L, inserted)
        val stored = dao.get(URL)!!
        assertEquals(LIBRARY_STATUS_COMPLETED, stored.status)
        assertEquals(1_700_000_000_000, stored.addedAtEpochMs)
    }

    @Test
    fun `upsert is what would have overwritten it`() = runTest {
        dao.upsert(finished)

        // Not a fix under test — the demonstration of why the branch had to change. If this ever
        // stops being destructive, the comment on toggleWatchlist is out of date.
        dao.upsert(freshSave())

        val stored = dao.get(URL)!!
        assertEquals(LIBRARY_STATUS_PLANNED, stored.status)
        assertNotEquals(1_700_000_000_000, stored.addedAtEpochMs)
    }

    @Test
    fun `insertIfAbsent still saves a title that isn't there`() = runTest {
        val inserted = dao.insertIfAbsent(freshSave())

        assertTrue("a genuinely new save must insert", inserted != -1L)
        val stored = dao.get(URL)!!
        assertEquals(LIBRARY_STATUS_PLANNED, stored.status)
        assertEquals("Frieren", stored.title)
    }

    @Test
    fun `a second insertIfAbsent for the same title is a no-op, not a duplicate`() = runTest {
        dao.insertIfAbsent(freshSave(addedAtEpochMs = 1))
        dao.insertIfAbsent(freshSave(addedAtEpochMs = 2))

        // Double-tapping the bookmark before the flow catches up hits this path.
        assertEquals(1, dao.observeAll().first().size)
        assertEquals(1, dao.get(URL)!!.addedAtEpochMs)
    }

    private companion object {
        const val URL = "series|tt22248376"
    }
}
