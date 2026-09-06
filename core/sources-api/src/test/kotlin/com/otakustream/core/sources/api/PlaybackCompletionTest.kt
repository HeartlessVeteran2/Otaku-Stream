package com.otakustream.core.sources.api

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// The registry is a process-wide singleton, so these run against real global state. Each test
// clears what it registered, and none of them assume the map starts empty beyond that.
class PlaybackCompletionTest {

    private val registered = mutableListOf<String>()

    private fun register(url: String) {
        registered += url
        PlaybackCompletion.register(url) { }
    }

    @Before
    @After
    fun drain() {
        registered.forEach { PlaybackCompletion.takeHandler(it) }
        registered.clear()
    }

    @Test
    fun `a handler fires once and only once`() {
        register("https://cdn.example/ep1.mp4")

        assertNotNull(PlaybackCompletion.takeHandler("https://cdn.example/ep1.mp4"))
        // Popped, not read: a second completion callback for the same stream must not push progress
        // to AniList twice.
        assertNull(PlaybackCompletion.takeHandler("https://cdn.example/ep1.mp4"))
    }

    @Test
    fun `abandoned playbacks do not accumulate forever`() {
        // Every one of these is a stream the user started and did not finish — browsing, sampling,
        // backing out. Nothing pops those handlers, and before the bound was added they stayed for
        // the life of the process.
        repeat(200) { register("https://cdn.example/abandoned-$it.mp4") }

        val surviving = (0 until 200).count {
            PlaybackCompletion.takeHandler("https://cdn.example/abandoned-$it.mp4") != null
        }
        assert(surviving <= 8) { "expected the registry to stay bounded, found $surviving handlers" }
    }

    @Test
    fun `the most recent playbacks are the ones kept`() {
        // Eviction has to take the oldest, because the live ones are the current episode and the
        // next one auto-play resolved ahead of it — dropping either would silently stop progress
        // syncing for an episode the user actually finished.
        repeat(20) { register("https://cdn.example/old-$it.mp4") }
        register("https://cdn.example/playing-now.mp4")

        assertNotNull(PlaybackCompletion.takeHandler("https://cdn.example/playing-now.mp4"))
        assertNull(PlaybackCompletion.takeHandler("https://cdn.example/old-0.mp4"))
    }
}
