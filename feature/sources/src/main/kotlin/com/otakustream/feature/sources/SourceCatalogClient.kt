package com.otakustream.feature.sources

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

// One installable source in the catalog directory: either a scripted (.js) source or a Stremio
// add-on manifest, both installed by URL.
data class SourceCatalogEntry(
    val name: String,
    val type: String, // TYPE_SCRIPTED | TYPE_STREMIO
    val url: String,
    val lang: String,
    val description: String?,
) {
    companion object {
        const val TYPE_SCRIPTED = "scripted"
        const val TYPE_STREMIO = "stremio"
    }
}

// A configurable, community-maintainable directory of installable sources — the Aniyomi-style
// "extension repo" mechanism. The app ships no scrapers: the default (empty repo URL) lists only
// the in-repo public-domain example, and pointing at a repo URL loads that JSON instead.
@Singleton
class SourceCatalogPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("source_catalog", Context.MODE_PRIVATE)

    var repoUrl: String
        get() = prefs.getString(KEY_REPO_URL, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_REPO_URL, value.trim()).apply() }

    private companion object {
        const val KEY_REPO_URL = "repo_url"
    }
}

@Singleton
class SourceCatalogClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val prefs: SourceCatalogPrefs,
) {
    suspend fun fetch(): List<SourceCatalogEntry> = withContext(Dispatchers.IO) {
        val repoUrl = prefs.repoUrl.trim()
        if (repoUrl.isEmpty()) return@withContext DEFAULT_ENTRIES
        val request = Request.Builder().url(repoUrl).build()
        val content = httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to load source catalog: HTTP ${response.code}" }
            response.body?.string() ?: error("Empty response body")
        }
        parse(content)
    }

    private fun parse(json: String): List<SourceCatalogEntry> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name").ifEmpty { return@mapNotNull null }
            val type = obj.optString("type").ifEmpty { return@mapNotNull null }
            if (type != SourceCatalogEntry.TYPE_SCRIPTED && type != SourceCatalogEntry.TYPE_STREMIO) return@mapNotNull null
            val url = obj.optString("url").ifEmpty { return@mapNotNull null }
            SourceCatalogEntry(
                name = name,
                type = type,
                url = url,
                lang = obj.optString("lang").ifEmpty { "en" },
                description = obj.optString("description").ifEmpty { null },
            )
        }
    }

    companion object {
        // Ships as a working demo with zero setup: the in-repo public-domain scripted example.
        val DEFAULT_ENTRIES = listOf(
            SourceCatalogEntry(
                name = "Scripted Example",
                type = SourceCatalogEntry.TYPE_SCRIPTED,
                url = "https://raw.githubusercontent.com/HeartlessVeteran2/Otaku-Stream/main/sources/scripted-example/example-source.js",
                lang = "en",
                description = "Public-domain sample source (Blender films) that validates the scripted-source pipeline.",
            ),
        )
    }
}
