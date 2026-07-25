package com.otakustream.feature.sources

import com.otakustream.core.sources.mangayomi.MangayomiBootstrapper
import com.otakustream.core.sources.scripting.ScriptedSourceBootstrapper
import com.otakustream.core.sources.stremio.StremioAddonBootstrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
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
    private val mangayomiBootstrapper: MangayomiBootstrapper,
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
        val deferred = job ?: mutex.withLock {
            job ?: scope.async { bootstrap() }.also { job = it }
        }
        try {
            deferred.await()
        } catch (t: Throwable) {
            // Never leave a failed or cancelled attempt cached: otherwise every future caller
            // would await the same dead Deferred and persisted sources would never register
            // until the process restarts. Clear it (only if it's still the one we awaited, so a
            // concurrently-started fresh run isn't clobbered) so the next call retries.
            mutex.withLock { if (job === deferred) job = null }
            throw t
        }
    }

    // The three source kinds are independent (separate tables, separate engines), so they load
    // concurrently rather than one after another — the first screen waits on the slowest, not the
    // sum.
    //
    // supervisorScope, NOT coroutineScope: under coroutineScope a single failing loader cancels its
    // siblings, and awaiting a cancelled sibling yields CancellationException, which the rethrow
    // below propagates — so one broken source kind would take the whole bootstrap down and leave
    // Home/Catalog with nothing registered. With a supervisor, each await fails (or succeeds) on its
    // own and the runCatching per loader actually isolates it.
    private suspend fun bootstrap() = supervisorScope {
        val loaders = listOf(
            async { scriptedBootstrapper.loadPersistedSources() },
            async { stremioBootstrapper.loadPersistedSources() },
            async { mangayomiBootstrapper.loadPersistedSources() },
        )
        loaders.forEach { loader ->
            // Exception, not Throwable: a loader that died of a VM/linkage Error or OOM is not
            // "this source kind had bad data", and swallowing it here would hide it behind a
            // half-populated registry. Let it take the bootstrap down where it's visible.
            try {
                loader.await().forEach(sourceRepository::registerDynamic)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Already logged by the individual bootstrappers, which report per-record detail.
            }
        }
    }
}
