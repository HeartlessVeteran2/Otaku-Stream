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
    val deduped = withUniqueIds(parsed)
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
        id = listingId(declaredId, name, lang),
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

// An extension's id, derived from the extension and from nothing else.
//
// This is the second attempt, and the first one's failure is the reason it reads like this. That
// version kept the declared id for whichever listing claimed it first and derived one for any later
// claimant in a different language. It produced unique ids, which was the crash it was written to
// fix — but "first" meant first in fetch order, so an extension's identity depended on which
// repositories happened to be reachable at the time. A curated repo being briefly down changed the
// id of somebody else's listing; when it came back, the id changed again. `installedIds`, the
// source registry and the database primary key are all keyed on that id, so the same extension read
// as installed, then not installed, then installed — and installing it twice made two rows.
// Identity that moves with the network is worse than the duplicate key it replaced.
//
// So the id is now a pure function of the listing: the declared id and the language together, or
// the name and the language when no id is declared. Same properties as before where it mattered —
// Animeonsen `en` and Animeonsen `ja` sharing a declared id come out as two sources, and the same
// extension carried by two repos comes out as one — with none of the order dependence.
//
// This does change ids for anything installed under the old rule: such an extension is offered as
// "Install" again, and installing it writes a new row beside the orphaned one. That cost is
// accepted rather than dismissed. The alternative — keeping declared ids and disambiguating only on
// collision — is exactly what cannot be made order-independent, and the exposure is small: the
// extension directory only became usable at all in #126, so almost nothing predates this rule.
internal fun listingId(declaredId: Long, name: String, lang: String): Long =
    if (declaredId != 0L) stableSourceId(declaredId.toString(), lang) else stableSourceId(name, lang)

// Drops genuine duplicates from a merged directory.
//
// With listingId above, two listings collide only when they are the same extension — same declared
// id (or name) and same language — which is what happens when two repositories carry it. Keeping
// the first means the curated repos win over the user's custom one, so provenance is decided by
// order rather than by chance. A duplicate key in the browse list is impossible by construction
// now rather than by this function's diligence, but the dedupe is still wanted: the same extension
// listed twice is noise, and Compose would still throw on it.
internal fun withUniqueIds(listings: List<MangayomiExtensionListing>): List<MangayomiExtensionListing> =
    listings.distinctBy { it.id }
