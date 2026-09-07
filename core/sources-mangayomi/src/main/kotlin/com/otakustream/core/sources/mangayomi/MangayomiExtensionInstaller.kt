package com.otakustream.core.sources.mangayomi

import com.otakustream.core.database.mangayomi.MangayomiSourceRecord
import com.otakustream.core.database.mangayomi.MangayomiSourceRepository
import com.otakustream.core.sources.api.RemoteCodeUrl
import com.otakustream.core.sources.api.SourceHttpException
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
        // Read first, then build. saveKeepingPrefs restores the row's preferences in the database,
        // but the source handed back here was already constructed — so a re-install returned a live
        // runtime with default settings while the database held the user's, and the two only agreed
        // again at the next cold start. Reachable whenever a re-install happens with the extension
        // already registered, which is exactly what the directory's Install button does.
        val existingPrefs = repository.getPrefs(listing.id)
        val source = factory.create(content, override = listing.toMetadata(), prefsJson = existingPrefs)
        try {
            // Keeps whatever preferences this extension already had — see saveKeepingPrefs.
            repository.saveKeepingPrefs(listing.toRecord(content, repoPrefs.repoUrl))
            source
        } catch (t: Throwable) {
            // The source is built (engine thread + native context live) but not yet handed back
            // to be registered — if persisting it fails, close it here so it can't leak.
            runCatching { source.close() }
            throw t
        }
    }

    suspend fun uninstall(id: Long) = repository.delete(id)

    private suspend fun download(url: String): String {
        // An extension is JavaScript this app executes, so it may not arrive over a channel that
        // can be rewritten in flight.
        RemoteCodeUrl.require(url, "An extension")
        val call = httpClient.newCall(Request.Builder().url(url).build())
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw SourceHttpException(response.code)
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
