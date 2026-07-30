package com.otakustream.core.player.torrent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.otakustream.core.torrent.TorrentEngine
import com.otakustream.core.torrent.TorrentFileReader
import com.otakustream.core.torrent.TorrentUri
import java.io.File
import java.io.IOException

// Media3 adapter for torrent:// urls. Lives in :core:player because this module already owns every
// Media3 integration point; :core:torrent stays a plain torrent engine with no player dependency.
//
// The trackers can't come from the url — it is deliberately just the torrent's identity so that
// resume position, skip markers, and watch history stay keyed on a stable string. They are supplied
// per-playback by whoever builds the factory, from Video.trackers.
@UnstableApi
class TorrentDataSource(
    private val engine: TorrentEngine,
    private val saveDir: File,
    private val trackers: List<String>,
) : BaseDataSource(/* isNetwork = */ true) {

    private var reader: TorrentFileReader? = null
    private var uri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        val ref = TorrentUri.parse(url)
            ?: throw IOException("Not a torrent url: $url")
        val opened = engine.openFile(ref, trackers, saveDir)
        reader = opened
        uri = dataSpec.uri
        position = dataSpec.position

        // A DataSpec length of LENGTH_UNSET means "to the end of the file", which is the normal case
        // for playback; a bounded request (a range read) must not be allowed to read past its end.
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            opened.length - dataSpec.position
        } else {
            minOf(dataSpec.length, opened.length - dataSpec.position)
        }
        if (bytesRemaining < 0) throw IOException("Requested position ${dataSpec.position} is past the end of the file")

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val active = reader ?: throw IOException("read() before open()")

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = active.read(position, buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val active = reader
        reader = null
        uri = null
        try {
            active?.close()
        } finally {
            // Only signal a completed transfer if one was actually started, matching the
            // BaseDataSource contract.
            if (active != null) transferEnded()
        }
    }

    // Built per playback rather than once, because the tracker list is per-video. `delegate` handles
    // every other scheme, so this factory can be dropped in wherever a DataSource.Factory is
    // expected without the caller caring whether the url is a torrent.
    @UnstableApi
    class Factory(
        private val engine: TorrentEngine,
        private val saveDir: File,
        private val trackers: List<String>,
        private val delegate: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeDispatchingDataSource(
            torrent = TorrentDataSource(engine, saveDir, trackers),
            other = delegate.createDataSource(),
        )
    }
}

// Chooses between the torrent source and everything else at open() time, because that is the first
// moment the url is known — a DataSource.Factory has to hand back an instance before then.
//
// Both are constructed up front rather than lazily. Neither constructor does any work (the torrent
// source touches nothing until open()), and it means a transfer listener registered before open()
// reaches whichever source ends up being used, instead of having to be replayed onto a
// later-created one.
@UnstableApi
private class SchemeDispatchingDataSource(
    private val torrent: DataSource,
    private val other: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val chosen = if (TorrentUri.isTorrentUrl(dataSpec.uri.toString())) torrent else other
        active = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("read() before open()")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        torrent.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun close() {
        // Only the one that was opened needs closing; closing the other would be a no-op at best and
        // an unbalanced transferEnded() at worst.
        active?.close()
        active = null
    }
}
