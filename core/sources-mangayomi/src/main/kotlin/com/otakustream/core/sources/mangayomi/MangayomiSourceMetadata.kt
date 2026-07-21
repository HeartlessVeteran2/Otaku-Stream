package com.otakustream.core.sources.mangayomi

// Identity + display metadata for an installed Mangayomi/AnymeX extension, taken from its repo
// index entry (anime_index.json). Only the fields the runtime + adapter need in this PR are
// modeled; persistence-oriented fields (repoUrl, cached source, prefs) arrive with the install
// layer in a later PR.
data class MangayomiSourceMetadata(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String = "",
    val iconUrl: String? = null,
    val version: String = "",
    val isNsfw: Boolean = false,
)
