package com.otakustream.feature.sources.ui

import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.RemovedHistoryEntry
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.sources.api.CatalogPage
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.SourceFilter
import com.otakustream.core.sources.api.Video
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.SourceBootstrapper
import com.otakustream.feature.sources.SourceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// What a refresh is allowed to do to rails that already have something in them.
//
// Both of these shipped: a fan-out where every source failed replaced a full home screen with an
// empty one and said nothing about why, and a source that never returns held the whole fan-out —
// and, once there was a pull indicator attached to it, held that forever. Neither could have been
// caught before, because HomeViewModel took SourceBootstrapper as a concrete class that reads Room
// and builds source engines, so it could not be constructed on a JVM runner at all.
//
// The fifteen-second deadline is the point of the second test, so the dispatcher is a virtual one:
// waiting it out for real would be a slow test that passes on a quiet runner and fails on a busy
// one.
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRailsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a rail keeps its entries when no source answers`() = runTest(dispatcher) {
        val source = FakeSource(id = 1, name = "one")
        val viewModel = homeViewModel(source)
        advanceUntilIdle()
        assertEquals(listOf("one:a"), viewModel.uiState.value.popular.map { it.media.url })

        // Every source now fails. Before the fix this wrote the empty list straight over the rail.
        source.failing = true
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(
            "a rail no source answered must keep what it had",
            listOf("one:a"),
            viewModel.uiState.value.popular.map { it.media.url },
        )
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    // The other half, and the reason the first test is not enough on its own: if "keep the previous
    // entries" applied whenever the new result was empty, a rail could never be emptied — a source
    // that genuinely has nothing to show would leave stale posters on screen for good.
    @Test
    fun `a rail is replaced when a source answers with nothing`() = runTest(dispatcher) {
        val source = FakeSource(id = 1, name = "one")
        val viewModel = homeViewModel(source)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.popular.size)

        source.items = emptyList()
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(
            "an answer of 'nothing' is still an answer",
            viewModel.uiState.value.popular.isEmpty(),
        )
    }

    // The source that never comes back. This is the shape of a wedged Mangayomi extension: its
    // coroutine is parked in a blocking call that cancellation cannot reach, so the deadline can
    // only be enforced by giving up on it — which is why the fan-out runs on a scope the awaiting
    // coroutine is not the parent of. As a child, this test would hang instead of failing.
    @Test
    fun `a source that never returns does not hold the refresh open`() = runTest(dispatcher) {
        val answering = FakeSource(id = 1, name = "answers")
        val hung = FakeSource(id = 2, name = "hangs").apply { hang = true }
        val viewModel = homeViewModel(answering, hung)

        // Not advanceUntilIdle here: on a virtual clock that runs straight through the deadline, so
        // it would prove only that the fan-out ends eventually and nothing about what ends it. This
        // walks up to the deadline without crossing it.
        runCurrent()
        assertTrue("the fan-out should have started", viewModel.uiState.value.isLoading)

        // Deliberately one tick short. advanceTimeBy stops *at* the target without running what is
        // scheduled there, but the runCurrent that follows does run it — so advancing the full
        // deadline here would fire the timeout and assert the opposite of what it says.
        advanceTimeBy(RAIL_DEADLINE_MS - 1)
        runCurrent()
        assertTrue(
            "still waiting on the hung source right up to the deadline",
            viewModel.uiState.value.isLoading,
        )

        advanceUntilIdle()

        assertFalse("the deadline must release the fan-out", viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(
            "the source that did answer still contributes its entries",
            listOf("answers:a"),
            viewModel.uiState.value.popular.map { it.media.url },
        )
    }

    private fun homeViewModel(vararg sources: VideoSource) = HomeViewModel(
        sourceRepository = FakeSourceRepository(sources.toList()),
        sourceBootstrapper = object : SourceBootstrapper {
            override suspend fun ensureStarted() = Unit
        },
        libraryRepository = EmptyLibraryRepository,
        ioDispatcher = dispatcher,
    )

    private class FakeSource(
        override val id: Long,
        override val name: String,
        override val lang: String = "en",
        var items: List<MediaItem> = listOf(MediaItem(url = "$name:a", title = "A", coverUrl = null)),
        var failing: Boolean = false,
        // Suspends forever. A real one would be blocked rather than suspended, but from the
        // fan-out's side the two are the same thing: a Deferred that never completes.
        var hang: Boolean = false,
    ) : VideoSource {
        private val never = CompletableDeferred<CatalogPage>()

        private suspend fun page(): CatalogPage = when {
            hang -> never.await()
            failing -> throw IllegalStateException("source is down")
            else -> CatalogPage(items = items, hasNextPage = false)
        }

        override suspend fun getPopular(page: Int) = page()
        override suspend fun getLatest(page: Int) = page()
        override suspend fun search(query: String, filters: List<SourceFilter>, page: Int) = page()
        override suspend fun getMediaDetails(media: MediaItem) =
            MediaDetails(media = media, description = null)

        override suspend fun getEpisodeList(media: MediaItem): List<Episode> = emptyList()
        override suspend fun getVideoList(episode: Episode): List<Video> = emptyList()
    }

    private class FakeSourceRepository(private val sources: List<VideoSource>) : SourceRepository {
        override fun getSources(): List<VideoSource> = sources
        override fun getSource(id: Long): VideoSource? = sources.firstOrNull { it.id == id }
        override fun observeSources(): Flow<List<VideoSource>> = MutableStateFlow(sources)
        override fun registerDynamic(source: VideoSource) = Unit
        override fun replaceDynamic(expected: VideoSource, source: VideoSource): Boolean = false
        override fun unregisterDynamic(id: Long) = Unit
    }

    // Only observeHistory is reached from here — the Continue Watching rail — and these tests are
    // about the source rails, so it stays empty.
    private object EmptyLibraryRepository : LibraryRepository {
        override fun observeLibrary(): Flow<List<LibraryEntry>> = flowOf(emptyList())
        override fun observeInLibrary(mediaUrl: String): Flow<Boolean> = flowOf(false)
        override fun observeStatus(mediaUrl: String): Flow<String?> = flowOf(null)
        override suspend fun addIfAbsent(entry: LibraryEntry): Boolean = false
        override suspend fun remove(mediaUrl: String) = Unit
        override suspend fun removeAndReturn(mediaUrl: String): LibraryEntry? = null
        override suspend fun setStatus(mediaUrl: String, status: String) = Unit
        override fun observeHistory(): Flow<List<WatchHistoryEntry>> = flowOf(emptyList())
        override fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordWatch(entry: WatchHistoryEntry) = Unit
        override suspend fun lastTitleFor(mediaUrl: String): String? = null
        override suspend fun removeHistoryEntryAndReturn(id: Long): RemovedHistoryEntry? = null
        override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Boolean = false
        override suspend fun clearHistory() = Unit
    }

    private companion object {
        // Mirrors RAIL_FETCH_TIMEOUT_MS, which is private to HomeViewModel.
        const val RAIL_DEADLINE_MS = 15_000L
    }
}
