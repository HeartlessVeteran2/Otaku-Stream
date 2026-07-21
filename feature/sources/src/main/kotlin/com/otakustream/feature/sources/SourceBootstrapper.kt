package com.otakustream.feature.sources

import com.otakustream.core.sources.scripting.ScriptedSourceBootstrapper
import com.otakustream.core.sources.stremio.StremioAddonBootstrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

// Rehydrates persisted scripted + Stremio sources into the registry exactly once per process.
// Previously HomeViewModel and CatalogViewModel each ran this on their own init, doing the DB
// read/parse twice at startup; centralizing it here runs it a single time and shares the result.
@Singleton
class SourceBootstrapper @Inject constructor(
    private val scriptedBootstrapper: ScriptedSourceBootstrapper,
    private val stremioBootstrapper: StremioAddonBootstrapper,
    private val sourceRepository: SourceRepository,
) {
    // App-scoped so the work survives the ViewModel that first triggered it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    @Volatile
    private var job: Deferred<Unit>? = null

    // Suspends until sources are registered — so a caller's first catalog/home load sees them,
    // preserving the old "bootstrap before first use" ordering. Idempotent: concurrent or later
    // callers await the same one-time run.
    suspend fun ensureStarted() {
        job?.let { return it.await() }
        mutex.withLock {
            (job ?: scope.async { bootstrap() }.also { job = it })
        }.await()
    }

    private suspend fun bootstrap() {
        runCatching { scriptedBootstrapper.loadPersistedSources().forEach(sourceRepository::registerDynamic) }
            .onFailure { if (it is CancellationException) throw it }
        runCatching { stremioBootstrapper.loadPersistedSources().forEach(sourceRepository::registerDynamic) }
            .onFailure { if (it is CancellationException) throw it }
    }
}
