package com.otakustream.core.sources.mangayomi

import android.util.Log
import com.otakustream.core.database.mangayomi.MangayomiSourceRecord
import com.otakustream.core.database.mangayomi.MangayomiSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    suspend fun loadPersistedSources(): List<MangayomiVideoSource> = withContext(Dispatchers.Default) {
        repository.getAll().mapNotNull { record ->
            runCatching { factory.create(record.scriptContent, override = record.toMetadata()) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    // Log rather than silently drop, so a broken persisted extension is diagnosable.
                    Log.e("MangayomiBootstrapper", "Failed to load persisted extension: ${record.name}", error)
                    null
                }
        }
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
