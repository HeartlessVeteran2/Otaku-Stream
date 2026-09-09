package com.otakustream.feature.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Two different nothings, and telling a user the wrong one is a lie about their own data.
//
// "Nothing saved yet" over a full library that a typo filtered to zero reads as data loss — the
// screen is saying the library is empty when it is not. "No matches" over a genuinely empty library
// is the milder mistake but still sends someone hunting for a search term they never typed. The
// screen's own comments call this out in both tabs, and the History tab shipped without the second
// case at all: a search matching none of your history drew the Clear button over a blank list.
//
// It is invisible to every test that does not render, because both branches produce a screen with
// no rows on it. This is the first Compose test in this module, on Robolectric, so the check costs
// no device.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryEmptyStatesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun saved(title: String) = LibraryEntry(
        mediaUrl = "media/$title",
        sourceId = 1L,
        title = title,
        coverUrl = null,
        addedAtEpochMs = 0L,
    )

    private fun watched(title: String) = WatchHistoryEntry(
        id = title.hashCode().toLong(),
        sourceId = 1L,
        mediaUrl = "media/$title",
        mediaTitle = title,
        episodeUrl = "media/$title/1",
        episodeName = "Episode 1",
        episodeNumber = 1f,
        watchedAtEpochMs = 0L,
    )

    // Unconfined rather than a StandardTestDispatcher: there is no runTest here to own a clock, and
    // the ViewModel's combine has to have produced by the time the first frame is composed.
    private fun show(
        watchlist: List<LibraryEntry> = emptyList(),
        history: List<WatchHistoryEntry> = emptyList(),
    ) {
        val viewModel = LibraryViewModel(
            libraryRepository = FakeLibrary(watchlist, history),
            trackingManager = NoTracking,
            downloadRepository = NoDownloads,
            episodeDownloads = NoDownloadService,
            ioDispatcher = Dispatchers.Unconfined,
        )
        compose.setContent {
            MaterialTheme {
                LibraryScreen(
                    onMediaClick = { _, _, _, _ -> },
                    onPlayDirect = {},
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun search(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.waitForIdle()
    }

    @Test
    fun `a watchlist search that matches nothing does not claim the watchlist is empty`() {
        show(watchlist = listOf(saved("Frieren"), saved("Dandadan")))

        search("zzz")

        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithText("Nothing saved yet").assertDoesNotExist()
    }

    @Test
    fun `an empty watchlist says nothing is saved, not that a search failed`() {
        show(watchlist = emptyList())

        compose.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        compose.onNodeWithText("No matches").assertDoesNotExist()
    }

    // The tab that shipped without the second case.
    @Test
    fun `a history search that matches nothing does not claim there is no history`() {
        show(history = listOf(watched("Frieren"), watched("Dandadan")))
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()

        search("zzz")

        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithText("No watch history yet").assertDoesNotExist()
    }

    @Test
    fun `an empty history says there is none, not that a search failed`() {
        show(history = emptyList())
        compose.onNodeWithText("History").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("No watch history yet").assertIsDisplayed()
        compose.onNodeWithText("No matches").assertDoesNotExist()
    }

    // The X on the search field is the only way back to the full list without deleting characters,
    // and a control that does nothing is worse than one that is not there.
    @Test
    fun `clearing the search brings the list back`() {
        show(watchlist = listOf(saved("Frieren")))

        search("zzz")
        compose.onNodeWithText("Frieren").assertDoesNotExist()

        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Frieren").assertIsDisplayed()
    }

    // The filter row is hidden on Downloads and On device, so a query left behind there is a filter
    // with no visible control — the watchlist comes back short and nothing on screen says why.
    @Test
    fun `a query does not survive a trip through a tab that has no search field`() {
        show(watchlist = listOf(saved("Frieren")))

        search("zzz")
        compose.onNodeWithText("Frieren").assertDoesNotExist()

        compose.onNodeWithText("Downloads").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Watchlist").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Frieren").assertIsDisplayed()
    }
}

private object NoTracking : TrackingManager {
    override suspend fun onEpisodeWatched(mediaUrl: String, episodeNumber: Float, season: Int?) = Unit
    override suspend fun onLibraryStatusChanged(mediaUrl: String, localStatus: String, season: Int?) = Unit
}

private object NoDownloadService : EpisodeDownloads {
    override fun start(url: String, isM3U8: Boolean, headers: Map<String, String>) = Unit
    override suspend fun removeAndAwait(url: String, timeoutMs: Long): Boolean = true
    override fun pause(url: String) = Unit
    override fun resume(url: String) = Unit
    override fun observe(): Flow<List<DownloadProgress>> = MutableStateFlow(emptyList())
    override fun completed(): List<DownloadProgress> = emptyList()
}

private object NoDownloads : DownloadRepository {
    override fun observeAll(): Flow<List<DownloadEntry>> = MutableStateFlow(emptyList())
    override fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>> = MutableStateFlow(emptyList())
    override suspend fun entriesForEpisode(episodeUrl: String): List<DownloadEntry> = emptyList()
    override suspend fun remember(entry: DownloadEntry) = Unit
    override suspend fun forget(videoUrl: String) = Unit
}

private class FakeLibrary(
    private val watchlist: List<LibraryEntry>,
    private val history: List<WatchHistoryEntry>,
) : LibraryRepository {
    override fun observeLibrary(): Flow<List<LibraryEntry>> = MutableStateFlow(watchlist)
    override fun observeInLibrary(mediaUrl: String): Flow<Boolean> = MutableStateFlow(false)
    override fun observeStatus(mediaUrl: String): Flow<String?> = MutableStateFlow(null)
    override suspend fun addIfAbsent(entry: LibraryEntry): Boolean = true
    override suspend fun remove(mediaUrl: String) = Unit
    override suspend fun removeAndReturn(mediaUrl: String): LibraryEntry? = null
    override suspend fun setStatus(mediaUrl: String, status: String) = Unit
    override fun observeHistory(): Flow<List<WatchHistoryEntry>> = MutableStateFlow(history)
    override fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>> = emptyFlow()
    override suspend fun recordWatch(entry: WatchHistoryEntry) = Unit
    override suspend fun lastTitleFor(mediaUrl: String): String? = null
    override suspend fun removeHistoryEntryAndReturn(id: Long): RemovedHistoryEntry? = null
    override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Boolean = true
    override suspend fun clearHistory() = Unit
}
