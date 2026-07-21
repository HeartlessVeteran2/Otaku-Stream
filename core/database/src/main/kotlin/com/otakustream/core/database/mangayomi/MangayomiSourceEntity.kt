package com.otakustream.core.database.mangayomi

import androidx.room.Entity
import androidx.room.PrimaryKey

// A persisted Mangayomi/AnymeX JS extension. The script text is cached inline (scriptContent) so
// cold-start rehydration never needs the network — mirrors how scripted_sources persists. Keyed
// by the extension's stable id (from its repo index entry, falling back to stableSourceId).
@Entity(tableName = "mangayomi_sources")
data class MangayomiSourceEntity(
    @PrimaryKey val id: Long,
    val repoUrl: String,
    val sourceCodeUrl: String,
    val scriptContent: String,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val iconUrl: String?,
    val version: String,
    val isNsfw: Boolean,
    val itemType: Int,
    val sourceCodeLanguage: Int,
    // Per-source preference values (getSourcePreferences), stored as a JSON object; wired to the
    // runtime in the preferences PR. Null until the user changes a preference.
    val prefsJson: String?,
)
