package com.otakustream.core.sources.stremio.account

import com.otakustream.core.network.await
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
    // The server's own document for this item, verbatim, when it came from fetchLibrary — null for
    // an item built locally that the account has never seen.
    //
    // Carried as raw JSON rather than as parsed fields on purpose. A push replaces the whole
    // document, so anything not round-tripped here is destroyed on the user's account; keeping the
    // text means fields this app has never heard of survive a push made by a version of the app
    // written before they existed. See libraryItemDocument.
    val remoteJson: String? = null,
) {
    // The app encodes Stremio catalog items as "type|id", so a pulled item lines up with the same
    // local library key a saved catalog item would use.
    val mediaUrl: String get() = "$type|$id"
}

// Talks to Stremio's account API (api.strem.io): logs in for an authKey and reads/writes the user's
// personal library ("libraryItem" datastore collection). Reuses the app-wide OkHttpClient. Only the
// authKey is a credential the caller persists — the password is used once here and never stored.
// An interface so the screen driving it can be tested without a network — see StremioAccountStore
// for the argument in full. Three methods, which is the whole of what the ViewModel calls; the
// request building and JSON parsing stay private to the implementation.
interface StremioAccountClient {
    suspend fun login(email: String, password: String): StremioAccount

    suspend fun fetchLibrary(authKey: String): List<StremioLibraryItem>

    suspend fun putLibraryItems(authKey: String, items: List<StremioLibraryItem>)
}

@Singleton
class StremioAccountClientImpl @Inject constructor(
    @com.otakustream.core.network.di.AccountHttpClient private val httpClient: OkHttpClient,
) : StremioAccountClient {
    override suspend fun login(email: String, password: String): StremioAccount = withContext(Dispatchers.IO) {
        val root = post("$API_BASE/login", JSONObject().put("email", email).put("password", password))
        val result = root.optJSONObject("result") ?: error(errorMessage(root) ?: "Stremio login failed")
        val authKey = result.optString("authKey").ifEmpty { error("Stremio login returned no auth key") }
        val userEmail = result.optJSONObject("user")?.optString("email")?.ifEmpty { null } ?: email
        StremioAccount(authKey = authKey, email = userEmail)
    }

    override suspend fun fetchLibrary(authKey: String): List<StremioLibraryItem> = withContext(Dispatchers.IO) {
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
                remoteJson = obj.toString(),
            )
        }
    }

    // Push local saves up to the Stremio account. An item the account already has is re-sent as the
    // server's own document with only `removed` touched; a genuinely new one is built from scratch.
    // libraryItemDocument owns that distinction and explains why it matters.
    override suspend fun putLibraryItems(authKey: String, items: List<StremioLibraryItem>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val now = isoNow()
        // mapNotNull, not map: libraryItemDocument returns null for an existing item whose document
        // couldn't be parsed, and dropping that item is the whole point — it is better to leave a
        // row un-pushed than to replace one we couldn't read.
        val documents = items.mapNotNull { libraryItemDocument(it, now) }
        if (documents.isEmpty()) return@withContext
        val changes = JSONArray().apply { documents.forEach { put(it) } }
        val body = JSONObject().put("authKey", authKey).put("collection", "libraryItem").put("changes", changes)
        post("$API_BASE/datastorePut", body)
        Unit
    }

    private fun errorMessage(root: JSONObject): String? =
        root.optJSONObject("error")?.let { if (it.isNull("message")) null else it.optString("message").ifEmpty { null } }

    // suspend + await(), not a blocking execute(). Cancelling the coroutine — the user leaving the
    // account screen mid-push, or the ViewModel scope dying — could not interrupt execute(): it
    // returned immediately while the request carried on underneath holding a thread and a
    // connection with nobody left to read the result. Same fix AniListClient already carries.
    private suspend fun post(url: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).await().use { response ->
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
