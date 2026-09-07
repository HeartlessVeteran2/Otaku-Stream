package com.otakustream.core.database.library

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

// The delete-and-undo round trip for a single history row, against a real SQLite database.
//
// History was the only list in the app where the sole way to remove one thing was to clear all of
// it. Adding a per-row delete means adding an undo, and an undo is where this kind of change goes
// wrong quietly: the snackbar closes either way, so a restore that silently dropped the row would
// look exactly like one that worked.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WatchHistoryUndoTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = LibraryRepositoryImpl(db, db.libraryDao(), db.watchHistoryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entry(title: String, episode: Float, watchedAt: Long) = WatchHistoryEntry(
        sourceId = 7,
        mediaUrl = "series|tt$episode",
        mediaTitle = title,
        episodeUrl = "https://example.test/$title/$episode",
        episodeName = "Episode $episode",
        episodeNumber = episode,
        watchedAtEpochMs = watchedAt,
        coverUrl = null,
    )

    @Test
    fun `removing one row leaves the rest alone`() = runTest {
        repository.recordWatch(entry("Frieren", 1f, 1_000))
        repository.recordWatch(entry("Frieren", 2f, 2_000))
        repository.recordWatch(entry("Dandadan", 1f, 3_000))

        val target = repository.observeHistory().first().first { it.episodeNumber == 2f }
        val removed = repository.removeHistoryEntryAndReturn(target.id)

        assertEquals("the deleted row is handed back", target.id, removed?.id)
        val remaining = repository.observeHistory().first()
        assertEquals(2, remaining.size)
        assertEquals(setOf(1f, 1f), remaining.map { it.episodeNumber }.toSet())
    }

    @Test
    fun `undo puts the entry back with its content intact`() = runTest {
        repository.recordWatch(entry("Frieren", 12f, 5_000))
        val original = repository.observeHistory().first().single()

        val removed = repository.removeHistoryEntryAndReturn(original.id)!!
        assertEquals(0, repository.observeHistory().first().size)

        repository.restoreHistoryEntry(removed)

        val restored = repository.observeHistory().first().single()
        // The id is deliberately not compared: autoGenerate issues a new one, which is why undo
        // restores the entry rather than the row. Everything the user can see must survive.
        assertEquals(original.mediaTitle, restored.mediaTitle)
        assertEquals(original.episodeUrl, restored.episodeUrl)
        assertEquals(original.episodeNumber, restored.episodeNumber)
        assertEquals(original.watchedAtEpochMs, restored.watchedAtEpochMs)
        assertEquals(original.mediaUrl, restored.mediaUrl)
        assertEquals(original.sourceId, restored.sourceId)
    }

    @Test
    fun `removing a row that is already gone returns null rather than throwing`() = runTest {
        repository.recordWatch(entry("Frieren", 1f, 1_000))
        val id = repository.observeHistory().first().single().id

        assertEquals(id, repository.removeHistoryEntryAndReturn(id)?.id)
        // Undo can be tapped after the row has gone another way — clearing history, say. It has to
        // find nothing rather than fail.
        assertNull(repository.removeHistoryEntryAndReturn(id))
    }
}
