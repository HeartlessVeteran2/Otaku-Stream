package com.otakustream.core.sources.stremio.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

private const val API_BASE = "https://api.strem.io/api"

data class StremioAccount(val authKey: String, val email: String?)

data class StremioLibraryItem(
    val id: String, // Stremio meta id, e.g. "tt1234567"
    val type: String, // "movie" / "series" / …
    val name: String,
    val poster: String?,
    val removed: Boolean,
) {
    // The app encodes Stremio catalog items as "type|id", so a pulled item lines up with the same
    // local library key a saved catalog item would use.
    val mediaUrl: String get() = "$type|$id"
}

// Talks to Stremio's account API (api.strem.io): logs in for an authKey and reads/writes the user's
// personal library ("libraryItem" datastore collection). Reuses the app-wide OkHttpClient. Only the
// authKey is a credential the caller persists — the password is used once here and never stored.
@Singleton
class StremioAccountClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun login(email: String, password: String): StremioAccount = withContext(Dispatchers.IO) {
        val root = post("$API_BASE/login", JSONObject().put("email", email).put("password", password))
        val result = root.optJSONObject("result") ?: error(errorMessage(root) ?: "Stremio login failed")
        val authKey = result.optString("authKey").ifEmpty { error("Stremio login returned no auth key") }
        val userEmail = result.optJSONObject("user")?.optString("email")?.ifEmpty { null } ?: email
        StremioAccount(authKey = authKey, email = userEmail)
    }

    suspend fun fetchLibrary(authKey: String): List<StremioLibraryItem> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("all", true)
        val result = post("$API_BASE/datastoreGet", body).optJSONArray("result") ?: return@withContext emptyList()
        (0 until result.length()).mapNotNull { index ->
            val obj = result.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("_id").ifEmpty { return@mapNotNull null }
            StremioLibraryItem(
                id = id,
                type = obj.optString("type").ifEmpty { "other" },
                name = obj.optString("name").ifEmpty { id },
                poster = obj.optString("poster").ifEmpty { null },
                removed = obj.optBoolean("removed", false),
            )
        }
    }

    // Best-effort push of local saves up to the Stremio account. Builds a minimal-but-valid
    // libraryItem for each entry (the receiver fills in richer metadata on next sync).
    suspend fun putLibraryItems(authKey: String, items: List<StremioLibraryItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val now = isoNow()
        val changes = JSONArray().apply { items.forEach { put(libraryItemJson(it, now)) } }
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", changes)
        post("$API_BASE/datastorePut", body)
        Unit
    }

    private fun libraryItemJson(item: StremioLibraryItem, now: String): JSONObject =
        JSONObject()
            .put("_id", item.id)
            .put("name", item.name)
            .put("type", item.type)
            .put("poster", item.poster ?: "")
            .put("posterShape", "poster")
            .put("background", JSONObject.NULL)
            .put("logo", JSONObject.NULL)
            .put("year", "")
            .put("removed", item.removed)
            .put("temp", false)
            .put("_ctime", now)
            .put("_mtime", now)
            .put("state", defaultState())

    private fun defaultState(): JSONObject = JSONObject()
        .put("lastWatched", "")
        .put("timeWatched", 0)
        .put("timeOffset", 0)
        .put("overallTimeWatched", 0)
        .put("timesWatched", 0)
        .put("flaggedWatched", 0)
        .put("duration", 0)
        .put("video_id", "")
        .put("watched", "")
        .put("noNotif", false)
        .put("season", 0)
        .put("episode", 0)

    private fun errorMessage(root: JSONObject): String? =
        root.optJSONObject("error")?.let { if (it.isNull("message")) null else it.optString("message").ifEmpty { null } }

    private fun post(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrElse {
                error("Stremio returned an unexpected response (HTTP ${response.code}).")
            }
            if (!response.isSuccessful) error(errorMessage(root) ?: "Stremio request failed: HTTP ${response.code}")
            if (root.has("error") && !root.isNull("error")) error(errorMessage(root) ?: "Stremio request failed")
            return root
        }
    }

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
