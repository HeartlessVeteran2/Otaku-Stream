package com.otakustream.core.sources.mangayomi.repo

import com.otakustream.core.sources.api.stableSourceId
import org.json.JSONArray
import org.json.JSONObject

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
    // Which repository this came from, so a merged directory can say where each row was found —
    // the same job AddonListOrigin does on the Stremio side.
    val repoName: String? = null,
)

// What an index yielded, and what it did not.
//
// The count is not bookkeeping: m2k3a's index is 64 entries of which 40 are Dart, so a screen that
// silently rendered 24 rows was indistinguishable from a broken repo. Reporting the difference lets
// the screen say "24 shown · 40 Dart extensions this app can't run", which is the same rule #118
// established for the stream picker — a source that contributes nothing says why.
data class ParsedIndex(
    val listings: List<MangayomiExtensionListing>,
    val unsupportedCount: Int,
)

// Item types / source-code languages as encoded in the Mangayomi index.
private const val ITEM_TYPE_ANIME = 1
private const val SOURCE_LANGUAGE_JS = 1

// Parses a Mangayomi index (a top-level JSON array) and keeps only entries this app can actually
// run: anime (itemType == 1) written in JavaScript (sourceCodeLanguage == 1). Dart extensions and
// manga/novel entries are dropped — there's no Dart interpreter, and the app is video-only — and
// counted, so the caller can say so rather than leaving the gap unexplained.
fun parseMangayomiIndex(json: String, repoName: String? = null): ParsedIndex {
    val array = JSONArray(json)
    val entries = (0 until array.length()).map { index -> array.optJSONObject(index) }
    val parsed = entries.mapNotNull { obj -> parseEntry(obj, repoName) }
    // A malformed third-party repo could list the same extension twice; the browse list keys on id,
    // so a duplicate would crash it.
    //
    // Keyed on id *and* lang, not id alone. Swakshan's index gives Animeonsen `en` and Animeonsen
    // `ja` the same declared id, and deduping on id threw one of them away — a real extension in a
    // real repo, silently missing, which is precisely the failure this whole change is about. The
    // two are distinct sources to every other part of the app, since stableSourceId is derived from
    // name and lang together.
    val deduped = parsed.distinctBy { it.id to it.lang }
    return ParsedIndex(
        listings = deduped,
        // Counted against what was actually in the file, so the number means "entries this app
        // cannot use", not "entries the parser happened to drop after deduping".
        unsupportedCount = entries.count { obj -> obj != null && !isSupported(obj) },
    )
}

private fun isSupported(obj: JSONObject): Boolean {
    val itemType = obj.optInt("itemType", ITEM_TYPE_ANIME)
    val sourceCodeLanguage = obj.optInt("sourceCodeLanguage", SOURCE_LANGUAGE_JS)
    return itemType == ITEM_TYPE_ANIME && sourceCodeLanguage == SOURCE_LANGUAGE_JS
}

private fun parseEntry(obj: JSONObject?, repoName: String?): MangayomiExtensionListing? {
    if (obj == null) return null
    if (!isSupported(obj)) return null
    val name = obj.optString("name").ifEmpty { return null }
    val sourceCodeUrl = obj.optString("sourceCodeUrl").ifEmpty { return null }
    val lang = obj.optString("lang").ifEmpty { "en" }
    val declaredId = obj.optLong("id", 0L)
    return MangayomiExtensionListing(
        id = if (declaredId != 0L) declaredId else stableSourceId(name, lang),
        name = name,
        lang = lang,
        baseUrl = obj.optString("baseUrl"),
        iconUrl = obj.optString("iconUrl").ifEmpty { null },
        sourceCodeUrl = sourceCodeUrl,
        version = obj.optString("version"),
        isNsfw = obj.optBoolean("isNsfw", false),
        itemType = obj.optInt("itemType", ITEM_TYPE_ANIME),
        sourceCodeLanguage = obj.optInt("sourceCodeLanguage", SOURCE_LANGUAGE_JS),
        repoName = repoName,
    )
}
