package com.otakustream.core.torrent

// Where one file sits inside a torrent, which is all the piece arithmetic needs.
//
// `fileOffset` is the byte offset of the file within the torrent's flat concatenation of all files,
// and it is very often *not* piece-aligned: in a multi-file torrent the file's first piece also holds
// the tail of the previous file. Getting that wrong is the classic way to compute an index one piece
// off and stall on a piece that was never needed.
data class TorrentFileLayout(
    val fileOffset: Long,
    val fileLength: Long,
    val pieceLength: Int,
) {
    // Global piece index of the file's first byte.
    val firstPiece: Int get() = (fileOffset / pieceLength).toInt()

    // Global piece index of the file's last byte. An empty file has no bytes and so no last piece
    // beyond its first — clamped rather than going negative.
    val lastPiece: Int
        get() = if (fileLength <= 0) firstPiece else ((fileOffset + fileLength - 1) / pieceLength).toInt()
}

// The pieces to ask for, given where the player is reading from.
//
// `deadlines` is ordered nearest-first: libtorrent takes a millisecond deadline per piece, so the
// caller assigns increasing deadlines down the list to express "this one first". `priorities` is the
// wider set to raise above default so the swarm keeps feeding ahead of the read head.
data class PiecePlan(
    val deadlines: List<Int>,
    val priorities: List<Int>,
)

// Pure piece arithmetic, deliberately separated from the DataSource and the libtorrent session so it
// can be tested without a swarm, a device, or any native code — which is the only part of torrent
// streaming that *can* be tested here.
object PieceStrategy {

    // How many pieces ahead of the read head get an explicit deadline. Small on purpose: deadlines
    // are a scheduling hint, and setting them far ahead just tells libtorrent everything is urgent,
    // which is the same as telling it nothing.
    const val DEFAULT_DEADLINE_WINDOW = 8

    // How many pieces ahead are lifted above default priority. Wider than the deadline window so
    // there is a buffer already arriving by the time the read head reaches it.
    const val DEFAULT_PRIORITY_WINDOW = 32

    // Global piece index containing the byte at `positionInFile`.
    fun pieceAt(layout: TorrentFileLayout, positionInFile: Long): Int {
        val clamped = positionInFile.coerceIn(0L, maxOf(0L, layout.fileLength - 1))
        return ((layout.fileOffset + clamped) / layout.pieceLength).toInt()
    }

    // Byte offset of `positionInFile` within its piece — where a read starts inside the piece buffer.
    fun offsetInPiece(layout: TorrentFileLayout, positionInFile: Long): Int =
        ((layout.fileOffset + positionInFile) % layout.pieceLength).toInt()

    // The plan for reading at `positionInFile`. Both windows are clamped to the file's own last
    // piece: running past it would prioritise pieces belonging to *other* files in the torrent,
    // spending the user's bandwidth on data this playback will never read.
    fun plan(
        layout: TorrentFileLayout,
        positionInFile: Long,
        deadlineWindow: Int = DEFAULT_DEADLINE_WINDOW,
        priorityWindow: Int = DEFAULT_PRIORITY_WINDOW,
    ): PiecePlan {
        val head = pieceAt(layout, positionInFile)
        val last = layout.lastPiece
        return PiecePlan(
            deadlines = pieceRange(head, deadlineWindow, last),
            priorities = pieceRange(head, priorityWindow, last),
        )
    }

    // The pieces a container needs before it can report a duration or seek at all: the header at the
    // start, and the end, because MP4 commonly stores its `moov` atom last and a player that can't
    // read it will refuse the file or treat it as unseekable. Requested once when the file is opened,
    // not repeatedly.
    fun bootstrapPieces(layout: TorrentFileLayout): List<Int> =
        listOf(layout.firstPiece, layout.lastPiece).distinct()

    private fun pieceRange(head: Int, window: Int, last: Int): List<Int> {
        if (window <= 0 || head > last) return emptyList()
        val end = minOf(head + window - 1, last)
        return (head..end).toList()
    }
}
