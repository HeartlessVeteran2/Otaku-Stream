package com.otakustream.core.sources.scripting

import com.otakustream.core.database.scripted.ScriptedSourceRepository
import javax.inject.Inject

class ScriptedSourceBootstrapper @Inject constructor(
    private val repository: ScriptedSourceRepository,
    private val installer: ScriptSourceInstaller,
) {
    suspend fun loadPersistedSources(): List<ScriptedVideoSource> =
        repository.getAll().map { record -> installer.buildSource(record.scriptUrl, record.scriptContent) }
}
