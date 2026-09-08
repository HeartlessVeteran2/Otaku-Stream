package com.otakustream.feature.library

import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadRepository
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.RemovedHistoryEntry
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.download.DownloadProgress
import com.otakustream.core.download.EpisodeDownloads
import com.otakustream.feature.tracking.TrackingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The download-removal bookkeeping, which has now been wrong three separate times: a single error
// string that any success cleared, then a fix that reconciled against the wrong thing, then a fix
// that could throw ConcurrentModificationException. Every one of those shipped because this
// ViewModel had no test — and it had no test because two of its four collaborators were concrete
// classes built on Media3's DownloadManager and the AniList network client.
//
// They are interfaces now, and this is what that bought.
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRemovalFailureTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun entry(videoUrl: String, title: String) = DownloadEntry(
        videoUrl = videoUrl,
        mediaUrl = "series|$title",
        episodeUrl = "$videoUrl/episode",
        sourceId = 1,
        mediaTitle = title,
        // Distinct per show: the message names the episode when there is one, so a fixture that
        // called every episode "Episode 1" could not tell two rows apart — which is the whole point
        // of the first test.
        episodeName = "$title episode 1",
        episodeNumber = 1f,
        coverUrl = null,
        requestedAtEpochMs = 0,
        headersJson = null,
    )

    // The regression that started all this. One row's removal times out, another's succeeds, and
    // the second must not erase the first — the first download is still listed and still stranded,
    // and that message is the only thing telling the user so.
    @Test
    fun `a successful removal does not clear a different row's failure`() = runTest(dispatcher) {
        val stubborn = entry("https://host/a.mp4", "Frieren")
        val cooperative = entry("https://host/b.mp4", "Dandadan")
        val downloads = FakeDownloadRepository(listOf(stubborn, cooperative))
        // Only the first url refuses to confirm.
        val episodes = FakeEpisodeDownloads(refuseRemovalOf = setOf(stubborn.videoUrl))
        val viewModel = LibraryViewModel(FakeLibraryRepository(), NoopTracking, downloads, episodes)

        viewModel.removeDownload(DownloadRow(stubborn, progress = null))
        val afterFailure = errorAfter(viewModel) { it != null }
        assertNotNull("the failure must be reported", afterFailure)
        assertTrue(
            "the message must name the row that failed, got: $afterFailure",
            afterFailure!!.contains("Frieren"),
        )

        viewModel.removeDownload(DownloadRow(cooperative, progress = null))
        advanceUntilIdle()

        assertEquals(
            "the second row's success erased the first row's failure",
            afterFailure,
            errorAfter(viewModel) { it != null },
        )
    }

    // The other direction: a row that does get removed must stop being reported, or the message
    // outlives the download it names and there is no way to dismiss it.
    @Test
    fun `a row's own successful removal clears its failure`() = runTest(dispatcher) {
        val row = entry("https://host/a.mp4", "Frieren")
        val downloads = FakeDownloadRepository(listOf(row))
        val episodes = FakeEpisodeDownloads(refuseRemovalOf = setOf(row.videoUrl))
        val viewModel = LibraryViewModel(FakeLibraryRepository(), NoopTracking, downloads, episodes)

        viewModel.removeDownload(DownloadRow(row, progress = null))
        assertNotNull(errorAfter(viewModel) { it != null })

        episodes.refuseRemovalOf = emptySet()
        viewModel.removeDownload(DownloadRow(row, progress = null))

        assertNull(
            "the message outlived the download it was about",
            errorAfter(viewModel) { it == null },
        )
    }

    // Two failures are one message with a count, not two messages or one that hides the other.
    @Test
    fun `two outstanding failures are reported together`() = runTest(dispatcher) {
        val first = entry("https://host/a.mp4", "Frieren")
        val second = entry("https://host/b.mp4", "Dandadan")
        val downloads = FakeDownloadRepository(listOf(first, second))
        val episodes = FakeEpisodeDownloads(refuseRemovalOf = setOf(first.videoUrl, second.videoUrl))
        val viewModel = LibraryViewModel(FakeLibraryRepository(), NoopTracking, downloads, episodes)

        viewModel.removeDownload(DownloadRow(first, progress = null))
        viewModel.removeDownload(DownloadRow(second, progress = null))

        val message = errorAfter(viewModel) { it?.contains("2") == true }
        assertNotNull(message)
        assertTrue("expected a count, got: $message", message!!.contains("2"))
    }

    // The metadata row is the app's only handle on the downloaded bytes, so it must survive a
    // removal that was not confirmed. Dropping it first is what stranded files in the cache with
    // nothing able to reach them.
    @Test
    fun `an unconfirmed removal leaves the row in the database`() = runTest(dispatcher) {
        val row = entry("https://host/a.mp4", "Frieren")
        val downloads = FakeDownloadRepository(listOf(row))
        val episodes = FakeEpisodeDownloads(refuseRemovalOf = setOf(row.videoUrl))
        val viewModel = LibraryViewModel(FakeLibraryRepository(), NoopTracking, downloads, episodes)

        viewModel.removeDownload(DownloadRow(row, progress = null))
        advanceUntilIdle()

        assertEquals("the row was forgotten before the bytes were confirmed gone", 0, downloads.forgotten.size)
    }

    // uiState is stateIn(WhileSubscribed), so with nothing collecting it never leaves its initial
    // value — and its combine body is flowOn(Dispatchers.IO), which virtual time does not control.
    // So: hold a subscription open for the life of the test, then alternate draining the test
    // dispatcher with a real pause until the expected value arrives.
    private suspend fun TestScope.errorAfter(
        viewModel: LibraryViewModel,
        expected: (String?) -> Boolean,
    ): String? {
        backgroundScope.launch { viewModel.uiState.collect {} }
        repeat(POLLS) {
            advanceUntilIdle()
            if (expected(viewModel.uiState.value.downloadError)) return viewModel.uiState.value.downloadError
            withContext(Dispatchers.Default) { delay(POLL_MS) }
        }
        advanceUntilIdle()
        return viewModel.uiState.value.downloadError
    }

    private companion object {
        const val POLLS = 200
        const val POLL_MS = 10L
    }
}

private object NoopTracking : TrackingManager {
    override suspend fun onEpisodeWatched(mediaUrl: String, episodeNumber: Float, season: Int?) = Unit
    override suspend fun onLibraryStatusChanged(mediaUrl: String, localStatus: String, season: Int?) = Unit
}

private class FakeEpisodeDownloads(var refuseRemovalOf: Set<String>) : EpisodeDownloads {
    override fun start(url: String, isM3U8: Boolean, headers: Map<String, String>) = Unit
    override suspend fun removeAndAwait(url: String, timeoutMs: Long): Boolean = url !in refuseRemovalOf
    override fun pause(url: String) = Unit
    override fun resume(url: String) = Unit
    override fun observe(): Flow<List<DownloadProgress>> = MutableStateFlow(emptyList())
    override fun completed(): List<DownloadProgress> = emptyList()
}

private class FakeDownloadRepository(entries: List<DownloadEntry>) : DownloadRepository {
    private val all = MutableStateFlow(entries)
    val forgotten = mutableListOf<String>()

    override fun observeAll(): Flow<List<DownloadEntry>> = all
    override fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>> = all
    override suspend fun entriesForEpisode(episodeUrl: String): List<DownloadEntry> =
        all.value.filter { it.episodeUrl == episodeUrl }

    override suspend fun remember(entry: DownloadEntry) {
        all.value = all.value.filterNot { it.videoUrl == entry.videoUrl } + entry
    }

    override suspend fun forget(videoUrl: String) {
        forgotten += videoUrl
        all.value = all.value.filterNot { it.videoUrl == videoUrl }
    }
}

private class FakeLibraryRepository : LibraryRepository {
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
    override suspend fun removeHistoryEntryAndReturn(id: Long): RemovedHistoryEntry? = null
    override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Boolean = true
    override suspend fun clearHistory() = Unit
}
