package com.otakustream.feature.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

// The AniList GraphQL client. Browse/trending/search/detail are all public (no token); only the
// viewer's own lists and writes (progress/score/status) require the OAuth token. Every network call
// reuses the app-wide OkHttpClient. Response parsing lives in AniListModels.kt as pure functions so
// it can be unit-tested without the network.
@Singleton
class AniListClient @Inject constructor(
    @com.otakustream.core.network.di.AccountHttpClient private val httpClient: OkHttpClient,
) {
    // ---- Discovery (unauthenticated) ----

    // Discovery rails are slow-moving and re-requested every time the Home ViewModel is created
    // (navigating away from Play and back, or a process-alive relaunch), so they're memoized on this
    // singleton for DISCOVERY_TTL_MS. Same idea as malIdCache below, just time-bounded.
    suspend fun fetchTrending(page: Int = 1): AniListPage =
        cachedPage("trending", page) { fetchPageSortedBy("TRENDING_DESC", page) }

    suspend fun fetchAllTimePopular(page: Int = 1): AniListPage =
        cachedPage("all-time", page) { fetchPageSortedBy("POPULARITY_DESC", page) }

    suspend fun fetchPopularThisSeason(page: Int = 1): AniListPage =
        cachedPage("season", page) { fetchPopularThisSeasonUncached(page) }

    private suspend fun fetchPopularThisSeasonUncached(page: Int): AniListPage = withContext(Dispatchers.IO) {
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
        parsePage(execute(query, variables, token = null).requireField("Page"))
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
        parsePage(execute(gql, variables, token = null).requireField("Page"))
    }

    // Full detail incl. relations + recommendations for the AniList detail screen. Memoized so
    // re-opening a title (a very common back-and-forth) doesn't refetch a large payload.
    suspend fun fetchMediaDetail(id: Long): AniListMedia {
        detailCache[id]?.let { return it }
        return fetchMediaDetailUncached(id).also { detail -> detailCache[id] = detail }
    }

    private suspend fun fetchMediaDetailUncached(id: Long): AniListMedia = withContext(Dispatchers.IO) {
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
        parseMedia(execute(gql, JSONObject().put("id", id), token = null).requireField("Media"))
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
            parsePage(execute(gql, JSONObject().put("page", page), token = null).requireField("Page"))
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
            parseViewerEntry(execute(gql, JSONObject().put("id", mediaId), token).requireField("Media"))
        }

    // Cached per token: continue-watching resolves the viewer before listing their anime, and the
    // viewer identity can't change without the token changing. See viewerCache for why the key is a
    // digest of the token rather than the token.
    suspend fun fetchViewer(token: String): AniListViewer {
        val key = tokenFingerprint(token)
        viewerCache.get()?.takeIf { it.first == key }?.let { return it.second }
        return fetchViewerUncached(token).also { viewer ->
            viewerCache.set(key to viewer)
        }
    }

    private fun tokenFingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private suspend fun fetchViewerUncached(token: String): AniListViewer = withContext(Dispatchers.IO) {
        val gql = """
            query {
              Viewer { id name avatar { large } }
            }
        """.trimIndent()
        parseViewer(execute(gql, JSONObject(), token).requireField("Viewer"))
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
                    .requireField("MediaListCollection"),
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

    private class CachedPage(val page: AniListPage, val fetchedAtMs: Long)

    private val discoveryCache = ConcurrentHashMap<String, CachedPage>()

    // A real LRU, not a periodic flush: access-ordered LinkedHashMap evicting only the least
    // recently used entry once it's full. The obvious `if (size >= MAX) clear()` looks equivalent
    // but isn't — it throws away all MAX warm entries the moment the MAX+1'th title is opened, so
    // browsing a long rail would leave nothing cached at exactly the point caching starts to pay.
    private val detailCache: MutableMap<Long, AniListMedia> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, AniListMedia>(DETAIL_CACHE_MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, AniListMedia>): Boolean =
                size > DETAIL_CACHE_MAX
        },
    )

    // Keyed by a SHA-256 digest of the token, never the token itself, and holding a single entry.
    // This is a process-lifetime singleton, so keying by the raw bearer token would leave a
    // plaintext copy of it in memory after the user signs out and the encrypted store has already
    // dropped it — and would accumulate one entry per account switch. A digest can't be replayed as
    // a credential, and one slot means a new token simply displaces the old viewer.
    private val viewerCache = AtomicReference<Pair<String, AniListViewer>?>(null)

    // Serves a fresh cached page when one exists, otherwise fetches and stores. A failed fetch
    // throws without poisoning the cache, so the next attempt retries.
    private suspend fun cachedPage(key: String, page: Int, fetch: suspend () -> AniListPage): AniListPage {
        val cacheKey = "$key:$page"
        discoveryCache[cacheKey]
            ?.takeIf { System.currentTimeMillis() - it.fetchedAtMs < DISCOVERY_TTL_MS }
            ?.let { return it.page }
        return fetch().also { discoveryCache[cacheKey] = CachedPage(it, System.currentTimeMillis()) }
    }

    suspend fun getMalId(aniListId: Long): Long? = withContext(Dispatchers.IO) {
        // 0L is the "known to have no MAL id" sentinel: cache negatives too so a show without a MAL
        // id doesn't re-hit the network on every AniSkip attempt (only transient errors — which
        // throw before we reach the cache write — are retried).
        malIdCache[aniListId]?.let { return@withContext it.takeIf { cached -> cached > 0 } }
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { idMal }
            }
        """.trimIndent()
        val data = execute(gql, JSONObject().put("id", aniListId), token = null)
        val malId = data.optJSONObject("Media")?.optInt("idMal", 0)?.toLong()?.takeIf { it > 0 }
        malIdCache[aniListId] = malId ?: 0L
        malId
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
            // A 200 can still carry a non-JSON body (captive portal / Cloudflare HTML) — parse
            // defensively and surface the HTTP context rather than throwing a raw JSONException.
            val root = runCatching { JSONObject(text) }.getOrElse {
                error("AniList returned an unexpected response (HTTP ${response.code}).")
            }
            if (root.has("errors")) {
                error(parseErrorMessage(root) ?: "AniList request failed")
            }
            return root.optJSONObject("data")
                ?: error("AniList returned no data (HTTP ${response.code}).")
        }
    }

    // Pulls a required top-level field out of a GraphQL `data` object with a clean message when it
    // is absent/null (e.g. Media(id) for a removed anime returns `Media: null`), instead of the
    // opaque JSONException that getJSONObject throws.
    private fun JSONObject.requireField(name: String): JSONObject =
        optJSONObject(name) ?: error("AniList response was missing \"$name\".")

    private fun parseErrorMessage(root: JSONObject): String? =
        root.optJSONArray("errors")?.optJSONObject(0)?.let { if (it.isNull("message")) null else it.optString("message") }
            ?.ifEmpty { null }

    private companion object {
        const val PAGE_SIZE = 30

        // Discovery rails change on the order of hours, not seconds; 10 minutes keeps a session's
        // repeat visits free without ever showing genuinely stale rails.
        const val DISCOVERY_TTL_MS = 10 * 60 * 1000L
        const val DETAIL_CACHE_MAX = 50

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
