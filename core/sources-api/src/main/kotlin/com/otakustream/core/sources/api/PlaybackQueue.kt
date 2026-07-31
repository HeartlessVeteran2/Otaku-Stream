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

    // Which *chain* the installed resolver belongs to. Bumped whenever ownership of the queue
    // changes hands — a new playback installing its own resolver, or a direct play clearing it —
    // but not when a chain re-arms itself, which is the same chain advancing by one episode.
    //
    // This is the thing that makes resolveNext safe. A resolver suspends for as long as it takes to
    // fetch the next episode's stream, and the user can start a different show in that window. Every
    // guard placed *inside* the resolver still leaves the gap between its last check and the moment
    // it returns, and a video returned through that gap is played on top of the user's actual
    // choice. Only the queue can close it, because only the queue sees both ends of the call.
    @Volatile
    private var chain: Long = 0

    // Writes are serialized so replaceResolverIfCurrent's compare and set can't be interleaved with
    // a plain install; reads stay lock-free on the @Volatile fields.
    private val writeLock = Any()

    fun setNextResolver(resolver: (suspend () -> Video?)?) {
        synchronized(writeLock) {
            this.resolver = resolver
            chain++
        }
    }

    // Re-arms the chain, but only if `current` is still the installed resolver.
    //
    // Deliberately does not bump `chain`: this is one chain advancing to its next episode, not a
    // change of ownership, and a resolver must not invalidate its own in-flight result. Identity is
    // the check that matters — "am I still the resolver anyone would call?" Returns false when the
    // caller has been superseded, which is how a stale chain learns to stop re-arming.
    fun replaceResolverIfCurrent(current: suspend () -> Video?, next: (suspend () -> Video?)?): Boolean =
        synchronized(writeLock) {
            if (resolver !== current) return false
            resolver = next
            true
        }

    fun hasResolver(): Boolean = resolver != null

    suspend fun resolveNext(): Video? {
        val armed: (suspend () -> Video?)?
        val startedAt: Long
        synchronized(writeLock) {
            armed = resolver
            startedAt = chain
        }
        val video = runCatching { armed?.invoke() }
            .getOrElse { error ->
                // kotlin.coroutines.cancellation keeps this module free of kotlinx-coroutines.
                if (error is kotlin.coroutines.cancellation.CancellationException) throw error
                null
            }
        // The queue changed hands while this was resolving, so whatever came back belongs to a
        // playback nobody is watching any more. Discarding it is the whole point: the caller reads
        // null as "there is no next episode", which is exactly right for a chain that has been
        // superseded or cleared.
        return synchronized(writeLock) { if (chain == startedAt) video else null }
    }

    fun clear() {
        synchronized(writeLock) {
            resolver = null
            chain++
        }
    }
}
