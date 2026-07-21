package com.otakustream.core.sources.mangayomi.repo

import com.otakustream.core.sources.api.stableSourceId
import org.json.JSONArray

// One installable entry from a Mangayomi/AnymeX extension repo index (anime_index.json). The
// runnable logic isn't here — `sourceCodeUrl` points at the raw .js the installer downloads.
data class MangayomiExtensionListing(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val iconUrl: String?,
    val sourceCodeUrl: String,
    val version: String,
    val isNsfw: Boolean,
    val itemType: Int,
    val sourceCodeLanguage: Int,
)

// Item types / source-code languages as encoded in the Mangayomi index.
private const val ITEM_TYPE_ANIME = 1
private const val SOURCE_LANGUAGE_JS = 1

// Parses a Mangayomi index (a top-level JSON array) and keeps only entries this app can actually
// run: anime (itemType == 1) written in JavaScript (sourceCodeLanguage == 1). Dart extensions and
// manga/novel entries are dropped — there's no Dart interpreter, and the app is video-only.
fun parseMangayomiIndex(json: String): List<MangayomiExtensionListing> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val itemType = obj.optInt("itemType", ITEM_TYPE_ANIME)
        val sourceCodeLanguage = obj.optInt("sourceCodeLanguage", SOURCE_LANGUAGE_JS)
        if (itemType != ITEM_TYPE_ANIME || sourceCodeLanguage != SOURCE_LANGUAGE_JS) return@mapNotNull null
        val name = obj.optString("name").ifEmpty { return@mapNotNull null }
        val sourceCodeUrl = obj.optString("sourceCodeUrl").ifEmpty { return@mapNotNull null }
        val lang = obj.optString("lang").ifEmpty { "en" }
        val declaredId = obj.optLong("id", 0L)
        MangayomiExtensionListing(
            id = if (declaredId != 0L) declaredId else stableSourceId(name, lang),
            name = name,
            lang = lang,
            baseUrl = obj.optString("baseUrl"),
            iconUrl = obj.optString("iconUrl").ifEmpty { null },
            sourceCodeUrl = sourceCodeUrl,
            version = obj.optString("version"),
            isNsfw = obj.optBoolean("isNsfw", false),
            itemType = itemType,
            sourceCodeLanguage = sourceCodeLanguage,
        )
    }
}
