package com.otakustream.core.sources.api

import java.util.concurrent.ConcurrentHashMap

// Fires a one-shot "this stream was actually watched to the end" signal from PlayerController
// (core:player, which knows playback position) to MediaDetailsViewModel (feature:sources, which
// knows the episode ↔ AniList mapping) — mirrors the PendingPlayback / PlaybackQueue hand-off
// rather than introducing a new pattern.
//
// Why it exists: progress must sync to AniList only once the episode is genuinely finished, never
// the instant its stream is chosen. The feature layer registers a handler keyed by the resolved
// video url; the player pops it exactly once when playback crosses the finished threshold.
object PlaybackCompletion {
    // core:sources-api stays free of kotlinx-coroutines by design; `suspend` is a language feature,
    // not a dependency, so the handler can suspend without pulling the library in here.
    private val handlers = ConcurrentHashMap<String, suspend () -> Unit>()

    fun register(url: String, handler: suspend () -> Unit) {
        handlers[url] = handler
    }

    fun unregister(url: String) {
        handlers.remove(url)
    }

    // Pops the handler for [url] so it can never fire twice for one play, and returns it (or null
    // when nothing was registered / it already fired). The caller runs it in its own scope.
    fun takeHandler(url: String): (suspend () -> Unit)? = handlers.remove(url)
}
