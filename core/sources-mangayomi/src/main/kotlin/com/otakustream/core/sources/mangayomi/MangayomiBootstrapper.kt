package com.otakustream.core.sources.mangayomi

import android.util.Log
import com.otakustream.core.database.mangayomi.MangayomiSourceRecord
import com.otakustream.core.database.mangayomi.MangayomiSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Rebuilds installed Mangayomi extensions into live sources at cold start, from the cached script
// text (no network). Mirrors ScriptedSourceBootstrapper; wired into the app-level SourceBootstrapper
// so the rehydrate runs once per process. A single broken extension is skipped, not fatal.
@Singleton
class MangayomiBootstrapper @Inject constructor(
    private val factory: MangayomiSourceFactory,
    private val repository: MangayomiSourceRepository,
) {
    // Built in parallel and without forcing engine bringup: a sequential map here meant cold start
    // paid for one full native QuickJS context after another before the first screen could render,
    // scaling linearly with the number of installed extensions. Each extension was already
    // validated when it was installed, so the fail-fast bringup buys nothing on this path — the
    // engine comes up on first real use instead.
    suspend fun loadPersistedSources(): List<MangayomiVideoSource> = withContext(Dispatchers.Default) {
        repository.getAll()
            .map { record ->
                async {
                    runCatching {
                        factory.create(
                            record.scriptContent,
                            override = record.toMetadata(),
                            prefsJson = record.prefsJson,
                            forceBringup = false,
                        )
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        // Log rather than silently drop, so a broken persisted extension is diagnosable.
                        Log.e("MangayomiBootstrapper", "Failed to load persisted extension: ${record.name}", error)
                        null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}

private fun MangayomiSourceRecord.toMetadata() = MangayomiSourceMetadata(
    id = id,
    name = name,
    lang = lang,
    baseUrl = baseUrl,
    iconUrl = iconUrl,
    version = version,
    isNsfw = isNsfw,
)
