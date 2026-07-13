package com.otakustream.core.sources.stremio

import com.otakustream.core.database.stremio.StremioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class StremioAddonBootstrapper @Inject constructor(
    private val stremioRepository: StremioRepository,
    private val installer: StremioAddonInstaller,
) {
    // Pure JSON parsing + object construction, no network — cold-start rehydration mirroring
    // ScriptedSourceBootstrapper.
    suspend fun loadPersistedSources(): List<StremioVideoSource> = withContext(Dispatchers.Default) {
        stremioRepository.getAllAddons().flatMap { record ->
            runCatching { installer.buildSources(record.manifestUrl, record.manifestJson) }.getOrDefault(emptyList())
        }
    }
}
