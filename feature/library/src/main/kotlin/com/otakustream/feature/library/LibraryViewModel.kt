package com.otakustream.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.otakustream.core.database.download.DownloadEntry
import com.otakustream.core.database.download.DownloadRepository
import com.otakustream.core.download.DownloadHeaders
import com.otakustream.core.download.DownloadProgress
import com.otakustream.core.download.EpisodeDownloads
import com.otakustream.core.database.library.LibraryEntry
import com.otakustream.core.database.library.LibraryRepository
import com.otakustream.core.database.library.WatchHistoryEntry
import com.otakustream.core.sources.api.UiMessages
import com.otakustream.feature.tracking.TrackingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import com.otakustream.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val watchlist: List<LibraryEntry> = emptyList(),
    val history: List<WatchHistoryEntry> = emptyList(),
    // Most recent history row per media — the "continue watching" rail.
    val continueWatching: List<WatchHistoryEntry> = emptyList(),
    val downloads: List<DownloadRow> = emptyList(),
    // A removal that could not be confirmed. Screen state rather than a UiMessages snackbar,
    // because it is an error about a row on this screen and the app's rule for those is that they
    // stay next to the thing that failed, with a way to try again — UiMessages says so in its own
    // header, and a global snackbar would surface this on whatever tab the user had moved to.
    val downloadError: String? = null,
)

// One line for however many removals are outstanding. Naming the show is what makes the message
// actionable when there is one, and a count is the honest summary when there are several — listing
// four titles in a banner above the four rows that already show them helps nobody.
private fun downloadErrorMessage(failures: Map<String, String>): String? = when (failures.size) {
    0 -> null
    1 -> failures.values.first()
    else -> "Couldn't finish removing ${failures.size} downloads. They're still listed — try again."
}

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
    // Injected so a test can make the combine below deterministic. See IoDispatcher.
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    // Keyed by the video url that failed, not a single string.
    //
    // A single string made any success clear any failure: removing one download that timed out and
    // then removing a different one that worked wiped the first message, while the first download
    // was still sitting in the list, still stranded, with nothing left on screen saying so. Keyed,
    // a success clears only its own row's failure.
    private val _downloadFailures = MutableStateFlow<Map<String, String>>(emptyMap())

    fun consumeDownloadError() {
        _downloadFailures.value = emptyMap()
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeLibrary(),
        libraryRepository.observeHistory(),
        downloadRepository.observeAll(),
        // Emits on every download state change, so a row's progress bar advances without the
        // screen polling for it.
        episodeDownloads.observe(),
        _downloadFailures,
    ) { watchlist, history, downloads, inFlight, downloadFailures ->
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
            downloadError = downloadErrorMessage(downloadFailures),
        )
    }
        // The combine body walks Media3's download index, which is a synchronous SQLite read, and it
        // runs on every progress callback — several a second during a download. On the collector's
        // default dispatcher that is a database read on the main thread, once per tick.
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    // No undo here, and that is the point: this deletes the file's bytes. Offering "Undo" for
    // something that would have to be re-downloaded over the network — possibly on a metered
    // connection, possibly not available any more — would be a lie, so the screen asks first.
    fun removeDownload(row: DownloadRow) {
        viewModelScope.launch {
            // The metadata row goes only once the bytes are confirmed gone, not alongside the
            // request to delete them. Deleting it first meant a removal that never completed left
            // the file in the download cache with nothing pointing at it — invisible in this list,
            // which is built by joining these rows against Media3's index, and therefore
            // unreclaimable through the app.
            //
            // Keeping the row on failure is the recoverable direction: the download stays listed,
            // the Remove button stays live, and pressing it again succeeds straight away once the
            // service has caught up.
            val url = row.entry.videoUrl
            if (episodeDownloads.removeAndAwait(url)) {
                downloadRepository.forget(url)
                // Only this row's failure is cleared. This ViewModel outlives the tab, so a message
                // about a download that is now gone would otherwise sit there naming rows it has
                // nothing to do with — but a *different* row that is still stranded has to keep
                // saying so.
                // update(), not `value = value - url`. Both callers run on viewModelScope's main
                // dispatcher today, where a read-modify-write in a single non-suspending statement
                // cannot be interleaved — but that is a property of the call sites, not of this
                // line, and it stops being true the first time one of them moves to a background
                // dispatcher. update() is a compare-and-set loop and costs nothing.
                _downloadFailures.update { it - url }
            } else {
                val message = "Couldn't finish removing ${row.entry.episodeName ?: row.entry.mediaTitle}. " +
                    "It's still listed — try again."
                _downloadFailures.update { it + (url to message) }
            }
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
            // The same decoder the download's own data source uses, not a second copy of it: a
            // retry that parsed headers differently from the request it is re-issuing would fail
            // in ways the first attempt did not.
            headers = DownloadHeaders.decode(entry.headersJson),
        )
    }

    fun pauseDownload(row: DownloadRow) = episodeDownloads.pause(row.entry.videoUrl)

    fun resumeDownload(row: DownloadRow) = episodeDownloads.resume(row.entry.videoUrl)

    // Removal happens immediately and offers to put it back, rather than asking first. A watchlist
    // row is pure metadata — the exact entry can be restored, so the cheap path is the right one
    // and a dialog would only be in the way of the common case, which is deliberate.
    //
    // The delete hands back what it deleted, so the snackbar is holding the row that actually went
    // rather than a snapshot read beforehand. Reading and then deleting are two suspending calls,
    // and a save or a status change landing between them would leave undo restoring the older copy
    // — quietly reverting whatever happened in the gap.
    fun removeFromWatchlist(mediaUrl: String) {
        viewModelScope.launch {
            val removed = libraryRepository.removeAndReturn(mediaUrl) ?: return@launch
            UiMessages.showUndoable("Removed ${removed.title}") {
                // Runs on the snackbar host's scope, not this one — see UiMessages.Message. The
                // repository is a singleton, so it does not care that this ViewModel may be gone.
                //
                // Insert-if-absent, in one statement. add() is an upsert, so undoing after the
                // title has been saved again would overwrite the newer entry — and with it whatever
                // status was just set — with the snapshot taken before the delete. Checking first
                // and then writing only narrows that window; SQLite closes it.
                //
                // And when it does refuse, say so. Dropping the result meant the snackbar closed
                // exactly as it does on success while nothing had been restored, so the one case
                // this guard exists to handle was also the one case the user was not told about.
                if (!libraryRepository.addIfAbsent(removed)) {
                    UiMessages.show("${removed.title} is already saved")
                }
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

    // One row, with an undo — matching what Watchlist and Downloads already offer. History was
    // the only list where the sole way to remove anything was to clear all of it, so a single
    // mistyped search or a video opened by accident could only be tidied away by destroying the
    // rest of the history with it.
    fun removeHistoryEntry(id: Long) {
        viewModelScope.launch {
            val removed = libraryRepository.removeHistoryEntryAndReturn(id) ?: return@launch
            UiMessages.showUndoable("Removed ${removed.entry.mediaTitle} from history") {
                // Runs on the snackbar host's scope, not this one — see UiMessages.Message. A
                // straight restore rather than insert-if-absent: history rows are append-only and
                // carry no user-editable state, so there is nothing here for a concurrent write to
                // overwrite, unlike the watchlist entry this pattern came from.
                //
                // But the undo has to expire. The snackbar outlives the row it is about: delete one
                // entry, then Clear history — which asks first and says it cannot be undone — and a
                // still-visible Undo would put that one row back into a history the user had just
                // been told was gone for good. `removed` carries which history it came from, and
                // the repository refuses the restore if that history has since been wiped.
                //
                // The check lives there rather than here for two reasons this ViewModel cannot fix:
                // it has to be atomic with the insert, and it has to survive this ViewModel, which
                // a rotation replaces while the snackbar it created is still on screen.
                //
                // And say so when it refuses, for the same reason the watchlist undo above does: a
                // snackbar that closes exactly as it does on success, having done nothing, is the
                // one case worth telling the user about.
                if (!libraryRepository.restoreHistoryEntry(removed)) {
                    UiMessages.show("History was cleared — ${removed.entry.mediaTitle} wasn't restored")
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { libraryRepository.clearHistory() }
    }
}
