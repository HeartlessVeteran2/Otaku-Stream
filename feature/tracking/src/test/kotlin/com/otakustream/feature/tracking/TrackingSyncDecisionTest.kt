package com.otakustream.feature.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Guards the forward-only, fire-on-finish AniList progress sync: the decision must never move
// progress backward and never downgrade a COMPLETED/REPEATING entry. Regressions here silently
// destroy a user's tracked history, so these assertions are the safety net for that logic.
class TrackingSyncDecisionTest {

    @Test
    fun `advances progress for a fresh anime not yet on any list`() {
        val update = decideProgressUpdate(currentStatus = null, currentProgress = 0, finishedEpisode = 1)
        assertEquals(ProgressUpdate(status = "CURRENT", progress = 1), update)
    }

    @Test
    fun `advances a watching entry forward one episode`() {
        val update = decideProgressUpdate(currentStatus = "CURRENT", currentProgress = 4, finishedEpisode = 5)
        assertEquals(ProgressUpdate(status = "CURRENT", progress = 5), update)
    }

    @Test
    fun `rewatching an earlier episode never lowers progress`() {
        // The headline bug: opening episode 1 of a show at progress 12 must not reset it to 1.
        assertNull(decideProgressUpdate(currentStatus = "CURRENT", currentProgress = 12, finishedEpisode = 1))
    }

    @Test
    fun `re-finishing the same episode is a no-op`() {
        assertNull(decideProgressUpdate(currentStatus = "CURRENT", currentProgress = 5, finishedEpisode = 5))
    }

    @Test
    fun `rewatching episode 1 of a completed show keeps it completed`() {
        // COMPLETED at progress 24, rewatch episode 1 → leave the completion untouched.
        assertNull(decideProgressUpdate(currentStatus = "COMPLETED", currentProgress = 24, finishedEpisode = 1))
    }

    @Test
    fun `advancing a completed rewatch keeps the terminal status`() {
        // If somehow ahead of the recorded progress on a COMPLETED/REPEATING entry, advance the
        // number but do not flip the status back to CURRENT.
        assertEquals(
            ProgressUpdate(status = "COMPLETED", progress = 25),
            decideProgressUpdate(currentStatus = "COMPLETED", currentProgress = 24, finishedEpisode = 25),
        )
        assertEquals(
            ProgressUpdate(status = "REPEATING", progress = 3),
            decideProgressUpdate(currentStatus = "REPEATING", currentProgress = 2, finishedEpisode = 3),
        )
    }

    @Test
    fun `non-positive episodes are ignored`() {
        assertNull(decideProgressUpdate(currentStatus = null, currentProgress = 0, finishedEpisode = 0))
        assertNull(decideProgressUpdate(currentStatus = null, currentProgress = 0, finishedEpisode = -1))
    }

    @Test
    fun `whole episode numbers map to progress and specials do not`() {
        assertEquals(1, 1.0f.toWholeEpisodeOrNull())
        assertEquals(12, 12.0f.toWholeEpisodeOrNull())
        // Episode 0 (prologue) and fractional specials (6.5) must not be truncated into a real
        // episode number — they return null so the sync skips them entirely.
        assertNull(0.0f.toWholeEpisodeOrNull())
        assertNull(6.5f.toWholeEpisodeOrNull())
    }
}
