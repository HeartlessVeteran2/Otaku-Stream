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
    // Ids must come out unique, because every consumer downstream is keyed on the id alone: the
    // browse list's Compose key, the installed-id set, the source registry, and the database's
    // primary key.
    //
    // Two things have to be true at once. Swakshan's index gives Animeonsen `en` and Animeonsen
    // `ja` the *same* declared id, and they are genuinely two sources — deduping on the id alone
    // threw one away, which is the bug this change is about. But keeping both under one id is
    // worse than losing one: Compose throws on a duplicate list key, and installing either variant
    // would mark and overwrite the other.
    //
    // So a declared id is honoured once. A second entry claiming an id already taken by a
    // different language gets a derived one instead — the same stableSourceId(name, lang) already
    // used for entries that declare no id at all, so nothing new is invented here. A repeat of the
    // same id *and* language is a genuine duplicate and is dropped.
    val seenIds = mutableSetOf<Long>()
    val seenKeys = mutableSetOf<Pair<Long, String>>()
    val deduped = parsed.mapNotNull { listing ->
        if (!seenKeys.add(listing.id to listing.lang)) return@mapNotNull null
        if (seenIds.add(listing.id)) {
            listing
        } else {
            val derived = stableSourceId(listing.name, listing.lang)
            // Only if the derived id is itself free; otherwise this entry cannot be told apart from
            // one already listed and is dropped rather than colliding.
            if (seenIds.add(derived)) listing.copy(id = derived) else null
        }
    }
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
