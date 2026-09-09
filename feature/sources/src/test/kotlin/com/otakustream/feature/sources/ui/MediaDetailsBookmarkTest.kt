package com.otakustream.feature.sources.ui

import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadRepository
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.RemovedHistoryEntry
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.database.tracking.TrackerLink
import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.core.download.DownloadProgress
import com.otakustream.core.download.EpisodeDownloads
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.feature.sources.SourceRepository
import com.otakustream.feature.tracking.AniListClient
import com.otakustream.feature.tracking.AniListListEntry
import com.otakustream.feature.tracking.AniListMedia
import com.otakustream.feature.tracking.AniListPage
import com.otakustream.feature.tracking.AniListViewer
import com.otakustream.feature.tracking.AniListViewerEntry
import com.otakustream.feature.tracking.AniSkipClient
import com.otakustream.feature.tracking.AniSkipInterval
import com.otakustream.feature.tracking.TrackingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The bookmark button, and what it must never do to a row that is already there.
//
// `inLibrary` is a StateFlow two async hops behind Room and it starts false. Open a title you have
// already saved and press the bookmark before that flow catches up — or press it twice quickly —
// and the save takes the "not saved yet" branch. When that branch was a whole-row upsert it
// replaced the existing row: a show marked Completed silently became Plan-to-watch and jumped to
// the top of the Library, from a tap that was only ever meant to save something.
//
// The screen driving this is 1100 lines with eight collaborators, and until AniListClient and
// AniSkipClient became interfaces it could not be constructed on a JVM runner at all.
@OptIn(ExperimentalCoroutinesApi::class)
class MediaDetailsBookmarkTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `saving a title that is already saved leaves its status alone`() = runTest(dispatcher) {
        val library = FakeLibraryRepository()
        // Already saved, and already finished. This is the row the tap must not touch.
        library.rows["media-1"] = LibraryEntry(
            mediaUrl = "media-1",
            sourceId = 7L,
            title = "Show",
            coverUrl = null,
            addedAtEpochMs = 1_000L,
        )
        library.statuses["media-1"] = "COMPLETED"

        val viewModel = viewModel(library)
        viewModel.load(sourceId = 7L, mediaUrl = "media-1", mediaTitle = "Show")
        // Deliberately NOT advancing to let `inLibrary` catch up: pressing while it still reads
        // false is the whole scenario.
        viewModel.toggleWatchlist()
        advanceUntilIdle()

        assertEquals(
            "a save must not reset the status of a row that already exists",
            "COMPLETED",
            library.statuses["media-1"],
        )
        assertEquals(
            "nor its added-at, which is what re-sorted it to the top of the Library",
            1_000L,
            library.rows["media-1"]?.addedAtEpochMs,
        )
        assertTrue("and it must go through addIfAbsent, not a whole-row upsert", library.addIfAbsentCalls > 0)
    }

    // The message that used to be erased by an unrelated success.
    //
    // This screen lists a whole season. Cancel episode 3's download and have it miss its deadline,
    // then cancel episode 4's and have that one succeed, and a single shared error string meant
    // episode 4's success wiped episode 3's warning — the only notice the user had that a file was
    // still stranded in Library › Downloads.
    @Test
    fun `a successful removal does not erase another episode's warning`() = runTest(dispatcher) {
        val downloads = FakeDownloadRepository()
        downloads.add("ep-3", "video-3")
        downloads.add("ep-4", "video-4")
        val service = FakeEpisodeDownloads()
        service.neverConfirms += "video-3"

        val viewModel = viewModel(downloads = downloads, episodeDownloads = service)
        viewModel.load(sourceId = 7L, mediaUrl = "media-1", mediaTitle = "Show")

        viewModel.cancelDownload(episode("ep-3"))
        advanceUntilIdle()
        assertTrue(
            "the stranded episode should be reported",
            viewModel.uiState.value.downloadError != null,
        )

        viewModel.cancelDownload(episode("ep-4"))
        advanceUntilIdle()

        assertTrue(
            "episode 3 is still stranded, so its warning must survive episode 4's success",
            viewModel.uiState.value.downloadError != null,
        )
    }

    // The other direction, and it exercises a different mechanism than it first appears to.
    //
    // Cancelling the same episode again clears its message through the ordinary "nothing left to
    // remove" branch, which would pass with no reconciliation at all — my first version of this
    // test did exactly that and proved nothing. What needs guarding is the *cross-episode* case:
    // ep-3 timed out, its rows then went away by some other route (the service finishing late, or
    // Library › Downloads removing it), and the next thing the user cancels is ep-4. Only the
    // reconciliation drops ep-3 there, and without it the screen keeps warning about a download
    // that no longer exists, with no Retry and no dismiss.
    @Test
    fun `a warning is dropped when its episode's rows go away by another route`() = runTest(dispatcher) {
        val downloads = FakeDownloadRepository()
        downloads.add("ep-3", "video-3")
        downloads.add("ep-4", "video-4")
        val service = FakeEpisodeDownloads()
        service.neverConfirms += "video-3"

        val viewModel = viewModel(downloads = downloads, episodeDownloads = service)
        viewModel.load(sourceId = 7L, mediaUrl = "media-1", mediaTitle = "Show")

        viewModel.cancelDownload(episode("ep-3"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.downloadError != null)

        // ep-3's rows disappear without this screen being told.
        downloads.forget("video-3")

        // The user cancels a different episode. ep-3 is never mentioned again.
        viewModel.cancelDownload(episode("ep-4"))
        advanceUntilIdle()

        assertEquals(
            "a message pointing at a download that no longer exists is worse than none",
            null,
            viewModel.uiState.value.downloadError,
        )
    }

    private fun viewModel(
        library: LibraryRepository = FakeLibraryRepository(),
        downloads: DownloadRepository = FakeDownloadRepository(),
        episodeDownloads: EpisodeDownloads = FakeEpisodeDownloads(),
    ) = MediaDetailsViewModel(
        sourceRepository = FakeSourceRepository(),
        libraryRepository = library,
        trackingRepository = FakeTrackingRepository(),
        trackingManager = FakeTrackingManager(),
        downloadRepository = downloads,
        episodeDownloads = episodeDownloads,
        aniListClient = FakeAniListClient(),
        aniSkipClient = FakeAniSkipClient(),
    )

    private fun episode(url: String) = Episode(url = url, name = url, episodeNumber = 1f)

    // Records what was asked of it and, crucially, honours addIfAbsent's contract: an existing row
    // is left exactly as it is. A fake that overwrote would let the bug through.
    private class FakeLibraryRepository : LibraryRepository {
        val rows = mutableMapOf<String, LibraryEntry>()
        val statuses = mutableMapOf<String, String>()
        var addIfAbsentCalls = 0
            private set

        override fun observeLibrary(): Flow<List<LibraryEntry>> = flowOf(rows.values.toList())
        override fun observeInLibrary(mediaUrl: String): Flow<Boolean> = flowOf(rows.containsKey(mediaUrl))
        override fun observeStatus(mediaUrl: String): Flow<String?> = flowOf(statuses[mediaUrl])

        override suspend fun addIfAbsent(entry: LibraryEntry): Boolean {
            addIfAbsentCalls++
            if (rows.containsKey(entry.mediaUrl)) return false
            rows[entry.mediaUrl] = entry
            return true
        }

        override suspend fun remove(mediaUrl: String) {
            rows.remove(mediaUrl)
            statuses.remove(mediaUrl)
        }

        override suspend fun removeAndReturn(mediaUrl: String): LibraryEntry? = rows.remove(mediaUrl)
        override suspend fun setStatus(mediaUrl: String, status: String) { statuses[mediaUrl] = status }
        override fun observeHistory(): Flow<List<WatchHistoryEntry>> = flowOf(emptyList())
        override fun observeWatchedEpisodeUrls(mediaUrl: String): Flow<List<String>> = flowOf(emptyList())
        override suspend fun recordWatch(entry: WatchHistoryEntry) = Unit
        override suspend fun lastTitleFor(mediaUrl: String): String? = null
        override suspend fun removeHistoryEntryAndReturn(id: Long): RemovedHistoryEntry? = null
        override suspend fun restoreHistoryEntry(removed: RemovedHistoryEntry): Boolean = false
        override suspend fun clearHistory() = Unit
    }

    private class FakeSourceRepository : SourceRepository {
        override fun getSources(): List<VideoSource> = emptyList()
        override fun getSource(id: Long): VideoSource? = null
        override fun observeSources(): Flow<List<VideoSource>> = MutableStateFlow(emptyList())
        override fun registerDynamic(source: VideoSource) = Unit
        override fun replaceDynamic(expected: VideoSource, source: VideoSource): Boolean = false
        override fun unregisterDynamic(id: Long) = Unit
    }

    private class FakeTrackingRepository : TrackingRepository {
        override suspend fun getLink(mediaUrl: String, season: Int): TrackerLink? = null
        override fun observeLink(mediaUrl: String, season: Int): Flow<TrackerLink?> = flowOf(null)
        override suspend fun getLinkByTrackerId(trackerMediaId: Long): TrackerLink? = null
        override suspend fun getLinksByTrackerId(trackerMediaId: Long): List<TrackerLink> = emptyList()
        override suspend fun saveLink(link: TrackerLink) = Unit
        override suspend fun removeLink(mediaUrl: String, season: Int) = Unit
        override suspend fun getToken(): String? = null
        override fun observeToken(): Flow<String?> = flowOf(null)
        override suspend fun saveToken(accessToken: String) = Unit
        override suspend fun clearToken() = Unit
        override suspend fun clearTokenIfCurrent(token: String): Boolean = false
    }

    private class FakeTrackingManager : TrackingManager {
        override suspend fun onEpisodeWatched(mediaUrl: String, episodeNumber: Float, season: Int?) = Unit
        override suspend fun onLibraryStatusChanged(mediaUrl: String, localStatus: String, season: Int?) = Unit
    }

    private class FakeDownloadRepository : DownloadRepository {
        // episodeUrl -> its rows. forget() removes a row, which is what lets the reconciliation
        // in clearDownloadsFor see an episode as genuinely gone.
        val rows = mutableMapOf<String, MutableList<DownloadEntry>>()

        fun add(episodeUrl: String, videoUrl: String) {
            rows.getOrPut(episodeUrl) { mutableListOf() }.add(
                DownloadEntry(
                    videoUrl = videoUrl,
                    mediaUrl = "media-1",
                    episodeUrl = episodeUrl,
                    sourceId = 7L,
                    mediaTitle = "Show",
                    episodeName = episodeUrl,
                    episodeNumber = 1f,
                    coverUrl = null,
                    requestedAtEpochMs = 0L,
                    headersJson = null,
                ),
            )
        }

        override fun observeAll(): Flow<List<DownloadEntry>> = flowOf(emptyList())
        override fun observeForMedia(mediaUrl: String): Flow<List<DownloadEntry>> = flowOf(emptyList())
        override suspend fun entriesForEpisode(episodeUrl: String): List<DownloadEntry> =
            rows[episodeUrl].orEmpty().toList()

        override suspend fun remember(entry: DownloadEntry) = Unit
        override suspend fun forget(videoUrl: String) {
            rows.values.forEach { it.removeAll { row -> row.videoUrl == videoUrl } }
        }
    }

    private class FakeEpisodeDownloads : EpisodeDownloads {
        // videoUrls the service refuses to confirm, standing in for a removal that misses its
        // deadline.
        val neverConfirms = mutableSetOf<String>()

        override fun start(url: String, isM3U8: Boolean, headers: Map<String, String>) = Unit
        override suspend fun removeAndAwait(url: String, timeoutMs: Long): Boolean =
            url !in neverConfirms

        override fun pause(url: String) = Unit
        override fun resume(url: String) = Unit
        override fun observe(): Flow<List<DownloadProgress>> = flowOf(emptyList())
        override fun completed(): List<DownloadProgress> = emptyList()
    }

    private class FakeAniListClient : AniListClient {
        override suspend fun fetchTrending(page: Int) = AniListPage(media = emptyList(), currentPage = 1, hasNextPage = false)
        override suspend fun fetchAllTimePopular(page: Int) = AniListPage(media = emptyList(), currentPage = 1, hasNextPage = false)
        override suspend fun fetchPopularThisSeason(page: Int) = AniListPage(media = emptyList(), currentPage = 1, hasNextPage = false)
        override suspend fun search(query: String, page: Int) = AniListPage(media = emptyList(), currentPage = 1, hasNextPage = false)
        override suspend fun fetchMediaDetail(id: Long): AniListMedia = error("not used")
        override suspend fun fetchViewerListEntry(token: String, mediaId: Long): AniListViewerEntry? = null
        override suspend fun fetchViewer(token: String): AniListViewer = error("not used")
        override suspend fun fetchUserAnimeLists(token: String, userId: Long): List<AniListListEntry> = emptyList()
        override suspend fun saveMediaListEntry(
            token: String,
            mediaId: Long,
            status: String?,
            score: Double?,
            progress: Int?,
        ) = Unit
        override suspend fun searchAnime(query: String): List<AniListMedia> = emptyList()
        override suspend fun getMalId(aniListId: Long): Long? = null
    }

    private class FakeAniSkipClient : AniSkipClient {
        override suspend fun fetch(
            malId: Long,
            episodeNumber: Int,
            episodeLengthSec: Long,
        ): List<AniSkipInterval> = emptyList()
    }
}
