package com.otakustream.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// This is the only part of torrent streaming that can be verified without a device and a live swarm,
// so it carries the weight. The cases that matter are the ones that produce a stall rather than an
// error: an index one piece off, or a window that walks past the file into a neighbour's pieces.
class PieceStrategyTest {

    private val pieceLength = 1024

    // A file starting mid-piece, which is the common multi-file case: piece 2 holds the tail of
    // whatever came before, and this file's first byte is 512 bytes into it.
    private val unaligned = TorrentFileLayout(
        fileOffset = 2 * 1024L + 512,
        fileLength = 10_000,
        pieceLength = pieceLength,
    )

    // A single-file torrent: the file starts exactly at the torrent's first byte.
    private val aligned = TorrentFileLayout(fileOffset = 0, fileLength = 10_000, pieceLength = pieceLength)

    @Test
    fun `first and last piece of an aligned file`() {
        assertEquals(0, aligned.firstPiece)
        // 10_000 bytes over 1024-byte pieces: last byte at 9_999 -> piece 9.
        assertEquals(9, aligned.lastPiece)
    }

    @Test
    fun `first and last piece of a file starting mid-piece`() {
        assertEquals(2, unaligned.firstPiece)
        // Last byte is at global offset 2560 + 9999 = 12559 -> piece 12.
        assertEquals(12, unaligned.lastPiece)
    }

    @Test
    fun `piece index accounts for the file's offset in the torrent`() {
        // Reading the file's very first byte must land on the piece containing it, not on piece 0.
        assertEquals(2, PieceStrategy.pieceAt(unaligned, 0))
        // 512 bytes in, the piece boundary is crossed.
        assertEquals(3, PieceStrategy.pieceAt(unaligned, 512))
        assertEquals(3, PieceStrategy.pieceAt(unaligned, 1023))
        assertEquals(4, PieceStrategy.pieceAt(unaligned, 1536))
    }

    @Test
    fun `offset within the piece accounts for the file's offset`() {
        assertEquals(512, PieceStrategy.offsetInPiece(unaligned, 0))
        assertEquals(0, PieceStrategy.offsetInPiece(unaligned, 512))
        assertEquals(0, PieceStrategy.offsetInPiece(aligned, 0))
        assertEquals(100, PieceStrategy.offsetInPiece(aligned, 100))
    }

    @Test
    fun `a position past the end clamps to the last piece rather than running off`() {
        assertEquals(aligned.lastPiece, PieceStrategy.pieceAt(aligned, 999_999))
        assertEquals(unaligned.lastPiece, PieceStrategy.pieceAt(unaligned, 999_999))
    }

    @Test
    fun `a negative position clamps to the first piece`() {
        assertEquals(aligned.firstPiece, PieceStrategy.pieceAt(aligned, -1))
    }

    @Test
    fun `plan from the start walks forward from the read head`() {
        val plan = PieceStrategy.plan(aligned, positionInFile = 0, deadlineWindow = 4, priorityWindow = 6)
        assertEquals(listOf(0, 1, 2, 3), plan.deadlines)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), plan.priorities)
    }

    @Test
    fun `plan after a seek starts at the new position, not the file start`() {
        // Seeking must not keep prioritising pieces behind the read head — that is bandwidth spent
        // on data the player has already passed.
        val plan = PieceStrategy.plan(aligned, positionInFile = 5_000, deadlineWindow = 3, priorityWindow = 3)
        assertEquals(listOf(4, 5, 6), plan.deadlines)
        assertTrue(plan.deadlines.none { it < 4 })
    }

    @Test
    fun `plan near the end of the file clamps to the file's last piece`() {
        // Without clamping, the window would run into pieces belonging to other files in the torrent.
        val plan = PieceStrategy.plan(aligned, positionInFile = 9_800, deadlineWindow = 8, priorityWindow = 32)
        assertEquals(listOf(9), plan.deadlines)
        assertEquals(listOf(9), plan.priorities)
    }

    @Test
    fun `plan for an unaligned file clamps to that file's pieces`() {
        val plan = PieceStrategy.plan(unaligned, positionInFile = 9_990, deadlineWindow = 8, priorityWindow = 8)
        assertEquals(listOf(12), plan.deadlines)
        assertTrue(plan.priorities.all { it <= unaligned.lastPiece })
    }

    @Test
    fun `deadlines are ordered nearest first`() {
        val plan = PieceStrategy.plan(aligned, positionInFile = 2_048, deadlineWindow = 5)
        assertEquals(plan.deadlines.sorted(), plan.deadlines)
        assertEquals(2, plan.deadlines.first())
    }

    @Test
    fun `a file inside a single piece plans exactly that piece`() {
        val tiny = TorrentFileLayout(fileOffset = 0, fileLength = 100, pieceLength = pieceLength)
        val plan = PieceStrategy.plan(tiny, positionInFile = 0, deadlineWindow = 8, priorityWindow = 32)
        assertEquals(listOf(0), plan.deadlines)
        assertEquals(listOf(0), plan.priorities)
    }

    @Test
    fun `an empty file yields a single piece rather than a negative range`() {
        val empty = TorrentFileLayout(fileOffset = 4096, fileLength = 0, pieceLength = pieceLength)
        assertEquals(4, empty.firstPiece)
        assertEquals(4, empty.lastPiece)
        assertEquals(listOf(4), PieceStrategy.plan(empty, 0).deadlines)
    }

    @Test
    fun `a zero or negative window asks for nothing`() {
        assertTrue(PieceStrategy.plan(aligned, 0, deadlineWindow = 0).deadlines.isEmpty())
        assertTrue(PieceStrategy.plan(aligned, 0, priorityWindow = -1).priorities.isEmpty())
    }

    @Test
    fun `bootstrap asks for the container's header and trailer`() {
        // MP4 often stores its moov atom at the end; without the last piece a player may refuse the
        // file or treat it as unseekable.
        assertEquals(listOf(0, 9), PieceStrategy.bootstrapPieces(aligned))
        assertEquals(listOf(2, 12), PieceStrategy.bootstrapPieces(unaligned))
    }

    @Test
    fun `bootstrap of a single-piece file is not duplicated`() {
        val tiny = TorrentFileLayout(fileOffset = 0, fileLength = 100, pieceLength = pieceLength)
        assertEquals(listOf(0), PieceStrategy.bootstrapPieces(tiny))
    }
}
