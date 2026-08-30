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
import com.otakustream.feature.tracking.TrackingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun removeDownload(row: DownloadRow) {
        viewModelScope.launch {
            episodeDownloads.remove(row.entry.videoUrl)
            downloadRepository.forget(row.entry.videoUrl)
        }
    }

    fun pauseDownload(row: DownloadRow) = episodeDownloads.pause(row.entry.videoUrl)

    fun resumeDownload(row: DownloadRow) = episodeDownloads.resume(row.entry.videoUrl)

    fun removeFromWatchlist(mediaUrl: String) {
        viewModelScope.launch { libraryRepository.remove(mediaUrl) }
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
