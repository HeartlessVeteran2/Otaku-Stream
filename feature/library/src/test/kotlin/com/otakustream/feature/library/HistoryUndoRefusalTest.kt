package com.otakustream.feature.library

import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadRepository
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.RemovedHistoryEntry
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.download.DownloadProgress
import com.otakustream.core.download.EpisodeDownloads
import com.otakustream.core.sources.api.UiMessages
import com.otakustream.feature.tracking.TrackingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// What the history undo says when it refuses.
//
// Removing one history row offers an Undo. That snackbar outlives the row it is about: delete an
// entry, then Clear history — which asks first and says it cannot be undone — and a still-visible
// Undo would put that row back into a history the user had just been told was gone. The repository
// refuses the restore in that case, atomically with the insert.
//
// The refusal is the part worth testing, because its failure mode is silence. A snackbar that
// closes exactly as it does on success, having restored nothing, tells the user their row is back
// when it isn't.
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryUndoRefusalTest {

    private val dispatcher = StandardTestDispatcher()
    private val messages = mutableListOf<UiMessages.Message>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Registered before anything runs, and the list cleared straight after: UiMessages is a
        // process-wide object that queues messages emitted while no sink is attached, so setting a
        // sink replays whatever another test left behind.
        UiMessages.setSink { messages += it }
        messages.clear()
    }

    @After
    fun tearDown() {
        UiMessages.setSink(null)
        Dispatchers.resetMain()
    }

    @Test
    fun `an undo that cannot restore says so`() = runTest(dispatcher) {
        val library = FakeHistoryRepository(restoreSucceeds = false)
        val viewModel = viewModel(library)

        viewModel.removeHistoryEntry(id = 1L)
        advanceUntilIdle()

        val undo = messages.single()
        assertEquals("Removed Cowboy Bebop from history", undo.text)
        assertNotNull("the removal must offer an undo at all", undo.action)

        // The host runs this on its own scope, which is the point of the action being a suspend
        // lambda rather than something the ViewModel launches.
        undo.action!!.invoke()
        advanceUntilIdle()

        assertEquals(
            "a refused undo must say so rather than close like a success",
            listOf(
                "Removed Cowboy Bebop from history",
                "History was cleared — Cowboy Bebop wasn't restored",
            ),
            messages.map { it.text },
        )
    }

    // The counterpart, without which "always warn" would pass the test above while telling users
    // their undo failed every single time it worked.
    @Test
    fun `an undo that restores says nothing further`() = runTest(dispatcher) {
        val library = FakeHistoryRepository(restoreSucceeds = true)
        val viewModel = viewModel(library)

        viewModel.removeHistoryEntry(id = 1L)
        advanceUntilIdle()
        messages.single().action!!.invoke()
        advanceUntilIdle()

        assertEquals(
            "a successful undo is self-evident — the row is back on screen",
            listOf("Removed Cowboy Bebop from history"),
            messages.map { it.text },
        )
        assertTrue(library.restoreAttempted)
    }

    // A row that was already gone offers no undo at all, rather than one that would restore
    // nothing.
    @Test
    fun `removing a row that is already gone offers no undo`() = runTest(dispatcher) {
        val library = FakeHistoryRepository(restoreSucceeds = true, rowExists = false)
        val viewModel = viewModel(library)

        viewModel.removeHistoryEntry(id = 1L)
        advanceUntilIdle()

        assertTrue("nothing was removed, so there is nothing to confirm", messages.isEmpty())
    }

    private fun viewModel(library: LibraryRepository) = LibraryViewModel(
        libraryRepository = library,
        trackingManager = NoopTrackingForUndo,
        downloadRepository = EmptyDownloadRepository,
        episodeDownloads = NoopEpisodeDownloads,
        ioDispatcher = dispatcher,
    )
}

private class FakeHistoryRepository(
    private val restoreSucceeds: Boolean,
    private val rowExists: Boolean = true,
) : LibraryRepository {
    var restoreAttempted = false
        private set

    override suspend fun removeHistoryEntryAndReturn(id: Long): RemovedHistoryEntry? =
        if (!rowExists) {
            null
        } else {
            RemovedHistoryEntry(
                entry = WatchHistoryEntry(
                    id = id,
                    mediaUrl = "media-1",
                    mediaTitle = "Cowboy Bebop",
                    episodeUrl = "ep-1",
                    episodeName = "Asteroid Blues",
                    episodeNumber = 1f,
                    sourceId = 1L,
                    coverUrl = null,
                    watchedAtEpochMs = 0L,
                ),
                generation = 0,
            )
        }

    override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Boolean {
        restoreAttempted = true
        return restoreSucceeds
    }

    override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(emptyList())
    override fun observeInLibrary(mediaUrl: String): Flow<Boolean> = MutableStateFlow(false)
    override fun observeStatus(mediaUrl: String): Flow<String?> = MutableStateFlow(null)
    override suspend fun addIfAbsent(entry: LibraryEntry): Boolean = true
    override suspend fun remove(mediaUrl: String) = Unit
    override suspend fun removeAndReturn(mediaUrl: String): LibraryEntry? = null
    override suspend fun setStatus(mediaUrl: String, status: String) = Unit
    override fun observeHistory(): Flow<List<WatchHistoryEntry>> = MutableStateFlow(emptyList())
    override fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>> = emptyFlow()
    override suspend fun recordWatch(entry: WatchHistoryEntry) = Unit
    override suspend fun lastTitleFor(mediaUrl: String): String? = null
    override suspend fun clearHistory() = Unit
}

private object NoopTrackingForUndo : TrackingManager {
    override suspend fun onEpisodeWatched(mediaUrl: String, episodeNumber: Float, season: Int?) = Unit
    override suspend fun onLibraryStatusChanged(mediaUrl: String, localStatus: String, season: Int?) = Unit
}

private object NoopEpisodeDownloads : EpisodeDownloads {
    override fun start(url: String, isM3U8: Boolean, headers: Map<String, String>) = Unit
    override suspend fun removeAndAwait(url: String, timeoutMs: Long): Boolean = true
    override fun pause(url: String) = Unit
    override fun resume(url: String) = Unit
    override fun observe(): Flow<List<DownloadProgress>> = MutableStateFlow(emptyList())
    override fun completed(): List<DownloadProgress> = emptyList()
}

private object EmptyDownloadRepository : DownloadRepository {
    override fun observeAll(): Flow<List<DownloadEntry>> = MutableStateFlow(emptyList())
    override fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>> = MutableStateFlow(emptyList())
    override suspend fun entriesForEpisode(episodeUrl: String): List<DownloadEntry> = emptyList()
    override suspend fun remember(entry: DownloadEntry) = Unit
    override suspend fun forget(videoUrl: String) = Unit
}
