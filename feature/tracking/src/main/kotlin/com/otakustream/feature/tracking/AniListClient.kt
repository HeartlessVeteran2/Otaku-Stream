package com.otakustream.feature.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

data class AniListMedia(val id: Long, val title: String, val episodes: Int?)

@Singleton
class AniListClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun searchAnime(query: String): List<AniListMedia> = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}search: String) {
              Page(perPage: 10) {
                media(search: ${'$'}search, type: ANIME) {
                  id
                  episodes
                  title { romaji english }
                }
              }
            }
        """.trimIndent()
        val data = execute(gql, JSONObject().put("search", query), token = null)
        val media = data.getJSONObject("Page").getJSONArray("media")
        (0 until media.length()).map { index ->
            val entry = media.getJSONObject(index)
            val title = entry.getJSONObject("title")
            AniListMedia(
                id = entry.getLong("id"),
                title = title.stringOrEmpty("english").ifEmpty { title.stringOrEmpty("romaji") },
                episodes = entry.optInt("episodes", 0).takeIf { it > 0 },
            )
        }
    }

    // AniList id → MyAnimeList id, needed to query AniSkip. Public field, no auth.
    suspend fun getMalId(aniListId: Long): Long? = withContext(Dispatchers.IO) {
        val gql = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { idMal }
            }
        """.trimIndent()
        val data = execute(gql, JSONObject().put("id", aniListId), token = null)
        data.optJSONObject("Media")?.optInt("idMal", 0)?.toLong()?.takeIf { it > 0 }
    }

    // Sets the entry to CURRENT with the given progress (creates it if absent).
    suspend fun saveProgress(token: String, mediaId: Long, progress: Int) {
        withContext(Dispatchers.IO) {
            val gql = """
                mutation (${'$'}mediaId: Int, ${'$'}progress: Int) {
                  SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: CURRENT) { id }
                }
            """.trimIndent()
            execute(gql, JSONObject().put("mediaId", mediaId).put("progress", progress), token)
        }
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
        root.optJSONArray("errors")?.optJSONObject(0)?.stringOrEmpty("message")?.ifEmpty { null }
}

// Android's JSONObject.optString returns the literal string "null" for JSON null values.
private fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)
