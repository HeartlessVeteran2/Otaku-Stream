package com.otakustream.core.database.stremio

import androidx.room.Entity
import androidx.room.PrimaryKey

// One row per installed addon manifest. Raw manifest JSON is persisted so cold start can
// re-derive all catalog-scoped VideoSource instances without re-fetching the manifest.
@Entity(tableName = "stremio_addons")
data class StremioAddonEntity(
    @PrimaryKey val manifestUrl: String,
    val manifestJson: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
)

// Single-row table holding the user's configured streaming-server base URL (id is always 0).
@Entity(tableName = "stremio_server_config")
data class StremioServerConfigEntity(
    @PrimaryKey val id: Int = 0,
    val baseUrl: String,
)

// An addon manifest can declare multiple catalogs, each becoming its own VideoSource — this
// lets a single catalog be turned off without uninstalling the whole addon.
@Entity(tableName = "stremio_catalog_toggles", primaryKeys = ["manifestUrl", "type", "id"])
data class StremioCatalogToggleEntity(
    val manifestUrl: String,
    val type: String,
    val id: String,
    val enabled: Boolean = true,
)
