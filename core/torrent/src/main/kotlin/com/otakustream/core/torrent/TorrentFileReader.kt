package com.otakustream.core.torrent

import android.util.Log
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.AddTorrentAlert
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "TorrentFileReader"

// Random access to one file inside a torrent, as the player needs it: give me the bytes at this
// offset, block until they exist, fail loudly if they never arrive.
//
// Bytes are read from the partially-downloaded file on disk rather than through libtorrent's
// readPiece (which answers asynchronously via an alert). libtorrent writes and verifies each piece
// to the save path as it completes, so once havePiece(i) is true the bytes for piece i are on disk
// and correct. That turns "read from a torrent" into an ordinary file read guarded by a wait, which
// is far easier to reason about than an alert round-trip per read.
class TorrentFileReader private constructor(
    private val handle: TorrentHandle,
    private val file: File,
    private val layout: TorrentFileLayout,
    private val saveDir: File,
    // Subtitle files inside this torrent, with torrent-relative paths. Chosen at open time because
    // that is when the file list is known and when their priorities have to be raised.
    private val subtitleCandidates: List<TorrentSubtitleFile>,
    // Invoked exactly once when this reader closes, so whoever owns the session can decide whether
    // anything still needs the torrent. The reader itself must not stop the session: several readers
    // can share one, and it has no way to know about the others.
    private val onClosed: (TorrentHandle, String) -> Unit,
) : Closeable {

    private val raf: RandomAccessFile = RandomAccessFile(file, "r")

    @Volatile
    private var closed = false

    val length: Long get() = layout.fileLength

    // Exposed for the owning engine's stats snapshot only. Internal so the handle can't leak out of
    // the module and be used after the torrent is removed.
    internal val torrentHandle: TorrentHandle get() = handle

    // Absolute path of the file being read, so the cache sweep can protect it from eviction. Internal
    // for the same reason as the handle above: nothing outside this module has any business knowing
    // where on disk a torrent lands.
    internal val filePath: String get() = file.absolutePath

    // How many subtitle files this torrent carries that are worth offering, so a caller can tell
    // "none in this torrent" from "not downloaded yet" and stop waiting for something that will
    // never arrive.
    val subtitleCount: Int get() = subtitleCandidates.size

    // The subtitle files that have fully arrived, with absolute paths, ready to hand to the player.
    //
    // Only complete files, and that matters more than it sounds: a subtitle renderer given a
    // half-written .srt shows the first few lines and then silently stops, which reads as the
    // subtitles being broken rather than still downloading.
    fun readySubtitles(): List<TorrentSubtitleFile> {
        if (closed || subtitleCandidates.isEmpty()) return emptyList()
        val progress = runCatching { handle.fileProgress() }.getOrNull() ?: return emptyList()
        val sizes = runCatching { handle.torrentFile()?.files() }.getOrNull() ?: return emptyList()
        return subtitleCandidates.filter { candidate ->
            val index = candidate.fileIndex
            index in progress.indices && progress[index] >= sizes.fileSize(index)
        }.mapNotNull { candidate ->
            // The path came out of the torrent's own metadata, so it is attacker-controlled; this
            // one is handed to the player to open directly.
            TorrentPaths.containedFile(saveDir, candidate.path)?.let { candidate.copy(path = it.absolutePath) }
        }
    }

    // Reads at most up to the end of the piece containing `position`. Deliberately not more: the
    // next piece may not have arrived, and returning a short read is exactly what a DataSource is
    // allowed to do. Returns -1 at end of file.
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Reader closed")
        if (position >= layout.fileLength) return -1
        if (length == 0) return 0

        val piece = PieceStrategy.pieceAt(layout, position)
        // Re-aim the download at wherever the player is actually reading. This is what makes a seek
        // work: without it the swarm keeps feeding the pieces after the old read head.
        applyPlan(position)
        awaitPiece(piece)

        // Don't read past the end of the verified piece, or past the end of the file.
        val pieceEnd = pieceEndInFile(piece)
        val available = (minOf(pieceEnd, layout.fileLength) - position).coerceAtMost(length.toLong())
        if (available <= 0) return -1

        return synchronized(raf) {
            if (closed) throw IOException("Reader closed")
            raf.seek(position)
            raf.read(buffer, offset, available.toInt())
        }
    }

    // Exclusive end offset, in file coordinates, of `piece`.
    private fun pieceEndInFile(piece: Int): Long =
        (piece + 1).toLong() * layout.pieceLength - layout.fileOffset

    private fun applyPlan(position: Long) {
        val plan = PieceStrategy.plan(layout, position)
        plan.priorities.forEach { runCatching { handle.piecePriority(it, Priority.SIX) } }
        // Increasing deadlines express ordering: the piece under the read head is wanted first.
        plan.deadlines.forEachIndexed { index, piece ->
            runCatching { handle.setPieceDeadline(piece, index * DEADLINE_STEP_MS) }
        }
    }

    // Blocks until the piece lands. Polls rather than waiting on an alert because the piece may
    // already be present, and because close() has to be able to break the wait promptly.
    private fun awaitPiece(piece: Int) {
        if (handle.havePiece(piece)) return
        val deadline = System.currentTimeMillis() + PIECE_TIMEOUT_MS
        while (!handle.havePiece(piece)) {
            if (closed) throw IOException("Reader closed while waiting for piece $piece")
            if (System.currentTimeMillis() > deadline) {
                // A real error, not an endless spinner: no peers, a dead torrent, or a stalled
                // swarm all end up here, and the player should say so.
                throw IOException("Timed out waiting for piece $piece of ${file.name}")
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted waiting for piece $piece", e)
            }
        }
    }

    override fun close() {
        // Guarded so a double close() can't decrement the owner's reader count twice and tear down a
        // session another reader is still using.
        if (closed) return
        closed = true
        synchronized(raf) { runCatching { raf.close() } }
        // Deadlines are per-torrent state; leaving them set would keep skewing the scheduler after
        // this reader is gone.
        runCatching { handle.clearPieceDeadlines() }
        runCatching { onClosed(handle, file.absolutePath) }
    }

    companion object {
        // Spread across the deadline window so the piece at the read head is due first.
        private const val DEADLINE_STEP_MS = 1_000

        // How long a single piece may take before playback gives up. Generous: a cold torrent has to
        // find peers through DHT and trackers first, which is the slow part.
        private const val PIECE_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 100L

        private const val METADATA_TIMEOUT_SECONDS = 60L

        // Adds the torrent (if it isn't already in the session), waits for its metadata, narrows the
        // download to the one file being played, and returns a reader positioned over it.
        //
        // Blocking by design: it is called from DataSource.open(), which Media3 already calls off the
        // main thread.
        @Throws(IOException::class)
        fun open(
            session: SessionManager,
            ref: TorrentRef,
            trackers: List<String>,
            saveDir: File,
            onClosed: (TorrentHandle, String) -> Unit,
        ): TorrentFileReader {
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                throw IOException("Could not create torrent save directory: $saveDir")
            }
            val handle = addAndAwaitHandle(session, ref, trackers, saveDir)
            val info = awaitMetadata(handle)
            val files = info.files()
            if (ref.fileIdx >= files.numFiles()) {
                throw IOException("Torrent ${ref.infoHash} has no file at index ${ref.fileIdx}")
            }

            val layout = TorrentFileLayout(
                fileOffset = files.fileOffset(ref.fileIdx),
                fileLength = files.fileSize(ref.fileIdx),
                pieceLength = info.pieceLength(),
            )

            // Subtitle files travel with the video rather than being ignored with everything else.
            // Release groups very often ship them alongside instead of muxing them in, and they are
            // kilobytes against the video's gigabytes — so fetching them costs nothing measurable,
            // while not fetching them means a torrent that has subtitles plays without any.
            val entries = (0 until files.numFiles()).map { index ->
                TorrentFileEntry(index = index, path = files.filePath(index), sizeBytes = files.fileSize(index))
            }
            val subtitles = TorrentSubtitles.pick(entries, ref.fileIdx).filter { candidate ->
                // Subtitle paths come from torrent metadata and are attacker-controlled. Keep the
                // progress total aligned with the candidates that can safely be opened.
                TorrentPaths.containedFile(saveDir, candidate.path) != null
            }

            // Everything else is set to IGNORE. Without this, a season pack would download every
            // episode to play one — the user's data, spent on files they didn't ask for.
            val subtitleIndices = subtitles.mapTo(mutableSetOf()) { it.fileIndex }
            val priorities = Array(files.numFiles()) { index ->
                if (index == ref.fileIdx || index in subtitleIndices) Priority.DEFAULT else Priority.IGNORE
            }
            runCatching { handle.prioritizeFiles(priorities) }
                .onFailure { Log.w(TAG, "Could not narrow the download to file ${ref.fileIdx}", it) }

            // The container's header and trailer, so duration and seeking work before the middle of
            // the file has arrived.
            PieceStrategy.bootstrapPieces(layout).forEach { piece ->
                runCatching {
                    handle.piecePriority(piece, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(piece, 0)
                }
            }

            handle.resume()

            val file = TorrentPaths.containedFile(saveDir, files.filePath(ref.fileIdx))
                ?: throw IOException("The torrent's file path escapes the download directory")
            // libtorrent creates the file when it allocates storage; give it a moment rather than
            // failing the open on a race with the first write.
            awaitFile(file)
            return TorrentFileReader(handle, file, layout, saveDir, subtitles, onClosed)
        }

        private fun addAndAwaitHandle(
            session: SessionManager,
            ref: TorrentRef,
            trackers: List<String>,
            saveDir: File,
        ): TorrentHandle {
            val latch = CountDownLatch(1)
            // AtomicReference, not a plain local: the alert arrives on libtorrent's own thread and
            // is read back on this one.
            val found = AtomicReference<TorrentHandle?>(null)

            // There is no hex-string lookup for a torrent in this API, so the handle is captured from
            // the add alert and matched on its own info-hash.
            val listener = object : AlertListener {
                override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig())

                override fun alert(alert: Alert<*>) {
                    val added = alert as? AddTorrentAlert ?: return
                    val handle = added.handle() ?: return
                    if (!handle.isValid) return
                    if (!handle.infoHash().toHex().equals(ref.infoHash, ignoreCase = true)) return
                    found.set(handle)
                    latch.countDown()
                }
            }

            session.addListener(listener)
            try {
                // Must be the same directory the reader later opens the file from, or the
                // torrent downloads to one place and is read from another.
                session.download(magnetFor(ref.infoHash, trackers), saveDir, null)
                if (!latch.await(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw IOException("Timed out adding torrent ${ref.infoHash}")
                }
                return found.get() ?: throw IOException("Torrent ${ref.infoHash} was added without a handle")
            } finally {
                session.removeListener(listener)
            }
        }

        private fun awaitMetadata(handle: TorrentHandle): org.libtorrent4j.TorrentInfo {
            val deadline = System.currentTimeMillis() + METADATA_TIMEOUT_SECONDS * 1_000
            while (System.currentTimeMillis() < deadline) {
                val info = handle.torrentFile()
                // A magnet carries no file list, so nothing about the torrent's shape is known until
                // metadata arrives from a peer.
                if (info != null && info.isValid) return info
                Thread.sleep(POLL_INTERVAL_MS)
            }
            throw IOException("Timed out waiting for torrent metadata")
        }

        private fun awaitFile(file: File) {
            val deadline = System.currentTimeMillis() + FILE_CREATE_TIMEOUT_MS
            while (!file.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            if (!file.exists()) throw IOException("Torrent storage was never created at $file")
        }

        private const val FILE_CREATE_TIMEOUT_MS = 15_000L

        // Trackers go in the magnet as `tr` parameters. This is the only place the tracker list is
        // used, which is why it travels beside the torrent:// url rather than inside it.
        internal fun magnetFor(infoHash: String, trackers: List<String>): String = buildString {
            append("magnet:?xt=urn:btih:").append(infoHash)
            trackers.forEach { tracker ->
                append("&tr=").append(URLEncoder.encode(tracker, "UTF-8"))
            }
        }
    }
}
