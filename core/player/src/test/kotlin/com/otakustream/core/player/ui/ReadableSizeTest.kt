package com.otakustream.core.player.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

// The size under each row in the torrent picker. It answers one question — is this row an episode,
// or something that slipped past the video filter — so the only thing it has to get right is the
// order of magnitude, and never round a small file away to nothing.
//
// The decimal separator is the reader's, not ours: "1,4 GB" is what a German viewer should see, and
// String.format's default locale is what makes that happen. That was an accident before this test
// said so, and an accident in both directions — the assertions below only passed on a runner whose
// default locale uses a dot, and a "fix" to Locale.ROOT would have quietly made the app print a
// dot to everyone.
class ReadableSizeTest {

    private val original = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun `an episode reads in gigabytes to one decimal`() {
        assertEquals("1.4 GB", readableSize((1.4 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `the decimal separator is the reader's own`() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals("1,4 GB", readableSize((1.4 * 1024 * 1024 * 1024).toLong()))
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
