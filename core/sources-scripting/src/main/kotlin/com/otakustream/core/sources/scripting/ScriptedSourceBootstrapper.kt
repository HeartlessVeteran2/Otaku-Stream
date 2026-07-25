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
    // Built concurrently so cold start doesn't serialize one Rhino evaluation after another, and on
    // IO rather than Default: buildSource evaluates the script's top level, where a source is free
    // to call the host `httpGet` — a synchronous OkHttp call. On Default that would park one of a
    // small, CPU-count-sized pool of workers per script and starve unrelated work; IO is sized for
    // blocking calls.
    //
    // Each record is built inside its own failure boundary: a single malformed persisted script is
    // logged and skipped rather than discarding every other source that loaded fine (which is what
    // both the old serial map and a bare awaitAll would do). Mirrors MangayomiBootstrapper.
    // Cancellation propagates, and Errors (a Rhino VM/linkage failure, OOM) propagate too rather
    // than being filed alongside "this script was malformed" — hence Exception, not Throwable.
    suspend fun loadPersistedSources(): List<ScriptedVideoSource> = withContext(Dispatchers.IO) {
        repository.getAll()
            .map { record ->
                async {
                    try {
                        installer.buildSource(record.scriptUrl, record.scriptContent)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e("ScriptedBootstrapper", "Failed to load persisted source: ${record.scriptUrl}", error)
                        null
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}
