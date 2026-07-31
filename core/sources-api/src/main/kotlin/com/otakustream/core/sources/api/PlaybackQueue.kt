package com.otakustream.core.sources.api

// Generic "give me the next video" hand-off between MediaDetailsViewModel (feature:sources,
// which knows what "next episode" means) and PlayerController (core:player, which doesn't and
// shouldn't) — mirrors the PendingPlayback pattern rather than introducing a new one.
object PlaybackQueue {
    // core:sources-api is a pure, dependency-free Kotlin module by design — a plain @Volatile
    // field avoids pulling in kotlinx-coroutines here just for this flag. Consumers that need a
    // reactive UI binding (MediaDetailsViewModel) wrap it in their own StateFlow.
    @Volatile
    var autoPlayEnabled: Boolean = true

    @Volatile
    private var resolver: (suspend () -> Video?)? = null

    // Writes are serialized so replaceResolverIfCurrent's compare and set can't be interleaved with
    // a plain install; reads stay lock-free on the @Volatile field.
    private val writeLock = Any()

    fun setNextResolver(resolver: (suspend () -> Video?)?) {
        synchronized(writeLock) { this.resolver = resolver }
    }

    // Re-arms the chain, but only if `current` is still the installed resolver.
    //
    // An auto-play resolver suspends while it fetches the next episode's stream, and the user can
    // start a different playback in that window — which installs a resolver for the new chain. The
    // stale resolver then finishes and re-arms unconditionally, replacing it, and auto-play carries
    // on through the episode list the user has left. Identity is the check that matters here: "am I
    // still the resolver anyone would call?" Returns false when the caller has been superseded.
    fun replaceResolverIfCurrent(current: suspend () -> Video?, next: (suspend () -> Video?)?): Boolean =
        synchronized(writeLock) {
            if (resolver !== current) return false
            resolver = next
            true
        }

    fun hasResolver(): Boolean = resolver != null

    suspend fun resolveNext(): Video? = runCatching { resolver?.invoke() }
        .getOrElse { error ->
            // kotlin.coroutines.cancellation keeps this module free of kotlinx-coroutines.
            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
            null
        }

    fun clear() {
        synchronized(writeLock) { resolver = null }
    }
}
