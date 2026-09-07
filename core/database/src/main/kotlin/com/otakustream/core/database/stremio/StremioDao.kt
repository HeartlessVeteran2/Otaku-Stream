package com.otakustream.core.database.stremio

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StremioDao {
    // manifestUrl is a tiebreak, not decoration. Priorities are only unique if every write keeps
    // them unique, and they were not: add-ons installed through "Add by URL" all defaulted to 0.
    // With ties, SQLite may return them in any order, so the list the reorder buttons index into
    // could differ from one emission to the next — and "move this one up" would move a different
    // row than the one that was tapped.
    @Query("SELECT * FROM stremio_addons ORDER BY priority, manifestUrl")
    fun observeAddons(): Flow<List<StremioAddonEntity>>

    @Query("SELECT * FROM stremio_addons ORDER BY priority, manifestUrl")
    suspend fun getAllAddons(): List<StremioAddonEntity>

    @Upsert
    suspend fun upsertAddon(entity: StremioAddonEntity)

    @Query("SELECT enabled FROM stremio_addons WHERE manifestUrl = :manifestUrl")
    suspend fun getAddonEnabled(manifestUrl: String): Boolean?

    @Query("SELECT priority FROM stremio_addons WHERE manifestUrl = :manifestUrl")
    suspend fun getAddonPriority(manifestUrl: String): Int?

    // Re-installing an add-on refreshes the manifest without undoing what the user did to it.
    //
    // A plain upsert carries the record's defaults for `enabled` and `priority`, and the installer
    // builds that record from the manifest it just fetched — so re-adding an add-on you had turned
    // off turned it back on, and re-adding one you had dragged to the bottom moved it back to
    // wherever the caller's priority argument put it. Neither is anything the user asked for, and
    // both were invisible until the next time they looked at the list.
    //
    // Both reads and the write are in one @Transaction, so a concurrent toggle can't land between
    // reading the old value and writing it back.
    @Transaction
    suspend fun upsertAddonKeepingUserSettings(entity: StremioAddonEntity) {
        upsertAddon(
            entity.copy(
                enabled = getAddonEnabled(entity.manifestUrl) ?: entity.enabled,
                priority = getAddonPriority(entity.manifestUrl) ?: entity.priority,
            ),
        )
    }

    // Writes a whole ordering at once, as 0..n-1.
    //
    // Reordering used to be two setAddonPriority calls that swapped the pair's values. That is two
    // separate suspending writes with no transaction around them, so a cancellation between them
    // left both rows holding the same priority — and once two rows tie, swapping their priorities
    // is a no-op, so the up/down buttons stopped working for that pair permanently. It also could
    // not fix the ties that already existed from "Add by URL" defaulting every add-on to 0, where
    // the swap was a no-op from the very first tap.
    //
    // Renumbering the full list instead of swapping a pair repairs both: it is one transaction, and
    // it leaves every priority distinct whatever state the table was in beforehand.
    @Transaction
    suspend fun setAddonOrder(manifestUrlsInOrder: List<String>) {
        manifestUrlsInOrder.forEachIndexed { index, manifestUrl -> setAddonPriority(manifestUrl, index) }
    }

    // Private to this interface's default method below — callers should always go through
    // deleteAddon(manifestUrl), which also clears the addon's catalog toggles, to avoid
    // reintroducing orphaned rows.
    @Query("DELETE FROM stremio_addons WHERE manifestUrl = :manifestUrl")
    suspend fun deleteAddonRow(manifestUrl: String)

    @Query("DELETE FROM stremio_catalog_toggles WHERE manifestUrl = :manifestUrl")
    suspend fun deleteCatalogTogglesForAddon(manifestUrl: String)

    // Runs both deletes in one transaction so a crash between them can't leave orphaned
    // catalog-toggle rows behind.
    @Transaction
    suspend fun deleteAddon(manifestUrl: String) {
        deleteAddonRow(manifestUrl)
        deleteCatalogTogglesForAddon(manifestUrl)
    }

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
