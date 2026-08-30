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
@Singleton
@androidx.annotation.OptIn(UnstableApi::class)
class EpisodeDownloads @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadHeaders: DownloadHeaders,
) {

    // The url doubles as the download id, so the same stream cannot be queued twice and every
    // other part of the app can ask about a download using the identity it already holds.
    //
    // isM3U8 and headers are the two things the player is given per-video that a bare url does not
    // carry, and both decide whether the download works at all — see DownloadEntry.
    fun start(url: String, isM3U8: Boolean = false, headers: Map<String, String> = emptyMap()) {
        // Registered before the request, so the first segment fetch already has them.
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

    fun remove(url: String) {
        downloadHeaders.forget(url)
        DownloadService.sendRemoveDownload(
            context,
            EpisodeDownloadService::class.java,
            url,
            /* foreground = */ false,
        )
    }

    // Media3 models pause as a manual stop reason on the individual download rather than as a
    // separate state, so "paused" here and STOP_REASON_PAUSED below are the same thing.
    fun pause(url: String) = setStopReason(url, STOP_REASON_PAUSED)

    fun resume(url: String) = setStopReason(url, Download.STOP_REASON_NONE)

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
    fun observe(): Flow<List<DownloadProgress>> = callbackFlow {
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
    fun completed(): List<DownloadProgress> {
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
