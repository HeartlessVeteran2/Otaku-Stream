package com.otakustream.core.sources.mangayomi

import com.otakustream.core.database.mangayomi.MangayomiSourceRecord
import com.otakustream.core.database.mangayomi.MangayomiSourceRepository
import com.otakustream.core.sources.mangayomi.repo.MangayomiExtensionListing
import com.otakustream.core.sources.mangayomi.repo.MangayomiRepoPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

// Installs a Mangayomi/AnymeX extension from a repo listing: downloads its `.js`, validates it
// actually loads under QuickJS (MangayomiSourceFactory.create forces bringup), caches the script
// inline in Room so cold start needs no network, and returns the live source to register.
@Singleton
class MangayomiExtensionInstaller @Inject constructor(
    private val httpClient: OkHttpClient,
    private val factory: MangayomiSourceFactory,
    private val repository: MangayomiSourceRepository,
    private val repoPrefs: MangayomiRepoPrefs,
) {
    suspend fun install(listing: MangayomiExtensionListing): MangayomiVideoSource = withContext(Dispatchers.IO) {
        val content = download(listing.sourceCodeUrl)
        val source = factory.create(content, override = listing.toMetadata())
        repository.save(listing.toRecord(content, repoPrefs.repoUrl))
        source
    }

    suspend fun uninstall(id: Long) = repository.delete(id)

    private suspend fun download(url: String): String {
        val call = httpClient.newCall(Request.Builder().url(url).build())
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute().use { response ->
                require(response.isSuccessful) { "Failed to download extension: HTTP ${response.code}" }
                response.body?.string() ?: error("Empty extension body")
            }
        } finally {
            cancellation?.dispose()
        }
    }
}

internal fun MangayomiExtensionListing.toMetadata() = MangayomiSourceMetadata(
    id = id,
    name = name,
    lang = lang,
    baseUrl = baseUrl,
    iconUrl = iconUrl,
    version = version,
    isNsfw = isNsfw,
)

internal fun MangayomiExtensionListing.toRecord(content: String, repoUrl: String) = MangayomiSourceRecord(
    id = id,
    repoUrl = repoUrl,
    sourceCodeUrl = sourceCodeUrl,
    scriptContent = content,
    name = name,
    lang = lang,
    baseUrl = baseUrl,
    iconUrl = iconUrl,
    version = version,
    isNsfw = isNsfw,
    itemType = itemType,
    sourceCodeLanguage = sourceCodeLanguage,
    prefsJson = null,
)
