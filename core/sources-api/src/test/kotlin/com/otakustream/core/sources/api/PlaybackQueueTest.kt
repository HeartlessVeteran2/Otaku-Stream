package com.otakustream.core.sources.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

// PlaybackQueue is a process-wide singleton holding one auto-play chain, and the thing that goes
// wrong with it is a timing problem: a resolver suspends to fetch the next episode's stream, the
// user starts a different show in that window, and the resolver then finishes and re-arms. What it
// re-arms with is the *old* show's chain, so auto-play carries on through an episode list nobody is
// watching. These tests pin the ownership rule that prevents it — a resolver may only replace itself.
class PlaybackQueueTest {

    @Before
    fun reset() {
        PlaybackQueue.clear()
    }

    @Test
    fun `a resolver that is still installed may re-arm the chain`() {
        val first = resolverReturning("first")
        val second = resolverReturning("second")

        PlaybackQueue.setNextResolver(first)

        assertTrue(PlaybackQueue.replaceResolverIfCurrent(first, second))
        assertEquals("second", runSync { PlaybackQueue.resolveNext() }?.url)
    }

    @Test
    fun `a superseded resolver cannot overwrite the newer chain`() {
        val stale = resolverReturning("stale")
        val staleNext = resolverReturning("stale-next")
        val newPlayback = resolverReturning("new-playback")

        // The stale resolver was armed, then the user started something else, which installs its own
        // chain outright. The stale resolver is still running at this point — it is mid-fetch.
        PlaybackQueue.setNextResolver(stale)
        PlaybackQueue.setNextResolver(newPlayback)

        assertFalse(PlaybackQueue.replaceResolverIfCurrent(stale, staleNext))
        // The newer playback's chain is untouched: this is the whole point. Overwriting it would
        // auto-play the abandoned show's next episode on top of what the user actually chose.
        assertEquals("new-playback", runSync { PlaybackQueue.resolveNext() }?.url)
    }

    @Test
    fun `a superseded resolver cannot retire the newer chain either`() {
        val stale = resolverReturning("stale")
        val newPlayback = resolverReturning("new-playback")

        PlaybackQueue.setNextResolver(stale)
        PlaybackQueue.setNextResolver(newPlayback)

        // Reaching the end of its own episode list, the stale resolver tries to clear the queue.
        // Refusing matters as much as refusing the replacement above: an unguarded clear would drop
        // the Next button on a playback that genuinely has a next episode.
        assertFalse(PlaybackQueue.replaceResolverIfCurrent(stale, null))
        assertTrue(PlaybackQueue.hasResolver())
    }

    @Test
    fun `the last resolver in a chain retires it`() {
        val last = resolverReturning("last")
        PlaybackQueue.setNextResolver(last)

        assertTrue(PlaybackQueue.replaceResolverIfCurrent(last, null))
        // hasResolver is what drives the player's Next button, so leaving a resolver installed here
        // is precisely how a dead Next appears on the final episode.
        assertFalse(PlaybackQueue.hasResolver())
        assertNull(runSync { PlaybackQueue.resolveNext() })
    }

    @Test
    fun `re-arming an empty queue is refused rather than reviving a cleared chain`() {
        val orphan = resolverReturning("orphan")
        PlaybackQueue.setNextResolver(orphan)
        // Which is what PlayerController does for a direct play — a file-picker or pasted-link
        // playback that has no episode list behind it.
        PlaybackQueue.clear()

        assertFalse(PlaybackQueue.replaceResolverIfCurrent(orphan, resolverReturning("revived")))
        assertFalse(PlaybackQueue.hasResolver())
    }

    private fun resolverReturning(url: String): suspend () -> Video? = { Video(url = url, quality = "720p") }

    // core:sources-api is deliberately free of kotlinx-coroutines, so there is no runBlocking here.
    // These resolvers never actually suspend, so starting the coroutine on the stdlib primitives
    // completes it before startCoroutine returns.
    private fun <T> runSync(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome) { "resolver suspended; this helper only runs non-suspending bodies" }
            .getOrThrow()
    }
}
