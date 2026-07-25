package com.otakustream.core.sources.scripting

import com.otakustream.core.database.scripted.ScriptedSourceRepository
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
    suspend fun loadPersistedSources(): List<ScriptedVideoSource> = withContext(Dispatchers.Default) {
        repository.getAll()
            .map { record -> async { installer.buildSource(record.scriptUrl, record.scriptContent) } }
            .awaitAll()
    }
}
