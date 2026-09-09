package com.otakustream.core.download

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

// How far along one download is, in the terms the UI needs.
//
// Media3's Download carries more than a list row wants and less than it needs — no title, no
// episode — so this is the projection, and the display metadata is joined on separately from the
// app's own table. The url is the join key, and it is the same url watch history and resume
// position use, so a downloaded episode is the same entity everywhere.
data class DownloadProgress(
    val url: String,
    val state: State,
    val percentDownloaded: Float,
    val downloadedBytes: Long,
) {
    enum class State { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, REMOVING }

    val isFinished: Boolean get() = state == State.COMPLETED
}

// The app's way in and out of the download machinery.
//
// Everything goes through DownloadService rather than touching DownloadManager directly, because
// only the service route starts the foreground service. Calling the manager straight would work
// right up until the user backgrounded the app, at which point the download would stop with no
// notification to explain it.
// An interface for the same reason LibraryRepository is one: the ViewModels that depend on this are
// otherwise untestable. The implementation is built on Media3's DownloadManager and its foreground
// DownloadService, neither of which can be stood up on a JVM runner — so every screen that lists or
// removes a download had no unit test at all, and the download-removal bookkeeping has now been
// wrong three separate times.
//
// Only what callers use. Everything about *how* a download is queued — the service route, the
// per-video headers, the stop-reason encoding of "paused" — stays in the implementation.
// Long enough for the service to start and unlink a file, short enough that a user who tapped
// Remove is not left watching a spinner. Exceeding it is not an error — it means the row stays and
// can be removed again.
//
// Top-level rather than in the implementation's companion because the interface's default argument
// has to see it.
const val REMOVE_TIMEOUT_MS = 10_000L

interface EpisodeDownloads {

    // The url doubles as the download id, so the same stream cannot be queued twice.
    fun start(url: String, isM3U8: Boolean = false, headers: Map<String, String> = emptyMap())

    // Whether the removal was confirmed within the wait. False is a deadline expiring, not a
    // verdict — the service may finish just after — which is why callers reconcile rather than
    // trust it.
    suspend fun removeAndAwait(url: String, timeoutMs: Long = REMOVE_TIMEOUT_MS): Boolean

    fun pause(url: String)

    fun resume(url: String)

    // In-flight downloads, re-emitted on every progress change.
    fun observe(): Flow<List<DownloadProgress>>

    // A one-shot read of everything finished, which observe() does not carry.
    fun completed(): List<DownloadProgress>
}

@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class EpisodeDownloadsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadHeaders: DownloadHeaders,
) : EpisodeDownloads {

    // The url doubles as the download id, so the same stream cannot be queued twice and every
    // other part of the app can ask about a download using the identity it already holds.
    //
    // isM3U8 and headers are the two things the player is given per-video that a bare url does not
    // carry, and both decide whether the download works at all — see DownloadEntry.
    override fun start(url: String, isM3U8: Boolean, headers: Map<String, String>) {
        // Registered before the request, so the download's first fetch already has them — and its
        // segments too, which reach the same entry through the origin. See DownloadHeaders.
        if (headers.isNotEmpty()) downloadHeaders.remember(url, headers)
        val request = DownloadRequest.Builder(url, android.net.Uri.parse(url))
            // Without this, an HLS url whose path has no .m3u8 extension is treated as a
            // progressive file: Media3 downloads the playlist text, a few kilobytes, and reports
            // success. The result is an episode marked "Saved" with nothing playable behind it.
            .apply { if (isM3U8) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        DownloadService.sendAddDownload(
            context,
            EpisodeDownloadService::class.java,
            request,
            /* foreground = */ false,
        )
    }

    // Asks the service to delete the download, then waits for the index to agree that it is gone.
    //
    // The wait is the point. sendRemoveDownload posts an Intent and returns; the deletion happens
    // later, in the service. The caller used to delete its own metadata row on the next line, so if
    // the removal never completed — the process dying first, the service failing — the bytes stayed
    // in the download cache with nothing left pointing at them. The Downloads list is built by
    // joining the app's rows against Media3's index, so an orphan like that is invisible in the UI
    // and unreachable by the only button that could delete it: the storage is simply gone until the
    // app's data is cleared.
    //
    // Returns whether it is confirmed gone. On false the caller must keep its row, which leaves the
    // download listed and the Remove button live — and a second press then succeeds immediately,
    // because a download that is already absent from the index satisfies this on the first check.
    override suspend fun removeAndAwait(url: String, timeoutMs: Long): Boolean {
        downloadHeaders.forget(url)
        DownloadService.sendRemoveDownload(
            context,
            EpisodeDownloadService::class.java,
            url,
            /* foreground = */ false,
        )
        // On IO, not the caller's dispatcher. Both callers await this from viewModelScope, which is
        // Dispatchers.Main — and isAbsentFromIndex is a synchronous SQLite read re-run on every
        // change the manager reports, several a second while a download is being torn down. That is
        // a database read per frame on the main thread.
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                changes().first { isAbsentFromIndex(url) }
                true
            } ?: false
        }
    }

    // Ticks once immediately and then on every change the manager reports. The tick carries no
    // payload on purpose: the index is the authority for "is it actually gone", and onDownloadRemoved
    // would never fire for a download that was already absent when the removal was requested.
    private fun changes(): Flow<Unit> = callbackFlow {
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                trySend(Unit)
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                trySend(Unit)
            }

            override fun onIdle(downloadManager: DownloadManager) {
                trySend(Unit)
            }
        }
        downloadManager.addListener(listener)
        trySend(Unit)
        awaitClose { downloadManager.removeListener(listener) }
    }

    // getDownload reads SQLite and declares IOException. A read that fails tells us nothing about
    // whether the download is gone, and answering "yes" on no evidence is what strands the bytes —
    // so an unreadable index counts as still present and the caller keeps its row.
    private fun isAbsentFromIndex(url: String): Boolean =
        runCatching { downloadManager.downloadIndex.getDownload(url) }.getOrElse { return false } == null

    // Media3 models pause as a manual stop reason on the individual download rather than as a
    // separate state, so "paused" here and STOP_REASON_PAUSED below are the same thing.
    override fun pause(url: String) = setStopReason(url, STOP_REASON_PAUSED)

    override fun resume(url: String) = setStopReason(url, Download.STOP_REASON_NONE)

    private fun setStopReason(url: String, reason: Int) {
        DownloadService.sendSetStopReason(
            context,
            EpisodeDownloadService::class.java,
            url,
            reason,
            /* foreground = */ false,
        )
    }

    // Emits the whole current set on every change.
    //
    // A whole-list emission rather than per-download deltas because that is what DownloadManager's
    // listener actually gives, and because the consumers are list screens that re-render anyway.
    // The initial emission is the current state, so a screen opened while a download is already
    // running shows it immediately instead of waiting for the next progress tick.
    override fun observe(): Flow<List<DownloadProgress>> = callbackFlow {
        fun emitCurrent() {
            trySend(downloadManager.currentDownloads.map { it.toProgress() })
        }

        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) = emitCurrent()

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) =
                emitCurrent()
        }
        downloadManager.addListener(listener)
        emitCurrent()
        awaitClose { downloadManager.removeListener(listener) }
    }

    // A one-shot read of everything ever downloaded, including finished items.
    //
    // currentDownloads holds only what is in flight, so a completed episode disappears from it —
    // which is exactly the set the Library needs to show. This walks the index instead.
    override fun completed(): List<DownloadProgress> {
        val cursor = downloadManager.downloadIndex.getDownloads(Download.STATE_COMPLETED)
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(c.download.toProgress())
            }
        }
    }

    private fun Download.toProgress() = DownloadProgress(
        url = request.id,
        state = when (state) {
            Download.STATE_QUEUED -> DownloadProgress.State.QUEUED
            Download.STATE_DOWNLOADING -> DownloadProgress.State.DOWNLOADING
            Download.STATE_STOPPED -> DownloadProgress.State.PAUSED
            Download.STATE_COMPLETED -> DownloadProgress.State.COMPLETED
            Download.STATE_FAILED -> DownloadProgress.State.FAILED
            Download.STATE_REMOVING, Download.STATE_RESTARTING -> DownloadProgress.State.REMOVING
            else -> DownloadProgress.State.QUEUED
        },
        // Media3 reports -1 (C.PERCENTAGE_UNSET) until the total size is known, which for an HLS
        // playlist is after the first segments land. Surfacing that verbatim would render as a
        // progress bar jumping backwards from -1%.
        percentDownloaded = percentDownloaded.takeIf { it >= 0f } ?: 0f,
        downloadedBytes = bytesDownloaded,
    )

    private companion object {
        // Any non-zero value means "stopped by us". Media3 reserves 0 for "not stopped".
        const val STOP_REASON_PAUSED = 1
    }
}
