package com.otakustream.core.database.stremio

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StremioDao {
    @Query("SELECT * FROM stremio_addons ORDER BY priority")
    fun observeAddons(): Flow<List<StremioAddonEntity>>

    @Query("SELECT * FROM stremio_addons ORDER BY priority")
    suspend fun getAllAddons(): List<StremioAddonEntity>

    @Upsert
    suspend fun upsertAddon(entity: StremioAddonEntity)

    @Query("DELETE FROM stremio_addons WHERE manifestUrl = :manifestUrl")
    suspend fun deleteAddon(manifestUrl: String)

    @Query("UPDATE stremio_addons SET enabled = :enabled WHERE manifestUrl = :manifestUrl")
    suspend fun setAddonEnabled(manifestUrl: String, enabled: Boolean)

    @Query("UPDATE stremio_addons SET priority = :priority WHERE manifestUrl = :manifestUrl")
    suspend fun setAddonPriority(manifestUrl: String, priority: Int)

    @Query("SELECT * FROM stremio_catalog_toggles WHERE manifestUrl = :manifestUrl")
    fun observeCatalogToggles(manifestUrl: String): Flow<List<StremioCatalogToggleEntity>>

    @Upsert
    suspend fun upsertCatalogToggle(entity: StremioCatalogToggleEntity)

    @Query("SELECT * FROM stremio_catalog_toggles")
    suspend fun getAllCatalogToggles(): List<StremioCatalogToggleEntity>

    @Query("SELECT * FROM stremio_server_config WHERE id = 0")
    suspend fun getServerConfig(): StremioServerConfigEntity?

    @Query("SELECT * FROM stremio_server_config WHERE id = 0")
    fun observeServerConfig(): Flow<StremioServerConfigEntity?>

    @Upsert
    suspend fun upsertServerConfig(entity: StremioServerConfigEntity)

    @Query("DELETE FROM stremio_server_config")
    suspend fun clearServerConfig()
}
