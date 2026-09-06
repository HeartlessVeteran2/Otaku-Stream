package com.otakustream.core.sources.mangayomi.repo

import android.content.Context
import com.otakustream.core.network.await
import com.otakustream.core.sources.api.RemoteCodeUrl
import com.otakustream.core.sources.api.SourceHttpException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

// An extension repo the user typed in themselves, kept alongside the curated ones rather than
// instead of them.
//
// This used to be the *only* source of listings, which is why the screen looked empty: with nothing
// set it showed a single sample extension pointing at example.invalid. See RecommendedExtensionRepos.
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

// Everything the extensions screen needs to render itself honestly: what it found, what it could
// not use, and what it could not reach.
data class ExtensionDirectory(
    val listings: List<MangayomiExtensionListing> = emptyList(),
    // Entries in the repos that this app cannot run — Dart extensions, and manga/novel sources.
    val unsupportedCount: Int = 0,
    // Repos that failed to load, by name. Reported rather than thrown so one dead repo does not
    // blank a screen that still has two working ones on it.
    val unreachableRepos: List<String> = emptyList(),
    // The error for a user-supplied URL specifically, which is different in kind: somebody typed it
    // and needs to be told it is wrong.
    val customRepoError: String? = null,
    val showAdult: Boolean = false,
)

// Loads the browsable extension directory: the curated repos plus the user's own, merged.
//
// Shaped after StremioAddonDirectoryClient, which had already solved this exact problem for add-ons
// — fetch the lists concurrently, merge them, dedupe, and label each row with where it came from.
// Doing it any other way here would have meant a repo *picker*, because the stored repoUrl is a
// single string: loading a second repo replaced the first one's list, so seeing everything meant
// pasting three URLs in turn and remembering which you had already done.
@Singleton
class MangayomiRepoClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val prefs: MangayomiRepoPrefs,
) {

    // showAdult is passed in rather than read here: AdultContentSettings lives in the Stremio
    // module and this one must not depend on it. The caller (MangayomiExtensionsViewModel) already
    // holds both, and filtering there still keeps adult listings out of the screen's state entirely
    // rather than merely unrendered — which is the property that matters.
    suspend fun fetch(showAdult: Boolean): ExtensionDirectory = coroutineScope {
        val customUrl = prefs.repoUrl.trim().takeIf { it.isNotEmpty() }

        val curated = RecommendedExtensionRepos.repos.map { repo ->
            async { CuratedResult(repo.name, runCatchingIndex(repo.indexUrl, repo.name)) }
        }
        // Fetched as a Result rather than null-on-failure, unlike the curated repos: a URL somebody
        // typed is one they need to be told is wrong, where a curated repo being down is the app's
        // problem to report quietly and carry on.
        val custom = customUrl?.let { url ->
            async {
                try {
                    // Bounded separately from the curated repos. They are known URLs; this one is
                    // whatever somebody typed, and it must not be able to hold the whole directory
                    // behind a loading spinner — the curated extensions have already arrived by
                    // then and are what the screen is for.
                    val parsed = withTimeoutOrNull(CUSTOM_REPO_TIMEOUT_MS) {
                        fetchIndex(url, CUSTOM_REPO_NAME)
                    } ?: error("That repository took too long to respond.")
                    Result.success(parsed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

        val curatedResults = curated.awaitAll()
        val customResult = custom?.await()

        val parsed = curatedResults.mapNotNull { it.parsed } + listOfNotNull(customResult?.getOrNull())

        // Curated first, then the user's own — deduped on the same (id, lang) key the parser uses,
        // so an extension carried by two repos shows once, keeping the repo it was found in first.
        val merged = parsed.flatMap { it.listings }
            .distinctBy { it.id to it.lang }
            .filter { showAdult || !it.isNsfw }

        ExtensionDirectory(
            listings = merged,
            unsupportedCount = parsed.sumOf { it.unsupportedCount },
            unreachableRepos = curatedResults.filter { it.parsed == null }.map { it.repoName },
            customRepoError = customResult?.exceptionOrNull()?.let { failure ->
                failure.message ?: "Couldn't load that extension repository."
            },
            showAdult = showAdult,
        )
    }

    // Null on failure: a curated repo being unreachable is reported next to the ones that worked,
    // not raised. Cancellation is rethrown — a cancelled load is not a broken repo.
    private suspend fun runCatchingIndex(url: String, repoName: String): ParsedIndex? = try {
        fetchIndex(url, repoName)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchIndex(url: String, repoName: String): ParsedIndex = withContext(Dispatchers.IO) {
        // Held to the same bar as the extensions themselves, and for a sharper reason: this index
        // supplies the sourceCodeUrl for every extension in it, so rewriting one cleartext response
        // redirects every install made from it — including of extensions the user picked by name.
        // The curated URLs go through this too; nothing is trusted for being in the list.
        RemoteCodeUrl.require(url, "An extension repository")
        val request = Request.Builder().url(url).build()
        val content = httpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                // A 404 on a saved repo is worth naming, because it has already happened to
                // everyone who set the obvious URL: kodjodevf's index became manga-only and its
                // anime_index.json now 404s, with no hint from inside the app that anime moved.
                if (response.code == HTTP_NOT_FOUND) {
                    error("That repository has no anime index (404). It may have moved — try one of the suggested repositories.")
                }
                throw SourceHttpException(response.code, url)
            }
            response.body?.string() ?: error("Empty response body")
        }
        parseMangayomiIndex(content, repoName)
    }

    private class CuratedResult(val repoName: String, val parsed: ParsedIndex?)

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val CUSTOM_REPO_NAME = "Your repository"

        // Long enough for a slow host, short enough that a dead one is not an outage.
        const val CUSTOM_REPO_TIMEOUT_MS = 15_000L
    }
}
