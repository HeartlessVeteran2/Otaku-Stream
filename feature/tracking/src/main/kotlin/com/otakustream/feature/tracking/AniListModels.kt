package com.otakustream.feature.tracking

import org.json.JSONObject
import java.util.Calendar

// Rich AniList media model backing the AniList-as-hub discovery/detail surfaces. The first three
// fields (id, title, episodes) keep the shape the older link/AniSkip callers already rely on; the
// rest are populated from the fuller GraphQL selection used by Home/detail. Any field the API omits
// stays null/empty so partial responses never crash a screen.
data class AniListMedia(
    val id: Long,
    val title: String,
    val episodes: Int?,
    val romajiTitle: String? = null,
    val englishTitle: String? = null,
    val nativeTitle: String? = null,
    val coverImageUrl: String? = null,
    val bannerImageUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val format: String? = null,
    val status: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val nextAiringEpisode: Int? = null,
    val nextAiringAtSeconds: Long? = null,
    val relations: List<AniListRelation> = emptyList(),
    val recommendations: List<AniListMedia> = emptyList(),
) {
    // Title candidates in AnymeX's preferred order: the user-facing English name, falling back to
    // romaji then native. Callers that only had `title` before still get the same value.
    val displayTitle: String
        get() = englishTitle?.takeIf { it.isNotBlank() }
            ?: romajiTitle?.takeIf { it.isNotBlank() }
            ?: title
}

// A related work (prequel/sequel/side story/adaptation…) surfaced on the detail screen.
data class AniListRelation(
    val relationType: String?,
    val media: AniListMedia,
)

// One entry from the signed-in user's lists (Watching/Planning/…): the anime plus their personal
// status, score, and progress.
data class AniListListEntry(
    val media: AniListMedia,
    val status: String?,
    val score: Double?,
    val progress: Int,
)

// A page of media results with the cursor AniList returns for infinite scroll.
data class AniListPage(
    val media: List<AniListMedia>,
    val currentPage: Int,
    val hasNextPage: Boolean,
)

// The signed-in AniList account (for the profile header + resolving the user's own lists).
data class AniListViewer(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
)

// ---- Pure parsers (no I/O) so Phase 8 can unit-test them against captured JSON. ----

internal fun parseMedia(media: JSONObject): AniListMedia {
    val titleObj = media.optJSONObject("title")
    val romaji = titleObj?.stringOrNull("romaji")
    val english = titleObj?.stringOrNull("english")
    val native = titleObj?.stringOrNull("native")
    val coverImage = media.optJSONObject("coverImage")
    val nextAiring = media.optJSONObject("nextAiringEpisode")
    return AniListMedia(
        id = media.getLong("id"),
        title = english?.takeIf { it.isNotBlank() } ?: romaji?.takeIf { it.isNotBlank() }
            ?: native.orEmpty(),
        episodes = media.optInt("episodes", 0).takeIf { it > 0 },
        romajiTitle = romaji,
        englishTitle = english,
        nativeTitle = native,
        coverImageUrl = coverImage?.stringOrNull("extraLarge")
            ?: coverImage?.stringOrNull("large"),
        bannerImageUrl = media.stringOrNull("bannerImage"),
        description = media.stringOrNull("description"),
        genres = media.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty(),
        averageScore = media.optInt("averageScore", 0).takeIf { it > 0 },
        format = media.stringOrNull("format"),
        status = media.stringOrNull("status"),
        season = media.stringOrNull("season"),
        seasonYear = media.optInt("seasonYear", 0).takeIf { it > 0 },
        nextAiringEpisode = nextAiring?.optInt("episode", 0)?.takeIf { it > 0 },
        nextAiringAtSeconds = nextAiring?.optLong("airingAt", 0L)?.takeIf { it > 0 },
        relations = parseRelations(media.optJSONObject("relations")),
        recommendations = parseRecommendations(media.optJSONObject("recommendations")),
    )
}

private fun parseRelations(relations: JSONObject?): List<AniListRelation> {
    val edges = relations?.optJSONArray("edges") ?: return emptyList()
    return (0 until edges.length()).mapNotNull { index ->
        val edge = edges.optJSONObject(index) ?: return@mapNotNull null
        val node = edge.optJSONObject("node") ?: return@mapNotNull null
        AniListRelation(relationType = edge.stringOrNull("relationType"), media = parseMedia(node))
    }
}

private fun parseRecommendations(recommendations: JSONObject?): List<AniListMedia> {
    val nodes = recommendations?.optJSONArray("nodes") ?: return emptyList()
    return (0 until nodes.length()).mapNotNull { index ->
        val node = nodes.optJSONObject(index) ?: return@mapNotNull null
        val recommended = node.optJSONObject("mediaRecommendation") ?: return@mapNotNull null
        parseMedia(recommended)
    }
}

internal fun parsePage(page: JSONObject): AniListPage {
    val mediaArray = page.optJSONArray("media")
    val media = if (mediaArray == null) {
        emptyList()
    } else {
        (0 until mediaArray.length()).mapNotNull { index ->
            mediaArray.optJSONObject(index)?.let(::parseMedia)
        }
    }
    val pageInfo = page.optJSONObject("pageInfo")
    return AniListPage(
        media = media,
        currentPage = pageInfo?.optInt("currentPage", 1) ?: 1,
        hasNextPage = pageInfo?.optBoolean("hasNextPage", false) ?: false,
    )
}

internal fun parseListCollection(collection: JSONObject): List<AniListListEntry> {
    val lists = collection.optJSONArray("lists") ?: return emptyList()
    val entries = mutableListOf<AniListListEntry>()
    for (listIndex in 0 until lists.length()) {
        val entryArray = lists.optJSONObject(listIndex)?.optJSONArray("entries") ?: continue
        for (entryIndex in 0 until entryArray.length()) {
            val entry = entryArray.optJSONObject(entryIndex) ?: continue
            val node = entry.optJSONObject("media") ?: continue
            entries += AniListListEntry(
                media = parseMedia(node),
                status = entry.stringOrNull("status"),
                score = entry.optDouble("score", 0.0).takeIf { it > 0.0 },
                progress = entry.optInt("progress", 0),
            )
        }
    }
    return entries
}

internal fun parseViewer(viewer: JSONObject): AniListViewer = AniListViewer(
    id = viewer.getLong("id"),
    name = viewer.optString("name"),
    avatarUrl = viewer.optJSONObject("avatar")?.stringOrNull("large"),
)

// AniList assigns December to the *next* year's WINTER season (its seasonal charts roll over early),
// so treat month 12 as WINTER of year+1 to match how the site groups "this season".
internal fun currentSeasonAndYear(calendar: Calendar = Calendar.getInstance()): Pair<String, Int> {
    val month = calendar.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0-based
    val year = calendar.get(Calendar.YEAR)
    return when (month) {
        12 -> "WINTER" to year + 1
        in 1..2 -> "WINTER" to year
        in 3..5 -> "SPRING" to year
        in 6..8 -> "SUMMER" to year
        else -> "FALL" to year
    }
}

// Android's JSONObject.optString returns the literal "null" for JSON null; treat those (and blanks)
// as a real Kotlin null so downstream nullability checks work.
internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
