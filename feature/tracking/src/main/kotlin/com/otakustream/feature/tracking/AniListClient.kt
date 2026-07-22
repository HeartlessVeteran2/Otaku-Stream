package com.otakustream.feature.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

// The AniList GraphQL client. Browse/trending/search/detail are all public (no token); only the
// viewer's own lists and writes (progress/score/status) require the OAuth token. Every network call
// reuses the app-wide OkHttpClient. Response parsing lives in AniListModels.kt as pure functions so
// it can be unit-tested without the network.
@Singleton
class AniListClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    // ---- Discovery (unauthenticated) ----

    suspend fun fetchTrending(page: Int = 1): AniListPage =
        fetchPageSortedBy("TRENDING_DESC", page)

    suspend fun fetchAllTimePopular(page: Int = 1): AniListPage =
        fetchPageSortedBy("POPULARITY_DESC", page)

    suspend fun fetchPopularThisSeason(page: Int = 1): AniListPage = withContext(Dispatchers.IO) {
        val (season, year) = currentSeasonAndYear()
        val query = """
            query (${'$'}page: Int, ${'$'}season: MediaSeason, ${'$'}seasonYear: Int) {
              Page(page: ${'$'}page, perPage: $PAGE_SIZE) {
                pageInfo { currentPage hasNextPage }
                media(season: ${'$'}season, seasonYear: ${'$'}seasonYear, type: ANIME, sort: POPULARITY_DESC) {
                  $MEDIA_SELECTION
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject()
            .put("page", page)
            .put("season", season)
            .put("seasonYear", year)
        parsePage(execute(query, variables, token = null).getJSONObject("Page"))
    }

    suspend fun search(query: String, page: Int = 1): AniListPage = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}search: String, ${'$'}page: Int) {
              Page(page: ${'$'}page, perPage: $PAGE_SIZE) {
                pageInfo { currentPage hasNextPage }
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                  $MEDIA_SELECTION
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject().put("search", query).put("page", page)
        parsePage(execute(gql, variables, token = null).getJSONObject("Page"))
    }

    // Full detail incl. relations + recommendations for the AniList detail screen.
    suspend fun fetchMediaDetail(id: Long): AniListMedia = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                $MEDIA_SELECTION
                relations {
                  edges {
                    relationType
                    node { $MEDIA_SELECTION }
                  }
                }
                recommendations(sort: RATING_DESC, perPage: 12) {
                  nodes {
                    mediaRecommendation { $MEDIA_SELECTION }
                  }
                }
              }
            }
        """.trimIndent()
        parseMedia(execute(gql, JSONObject().put("id", id), token = null).getJSONObject("Media"))
    }

    private suspend fun fetchPageSortedBy(sort: String, page: Int): AniListPage =
        withContext(Dispatchers.IO) {
            val gql = """
                query (${'$'}page: Int) {
                  Page(page: ${'$'}page, perPage: $PAGE_SIZE) {
                    pageInfo { currentPage hasNextPage }
                    media(type: ANIME, sort: $sort) {
                      $MEDIA_SELECTION
                    }
                  }
                }
            """.trimIndent()
            parsePage(execute(gql, JSONObject().put("page", page), token = null).getJSONObject("Page"))
        }

    // ---- Personal library + writes (authenticated) ----

    // The signed-in viewer's own entry for one anime (to pre-fill the detail list controls).
    // Returns null when the anime isn't on any of their lists.
    suspend fun fetchViewerListEntry(token: String, mediaId: Long): AniListViewerEntry? =
        withContext(Dispatchers.IO) {
            val gql = """
                query (${'$'}id: Int) {
                  Media(id: ${'$'}id, type: ANIME) {
                    mediaListEntry { status score(format: POINT_10_DECIMAL) progress }
                  }
                }
            """.trimIndent()
            parseViewerEntry(execute(gql, JSONObject().put("id", mediaId), token).getJSONObject("Media"))
        }

    suspend fun fetchViewer(token: String): AniListViewer = withContext(Dispatchers.IO) {
        val gql = """
            query {
              Viewer { id name avatar { large } }
            }
        """.trimIndent()
        parseViewer(execute(gql, JSONObject(), token).getJSONObject("Viewer"))
    }

    // The signed-in user's anime lists (Watching/Planning/Completed/…) with their per-entry
    // status, score, and progress. Flattened across the AniList list buckets.
    suspend fun fetchUserAnimeLists(token: String, userId: Long): List<AniListListEntry> =
        withContext(Dispatchers.IO) {
            val gql = """
                query (${'$'}userId: Int) {
                  MediaListCollection(userId: ${'$'}userId, type: ANIME) {
                    lists {
                      name
                      entries {
                        status
                        score(format: POINT_10_DECIMAL)
                        progress
                        media { $MEDIA_SELECTION }
                      }
                    }
                  }
                }
            """.trimIndent()
            parseListCollection(
                execute(gql, JSONObject().put("userId", userId), token)
                    .getJSONObject("MediaListCollection"),
            )
        }

    // Create/update the viewer's list entry. Only non-null fields are sent so callers can nudge
    // progress without clobbering status/score (and vice versa).
    suspend fun saveMediaListEntry(
        token: String,
        mediaId: Long,
        status: String? = null,
        score: Double? = null,
        progress: Int? = null,
    ) {
        withContext(Dispatchers.IO) {
            val args = buildList {
                add("mediaId: ${'$'}mediaId")
                if (status != null) add("status: ${'$'}status")
                if (score != null) add("scoreRaw: ${'$'}scoreRaw")
                if (progress != null) add("progress: ${'$'}progress")
            }.joinToString(", ")
            val declarations = buildList {
                add("${'$'}mediaId: Int")
                if (status != null) add("${'$'}status: MediaListStatus")
                if (score != null) add("${'$'}scoreRaw: Int")
                if (progress != null) add("${'$'}progress: Int")
            }.joinToString(", ")
            val gql = """
                mutation ($declarations) {
                  SaveMediaListEntry($args) { id status score progress }
                }
            """.trimIndent()
            val variables = JSONObject().put("mediaId", mediaId)
            if (status != null) variables.put("status", status)
            // AniList's scoreRaw is on a 0–100 scale regardless of the user's display format.
            if (score != null) variables.put("scoreRaw", (score * 10).toInt().coerceIn(0, 100))
            if (progress != null) variables.put("progress", progress)
            execute(gql, variables, token)
        }
    }

    // ---- Backward-compatible helpers used by the existing link/AniSkip/auto-sync flows ----

    suspend fun searchAnime(query: String): List<AniListMedia> = search(query).media

    // AniList id → MyAnimeList id, needed to query AniSkip. Public field, no auth. Cached on this
    // singleton so the mapping is resolved once per anime across every screen/playback.
    private val malIdCache = ConcurrentHashMap<Long, Long>()

    suspend fun getMalId(aniListId: Long): Long? = withContext(Dispatchers.IO) {
        malIdCache[aniListId]?.let { return@withContext it }
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { idMal }
            }
        """.trimIndent()
        val data = execute(gql, JSONObject().put("id", aniListId), token = null)
        data.optJSONObject("Media")?.optInt("idMal", 0)?.toLong()?.takeIf { it > 0 }
            ?.also { malIdCache[aniListId] = it }
    }

    // Sets the entry to CURRENT with the given progress (creates it if absent).
    suspend fun saveProgress(token: String, mediaId: Long, progress: Int) {
        saveMediaListEntry(token, mediaId, status = "CURRENT", progress = progress)
    }

    private fun execute(query: String, variables: JSONObject, token: String?): JSONObject {
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val request = Request.Builder()
            .url(ANILIST_GRAPHQL_URL)
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { if (token != null) addHeader("Authorization", "Bearer $token") }
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Body may be non-JSON (proxy/HTML error page) — never let a parse failure mask the HTTP code.
                val message = runCatching { parseErrorMessage(JSONObject(text)) }.getOrNull()
                error(message ?: "AniList request failed: HTTP ${response.code}")
            }
            val root = JSONObject(text)
            if (root.has("errors")) {
                error(parseErrorMessage(root) ?: "AniList request failed")
            }
            return root.getJSONObject("data")
        }
    }

    private fun parseErrorMessage(root: JSONObject): String? =
        root.optJSONArray("errors")?.optJSONObject(0)?.let { if (it.isNull("message")) null else it.optString("message") }
            ?.ifEmpty { null }

    private companion object {
        const val PAGE_SIZE = 30

        // Shared GraphQL selection so every media-returning query yields the same fields the
        // AniListMedia parser expects.
        const val MEDIA_SELECTION = """
                  id
                  episodes
                  title { romaji english native }
                  coverImage { extraLarge large }
                  bannerImage
                  description(asHtml: false)
                  genres
                  averageScore
                  format
                  status
                  season
                  seasonYear
                  nextAiringEpisode { episode airingAt }
        """
    }
}
