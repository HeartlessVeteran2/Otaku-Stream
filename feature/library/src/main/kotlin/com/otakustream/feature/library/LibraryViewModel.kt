package com.otakustream.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadRepository
import com.otakustream.core.download.DownloadProgress
import com.otakustream.core.download.EpisodeDownloads
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.sources.api.UiMessages
import com.otakustream.feature.tracking.TrackingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import org.json.JSONObject

data class LibraryUiState(
    val watchlist: List<LibraryEntry> = emptyList(),
    val history: List<WatchHistoryEntry> = emptyList(),
    // Most recent history row per media — the "continue watching" rail.
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
    val downloads: List<DownloadRow> = emptyList(),
)

// A download as the list shows it: what it is called, joined to how far along it is.
//
// The two halves come from different owners on purpose. The name is the app's (Media3 knows only a
// url); the state is Media3's (it is what actually does the downloading). Joining them at read time
// means neither can go stale against the other.
data class DownloadRow(
    val entry: DownloadEntry,
    val progress: DownloadProgress?,
) {
    // No progress record means Media3 has no download for this url. That happens either because it
    // finished and left currentDownloads, or because it never started. `completed` disambiguates.
    val isPending: Boolean get() = progress != null && !progress.isFinished
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val trackingManager: TrackingManager,
    private val downloadRepository: DownloadRepository,
    private val episodeDownloads: EpisodeDownloads,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeLibrary(),
        libraryRepository.observeHistory(),
        downloadRepository.observeAll(),
        // Emits on every download state change, so a row's progress bar advances without the
        // screen polling for it.
        episodeDownloads.observe(),
    ) { watchlist, history, downloads, inFlight ->
        val byUrl = inFlight.associateBy { it.url }
        // A finished download is not in currentDownloads at all, so it would join to null and be
        // indistinguishable from one that never started. The index is the only place that knows.
        val finished = episodeDownloads.completed().associateBy { it.url }
        LibraryUiState(
            watchlist = watchlist,
            history = history,
            continueWatching = history.distinctBy { it.mediaUrl }.take(10),
            downloads = downloads.map { entry ->
                DownloadRow(entry, byUrl[entry.videoUrl] ?: finished[entry.videoUrl])
            },
        )
    }
        // The combine body walks Media3's download index, which is a synchronous SQLite read, and it
        // runs on every progress callback — several a second during a download. On the collector's
        // default dispatcher that is a database read on the main thread, once per tick.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    // No undo here, and that is the point: this deletes the file's bytes. Offering "Undo" for
    // something that would have to be re-downloaded over the network — possibly on a metered
    // connection, possibly not available any more — would be a lie, so the screen asks first.
    fun removeDownload(row: DownloadRow) {
        viewModelScope.launch {
            episodeDownloads.remove(row.entry.videoUrl)
            downloadRepository.forget(row.entry.videoUrl)
        }
    }

    // A failed download was a dead end: the row said "Failed" in red and the only button beside it
    // deleted it, so recovering meant finding the show again and re-tapping the episode. Everything
    // needed to re-enqueue is already on the row — the stream url, whether it is HLS, and the
    // per-video headers the host requires — so this is the same call the first attempt made.
    fun retryDownload(row: DownloadRow) {
        val entry = row.entry
        episodeDownloads.start(
            url = entry.videoUrl,
            isM3U8 = entry.isM3U8,
            headers = parseHeaders(entry.headersJson),
        )
    }

    // Stored as the JSON the source handed over. Unreadable JSON means retrying without headers,
    // which is what the download would have done before they were recorded at all — better than
    // refusing to retry.
    private fun parseHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun pauseDownload(row: DownloadRow) = episodeDownloads.pause(row.entry.videoUrl)

    fun resumeDownload(row: DownloadRow) = episodeDownloads.resume(row.entry.videoUrl)

    // Removal happens immediately and offers to put it back, rather than asking first. A watchlist
    // row is pure metadata — the exact entry can be restored, so the cheap path is the right one
    // and a dialog would only be in the way of the common case, which is deliberate.
    //
    // The entry is captured before the delete because after it there is nothing left to read.
    fun removeFromWatchlist(mediaUrl: String) {
        viewModelScope.launch {
            val removed = libraryRepository.observeLibrary().first().find { it.mediaUrl == mediaUrl }
            libraryRepository.remove(mediaUrl)
            if (removed == null) return@launch
            UiMessages.showUndoable("Removed ${removed.title}") {
                // Runs on the snackbar host's scope, not this one — see UiMessages.Message. The
                // repository is a singleton, so it does not care that this ViewModel may be gone.
                libraryRepository.add(removed)
            }
        }
    }

    fun setStatus(mediaUrl: String, status: String) {
        viewModelScope.launch {
            libraryRepository.setStatus(mediaUrl, status)
            // Local Library is the source of truth; mirror the change up to AniList when linked.
            trackingManager.onLibraryStatusChanged(mediaUrl, status)
        }
    }

    fun clearHistory() {
        viewModelScope.launch { libraryRepository.clearHistory() }
    }
}
