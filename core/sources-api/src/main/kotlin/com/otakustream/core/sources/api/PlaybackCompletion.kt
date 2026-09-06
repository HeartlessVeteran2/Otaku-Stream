package com.otakustream.core.sources.api

// Fires a one-shot "this stream was actually watched to the end" signal from PlayerController
// (core:player, which knows playback position) to MediaDetailsViewModel (feature:sources, which
// knows the episode ↔ AniList mapping) — mirrors the PendingPlayback / PlaybackQueue hand-off
// rather than introducing a new pattern.
//
// Why it exists: progress must sync to AniList only once the episode is genuinely finished, never
// the instant its stream is chosen. The feature layer registers a handler keyed by the resolved
// video url; the player pops it exactly once when playback crosses the finished threshold.
object PlaybackCompletion {
    // Bounded, because the only exit used to be finishing the episode.
    //
    // A handler is registered every time a stream starts and popped only when playback crosses the
    // finished threshold — so every episode the user sampled, skipped, or backed out of left one
    // behind for the life of the process. Each is small (the closures capture the singleton manager
    // and three primitives, never a ViewModel — see registerAniListSync), but nothing ever removed
    // them, and browsing is exactly the activity that starts a lot of streams without finishing any.
    //
    // An LRU rather than an expiry: only one stream plays at a time, and the most that are ever
    // legitimately live at once is two — the current episode and the next one auto-play resolved
    // ahead of it. MAX is well clear of that, so eviction can't reach a handler that still has an
    // episode behind it. Same LinkedHashMap idiom AniListClient already uses for its detail cache.
    //
    // core:sources-api stays free of kotlinx-coroutines by design; `suspend` is a language feature,
    // not a dependency, so the handler can suspend without pulling the library in here. That also
    // rules out a coroutine-based cache, hence the plain map under a lock.
    private const val MAX_PENDING = 8

    private val handlers = object : LinkedHashMap<String, suspend () -> Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, suspend () -> Unit>): Boolean =
            size > MAX_PENDING
    }

    fun register(url: String, handler: suspend () -> Unit) {
        synchronized(handlers) { handlers[url] = handler }
    }

    // Pops the handler for [url] so it can never fire twice for one play, and returns it (or null
    // when nothing was registered / it already fired). The caller runs it in its own scope.
    fun takeHandler(url: String): (suspend () -> Unit)? = synchronized(handlers) { handlers.remove(url) }
}
