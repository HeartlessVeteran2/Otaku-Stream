package com.otakustream.core.sources.mangayomi.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

// The configurable extension-repo URL — the Aniyomi/AnymeX "extension repo" mechanism, pointed at
// a Mangayomi anime_index.json. The app ships no scrapers: with no repo URL set, the browser shows
// only the in-repo public-domain example (DEFAULT_LISTINGS); a repo URL loads that index instead.
@Singleton
class MangayomiRepoPrefs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mangayomi_repo", Context.MODE_PRIVATE)

    var repoUrl: String
        get() = prefs.getString(KEY_REPO_URL, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_REPO_URL, value.trim()).apply() }

    private companion object {
        const val KEY_REPO_URL = "repo_url"
    }
}

@Singleton
class MangayomiRepoClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val prefs: MangayomiRepoPrefs,
) {
    suspend fun fetch(): List<MangayomiExtensionListing> = withContext(Dispatchers.IO) {
        val repoUrl = prefs.repoUrl.trim()
        if (repoUrl.isEmpty()) return@withContext DEFAULT_LISTINGS
        val request = Request.Builder().url(repoUrl).build()
        val call = httpClient.newCall(request)
        // Cancel the blocking OkHttp call if the coroutine is cancelled (navigate away / reload).
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        val content = try {
            call.execute().use { response ->
                require(response.isSuccessful) { "Failed to load extension repo: HTTP ${response.code}" }
                response.body?.string() ?: error("Empty response body")
            }
        } finally {
            cancellation?.dispose()
        }
        parseMangayomiIndex(content)
    }

    companion object {
        // Zero-setup demo: the in-repo public-domain example extension (QuickJS/modern JS).
        val DEFAULT_LISTINGS = listOf(
            MangayomiExtensionListing(
                id = 100000001L,
                name = "Mangayomi Example",
                lang = "en",
                baseUrl = "https://example.invalid",
                iconUrl = null,
                sourceCodeUrl = "https://raw.githubusercontent.com/HeartlessVeteran2/Otaku-Stream/main/sources/mangayomi-example/example-extension.js",
                version = "1.0.0",
                isNsfw = false,
                itemType = 1,
                sourceCodeLanguage = 1,
            ),
        )
    }
}
