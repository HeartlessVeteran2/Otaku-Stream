package com.otakustream.core.database.stremio

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StremioDao {
    @Query("SELECT * FROM stremio_addons")
    fun observeAddons(): Flow<List<StremioAddonEntity>>

    @Query("SELECT * FROM stremio_addons")
    suspend fun getAllAddons(): List<StremioAddonEntity>

    @Upsert
    suspend fun upsertAddon(entity: StremioAddonEntity)

    @Query("DELETE FROM stremio_addons WHERE manifestUrl = :manifestUrl")
    suspend fun deleteAddon(manifestUrl: String)

    @Query("SELECT * FROM stremio_server_config WHERE id = 0")
    suspend fun getServerConfig(): StremioServerConfigEntity?

    @Query("SELECT * FROM stremio_server_config WHERE id = 0")
    fun observeServerConfig(): Flow<StremioServerConfigEntity?>

    @Upsert
    suspend fun upsertServerConfig(entity: StremioServerConfigEntity)

    @Query("DELETE FROM stremio_server_config")
    suspend fun clearServerConfig()
}
