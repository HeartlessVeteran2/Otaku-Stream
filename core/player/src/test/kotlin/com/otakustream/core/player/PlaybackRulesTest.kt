package com.otakustream.core.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRulesTest {

    // The regression this file exists for.
    //
    // The old clamp was `coerceIn(0L, duration.coerceAtLeast(0L))`. C.TIME_UNSET is a large
    // negative, so coerceAtLeast(0) made the upper bound zero and every seek landed at 0:00 while
    // the duration was still unknown — the whole of a torrent's startup.
    @Test
    fun `seeking before the duration is known keeps the target`() {
        assertEquals(90_000L, clampSeekPosition(90_000L, C.TIME_UNSET))
        assertEquals(10_000L, clampSeekPosition(10_000L, C.TIME_UNSET))
    }

    // Any negative is the same sentinel in practice, so all of them mean "not known yet".
    @Test
    fun `a negative duration is treated as unknown, not as a bound`() {
        assertEquals(30_000L, clampSeekPosition(30_000L, -1L))
        assertEquals(30_000L, clampSeekPosition(30_000L, Long.MIN_VALUE))
    }

    // Zero is a real answer, not a sentinel: a zero-length item has exactly one valid position.
    @Test
    fun `a zero duration still bounds the target`() {
        assertEquals(0L, clampSeekPosition(30_000L, 0L))
    }

    @Test
    fun `a known duration still bounds the target`() {
        assertEquals(60_000L, clampSeekPosition(90_000L, 60_000L))
        assertEquals(45_000L, clampSeekPosition(45_000L, 60_000L))
        assertEquals(60_000L, clampSeekPosition(60_000L, 60_000L))
    }

    // Rewinding past the start is the one case the old code did get right, and it has to keep
    // working: a double-tap-back five seconds in must land at zero, not at a negative position.
    @Test
    fun `seeking before the start clamps to zero in every case`() {
        assertEquals(0L, clampSeekPosition(-5_000L, 60_000L))
        assertEquals(0L, clampSeekPosition(-5_000L, C.TIME_UNSET))
        assertEquals(0L, clampSeekPosition(-5_000L, 0L))
    }

    @Test
    fun `a usable torrent is not refused`() {
        assertNull(
            torrentRefusalMessage(
                isAvailable = true,
                torrentsEnabled = true,
                unmeteredOnly = true,
                isOnUnmeteredNetwork = true,
            ),
        )
        // Unmetered-only off means the network never matters.
        assertNull(
            torrentRefusalMessage(
                isAvailable = true,
                torrentsEnabled = true,
                unmeteredOnly = false,
                isOnUnmeteredNetwork = false,
            ),
        )
    }

    // Each refusal names its own cause, because the three need different things from the user: a
    // device limit they cannot fix, a switch to turn on, or a network to leave.
    @Test
    fun `each reason to refuse says which one it was`() {
        val unavailable = torrentRefusalMessage(false, torrentsEnabled = true, unmeteredOnly = false, isOnUnmeteredNetwork = true)
        val disabled = torrentRefusalMessage(true, torrentsEnabled = false, unmeteredOnly = false, isOnUnmeteredNetwork = true)
        val metered = torrentRefusalMessage(true, torrentsEnabled = true, unmeteredOnly = true, isOnUnmeteredNetwork = false)

        assertEquals("Torrent playback isn't available on this device.", unavailable)
        assertEquals("Torrent playback is turned off. Turn it on in Settings to play this.", disabled)
        assertEquals(
            "Torrents are set to unmetered networks only, and this connection is metered.",
            metered,
        )
    }

    // Device availability is checked first: a 32-bit device has no torrent engine at all, so
    // telling that user to go turn a setting on would send them somewhere that cannot help.
    @Test
    fun `an unavailable engine is reported ahead of the settings`() {
        assertEquals(
            "Torrent playback isn't available on this device.",
            torrentRefusalMessage(
                isAvailable = false,
                torrentsEnabled = false,
                unmeteredOnly = true,
                isOnUnmeteredNetwork = false,
            ),
        )
    }
}
