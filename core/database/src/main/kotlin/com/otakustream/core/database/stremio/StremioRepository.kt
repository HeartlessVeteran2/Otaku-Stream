package com.otakustream.core.database.stremio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class StremioAddonRecord(val manifestUrl: String, val manifestJson: String, val name: String)

interface StremioRepository {
    fun observeAddons(): Flow<List<StremioAddonRecord>>
    suspend fun getAllAddons(): List<StremioAddonRecord>
    suspend fun saveAddon(record: StremioAddonRecord)
    suspend fun deleteAddon(manifestUrl: String)

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

    override suspend fun saveAddon(record: StremioAddonRecord) = dao.upsertAddon(
        StremioAddonEntity(manifestUrl = record.manifestUrl, manifestJson = record.manifestJson, name = record.name),
    )

    override suspend fun deleteAddon(manifestUrl: String) = dao.deleteAddon(manifestUrl)

    override suspend fun getServerBaseUrl(): String? = dao.getServerConfig()?.baseUrl
    override fun observeServerBaseUrl(): Flow<String?> = dao.observeServerConfig().map { it?.baseUrl }
    override suspend fun saveServerBaseUrl(baseUrl: String) = dao.upsertServerConfig(StremioServerConfigEntity(baseUrl = baseUrl))
    override suspend fun clearServerBaseUrl() = dao.clearServerConfig()
}

private fun StremioAddonEntity.toRecord() = StremioAddonRecord(manifestUrl = manifestUrl, manifestJson = manifestJson, name = name)
