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
    // ScriptedSourceBootstrapper. Disabled addons and disabled catalogs are skipped entirely so
    // they're never queried; getAllAddons() is already priority-ordered, so sources register (and
    // therefore display) in the user's configured order.
    suspend fun loadPersistedSources(): List<StremioVideoSource> = withContext(Dispatchers.Default) {
        val disabledCatalogKeys = stremioRepository.getDisabledCatalogKeys()
        stremioRepository.getAllAddons().filter { it.enabled }.flatMap { record ->
            runCatching {
                // Re-register any stream provider (Torrentio et al.) so catalog-less stream
                // add-ons resolve streams again after a cold start, not just when freshly installed.
                installer.registerStreamProviderIfAny(record.manifestUrl, record.manifestJson)
                installer.buildSources(record.manifestUrl, record.manifestJson) { type, id ->
                    Triple(record.manifestUrl, type, id) !in disabledCatalogKeys
                }
            }.getOrDefault(emptyList())
        }
    }
}
