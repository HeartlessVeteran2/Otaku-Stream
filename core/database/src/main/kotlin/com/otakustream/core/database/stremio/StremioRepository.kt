package com.otakustream.core.database.stremio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class StremioAddonRecord(
    val manifestUrl: String,
    val manifestJson: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
)

data class StremioCatalogToggle(val manifestUrl: String, val type: String, val id: String, val enabled: Boolean)

interface StremioRepository {
    fun observeAddons(): Flow<List<StremioAddonRecord>>
    suspend fun getAllAddons(): List<StremioAddonRecord>
    // The only way to save an add-on. A plain saveAddon existed alongside this and had no callers
    // left once the installer switched over — and it is the destructive one, carrying the caller's
    // `enabled` and `priority` over whatever the user had chosen. Leaving a destructive twin next to
    // the safe one is how that comes back; see LibraryRepository.addIfAbsent for the same call.
    suspend fun saveAddonKeepingUserSettings(record: StremioAddonRecord)
    suspend fun deleteAddon(manifestUrl: String)
    suspend fun setAddonEnabled(manifestUrl: String, enabled: Boolean)

    // Renumbers the whole list in one transaction. Replaces the pairwise priority swap, which could
    // not survive a cancellation and could not break an existing tie.
    suspend fun setAddonOrder(manifestUrlsInOrder: List<String>)

    fun observeCatalogToggles(manifestUrl: String): Flow<List<StremioCatalogToggle>>
    suspend fun setCatalogEnabled(manifestUrl: String, type: String, id: String, enabled: Boolean)
    suspend fun getDisabledCatalogKeys(): Set<Triple<String, String, String>>

    suspend fun getServerBaseUrl(): String?
    fun observeServerBaseUrl(): Flow<String?>
    suspend fun saveServerBaseUrl(baseUrl: String)
    suspend fun clearServerBaseUrl()
}

class StremioRepositoryImpl @Inject constructor(
    private val dao: StremioDao,
) : StremioRepository {
    override fun observeAddons(): Flow<List<StremioAddonRecord>> =
        dao.observeAddons().map { list -> list.map { it.toRecord() } }

    override suspend fun getAllAddons(): List<StremioAddonRecord> = dao.getAllAddons().map { it.toRecord() }

    override suspend fun saveAddonKeepingUserSettings(record: StremioAddonRecord) =
        dao.upsertAddonKeepingUserSettings(record.toEntity())

    override suspend fun deleteAddon(manifestUrl: String) = dao.deleteAddon(manifestUrl)

    override suspend fun setAddonEnabled(manifestUrl: String, enabled: Boolean) = dao.setAddonEnabled(manifestUrl, enabled)

    override suspend fun setAddonOrder(manifestUrlsInOrder: List<String>) = dao.setAddonOrder(manifestUrlsInOrder)

    override fun observeCatalogToggles(manifestUrl: String): Flow<List<StremioCatalogToggle>> =
        dao.observeCatalogToggles(manifestUrl).map { list -> list.map { it.toToggle() } }

    override suspend fun setCatalogEnabled(manifestUrl: String, type: String, id: String, enabled: Boolean) =
        dao.upsertCatalogToggle(StremioCatalogToggleEntity(manifestUrl = manifestUrl, type = type, id = id, enabled = enabled))

    override suspend fun getDisabledCatalogKeys(): Set<Triple<String, String, String>> =
        dao.getAllCatalogToggles().filterNot { it.enabled }.map { Triple(it.manifestUrl, it.type, it.id) }.toSet()

    override suspend fun getServerBaseUrl(): String? = dao.getServerConfig()?.baseUrl
    override fun observeServerBaseUrl(): Flow<String?> = dao.observeServerConfig().map { it?.baseUrl }
    override suspend fun saveServerBaseUrl(baseUrl: String) = dao.upsertServerConfig(StremioServerConfigEntity(baseUrl = baseUrl))
    override suspend fun clearServerBaseUrl() = dao.clearServerConfig()
}

private fun StremioAddonRecord.toEntity() = StremioAddonEntity(
    manifestUrl = manifestUrl,
    manifestJson = manifestJson,
    name = name,
    enabled = enabled,
    priority = priority,
)

private fun StremioAddonEntity.toRecord() =
    StremioAddonRecord(manifestUrl = manifestUrl, manifestJson = manifestJson, name = name, enabled = enabled, priority = priority)

private fun StremioCatalogToggleEntity.toToggle() = StremioCatalogToggle(manifestUrl = manifestUrl, type = type, id = id, enabled = enabled)
