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

    fun setNextResolver(resolver: (suspend () -> Video?)?) {
        this.resolver = resolver
    }

    fun hasResolver(): Boolean = resolver != null

    suspend fun resolveNext(): Video? = runCatching { resolver?.invoke() }
        .getOrElse { error ->
            // kotlin.coroutines.cancellation keeps this module free of kotlinx-coroutines.
            if (error is kotlin.coroutines.cancellation.CancellationException) throw error
            null
        }

    fun clear() {
        resolver = null
    }
}
