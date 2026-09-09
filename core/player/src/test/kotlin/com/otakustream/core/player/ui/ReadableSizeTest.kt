package com.otakustream.core.player.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// The size under each row in the torrent picker. It answers one question — is this row an episode,
// or something that slipped past the video filter — so the only thing it has to get right is the
// order of magnitude, and never round a small file away to nothing.
class ReadableSizeTest {

    @Test
    fun `an episode reads in gigabytes to one decimal`() {
        assertEquals("1.4 GB", readableSize((1.4 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `a smaller episode reads in whole megabytes`() {
        assertEquals("350 MB", readableSize(350L * 1024 * 1024))
    }

    // The row this exists for: something far too small to be an episode. "0 MB" would hide exactly
    // what the size is there to reveal.
    @Test
    fun `a file too small to be an episode does not round to zero`() {
        assertEquals("40 KB", readableSize(40L * 1024))
        assertEquals("0 KB", readableSize(0L))
    }

    @Test
    fun `the boundaries land on the larger unit`() {
        assertEquals("1 MB", readableSize(1024L * 1024))
        assertEquals("1.0 GB", readableSize(1024L * 1024 * 1024))
    }
}
