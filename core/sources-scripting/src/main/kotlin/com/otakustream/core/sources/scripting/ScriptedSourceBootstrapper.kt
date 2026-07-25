package com.otakustream.core.sources.scripting

import android.util.Log
import com.otakustream.core.database.scripted.ScriptedSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ScriptedSourceBootstrapper @Inject constructor(
    private val repository: ScriptedSourceRepository,
    private val installer: ScriptSourceInstaller,
) {
    // Compiling/evaluating cached scripts is pure CPU work (no network) — keep it off Main, and
    // build them concurrently so cold start doesn't serialize one Rhino evaluation after another.
    //
    // Each record is built inside its own failure boundary: a single malformed persisted script is
    // logged and skipped rather than discarding every other source that loaded fine (which is what
    // both the old serial map and a bare awaitAll would do). Mirrors MangayomiBootstrapper.
    suspend fun loadPersistedSources(): List<ScriptedVideoSource> = withContext(Dispatchers.Default) {
        repository.getAll()
            .map { record ->
                async {
                    runCatching { installer.buildSource(record.scriptUrl, record.scriptContent) }
                        .getOrElse { error ->
                            if (error is CancellationException) throw error
                            Log.e("ScriptedBootstrapper", "Failed to load persisted source: ${record.scriptUrl}", error)
                            null
                        }
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}
