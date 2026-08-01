package com.otakustream.core.sources.mangayomi

import com.otakustream.core.sources.api.CatalogPage
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.MediaStatus
import com.otakustream.core.sources.api.SourceFilter
import com.otakustream.core.sources.api.SubtitleTrack
import com.otakustream.core.sources.api.Video
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.core.sources.mangayomi.runtime.MangayomiRuntime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

// Adapts one Mangayomi/AnymeX JS extension (running in a MangayomiRuntime) to the app's
// VideoSource contract. Mapping of Mangayomi shapes → Models.kt:
//   list item {name,imageUrl,link}                 -> MediaItem(url=link, title=name, cover=imageUrl)
//   getDetail {name,imageUrl,description,genre[],status,episodes/chapters[]}
//                                                  -> MediaDetails (+ Episode list, one getDetail call)
//   getVideoList [{url,quality,headers,subtitles}] -> Video (+ SubtitleTrack)
// getFilterList → getAvailableFilters is deferred to a later PR; search runs with empty filters.
class MangayomiVideoSource(
    private val metadata: MangayomiSourceMetadata,
    private val runtime: MangayomiRuntime,
) : VideoSource, AutoCloseable {

    override val id: Long = metadata.id
    override val name: String = metadata.name
    override val lang: String = metadata.lang

    // getMediaDetails and getEpisodeList both derive from the same getDetail(url) call — cache the
    // in-flight job (not the raw string) per media url so concurrent opens (details screen fires
    // both) share one getDetail call rather than racing two.
    //
    // Bounded and access-ordered rather than a plain ConcurrentHashMap. This lives as long as the
    // installed extension does, so unbounded it holds the full JSON of every title the user has
    // opened since the app started.
    private val detailCache = object : LinkedHashMap<String, CachedDetail>(DETAIL_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CachedDetail>): Boolean =
            size > DETAIL_CACHE_MAX
    }

    // Bounding by size does not bound staleness. Access ordering makes the title the user keeps
    // reopening the *last* one evicted — so the show they are actively following, the one most
    // likely to have gained an episode, is precisely the one that would keep serving an old episode
    // list for the rest of the session. A timestamp bounds that however often the entry is read.
    //
    // Monotonic, not wall clock: a device clock correction would otherwise expire every entry at
    // once or push one into the future where it never expires at all.
    private class CachedDetail(val job: Deferred<String>, val startedAtNanos: Long)

    // An access-ordered LinkedHashMap reorders itself on a read, so lookups mutate it too.
    private val detailCacheLock = Any()

    // The shared jobs belong to the source, not to whichever caller created one. Parented to the
    // caller — which is what `coroutineScope { async { } }` did — a cached job dies when that caller
    // does, so backing out of a details screen cancelled the request the next caller was meant to
    // join. SupervisorJob so one failed getDetail cannot cancel the rest.
    private val detailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Releases the runtime's engine thread + native QuickJS context. VideoSource has no lifecycle
    // hook, so the host closes this when the source is uninstalled/reloaded.
    override fun close() {
        // Before the runtime: an in-flight getDetail would otherwise call into a closed QuickJS
        // context.
        detailScope.cancel()
        runtime.close()
    }

    override suspend fun getPopular(page: Int): CatalogPage =
        parseCatalog(runtime.invoke("getPopular", listOf(page)))

    override suspend fun getLatest(page: Int): CatalogPage =
        parseCatalog(runtime.invoke("getLatestUpdates", listOf(page)))

    override suspend fun search(query: String, filters: List<SourceFilter>, page: Int): CatalogPage =
        parseCatalog(runtime.invoke("search", listOf(query, page, JSONArray())))

    override suspend fun getMediaDetails(media: MediaItem): MediaDetails {
        val json = JSONObject(detailFor(media.url))
        val status = when (json.optInt("status", 5)) {
            0, 2 -> MediaStatus.ONGOING // ongoing, onHiatus
            1, 4 -> MediaStatus.COMPLETED // completed, publishingFinished
            else -> MediaStatus.UNKNOWN
        }
        return MediaDetails(
            media = media.copy(
                title = json.optString("name").ifEmpty { media.title },
                coverUrl = json.optString("imageUrl").ifEmpty { media.coverUrl },
            ),
            description = json.optString("description").ifEmpty { null },
            genres = json.optJSONArray("genre").toStringList(),
            status = status,
        )
    }

    override suspend fun getEpisodeList(media: MediaItem): List<Episode> {
        val json = JSONObject(detailFor(media.url))
        // Mangayomi models both manga chapters and anime episodes as `chapters`; some anime
        // extensions emit `episodes`. Accept either.
        val array = json.optJSONArray("episodes") ?: json.optJSONArray("chapters") ?: JSONArray()
        val count = array.length()
        return (0 until count).map { index ->
            val entry = array.getJSONObject(index)
            val epName = entry.optString("name").ifEmpty { "Episode ${index + 1}" }
            Episode(
                url = entry.optString("url"),
                name = epName,
                // Prefer an explicit number, then one parsed from the name, else fall back to the
                // list position (extensions typically list newest-first, so reverse the index).
                episodeNumber = entry.optDouble("episodeNumber", Double.NaN).toFloat()
                    .takeUnless { it.isNaN() }
                    ?: numberInName(epName)
                    ?: (count - index).toFloat(),
                dateUploadEpochMs = entry.optString("dateUpload").toLongOrNull()
                    ?: entry.optLong("dateUpload", 0L),
            )
        }
    }

    override suspend fun getVideoList(episode: Episode): List<Video> {
        val array = JSONArray(runtime.invoke("getVideoList", listOf(episode.url)) ?: "[]")
        return (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            val url = entry.optString("url").ifEmpty { entry.optString("originalUrl") }
            Video(
                url = url,
                quality = entry.optString("quality").ifEmpty { "default" },
                headers = entry.optJSONObject("headers").toStringMap(),
                subtitleTracks = entry.optJSONArray("subtitles").toSubtitleTracks(),
                isM3U8 = url.contains(".m3u8", ignoreCase = true),
            )
        }
    }

    // The extension's declared preferences (list/switch/editText descriptors) as a JSON array, for
    // the per-source settings screen. Guarded: many extensions don't implement getSourcePreferences
    // at all, and invoking a missing method would throw a JS TypeError — treat "no method" as "no
    // preferences" so the screen shows the empty state, not an error.
    suspend fun getSourcePreferences(): String {
        val hasMethod = runtime.readGlobalJson(
            "typeof globalThis.__om_instance.getSourcePreferences === 'function'",
        ) == "true"
        if (!hasMethod) return "[]"
        return runtime.invoke("getSourcePreferences", emptyList()) ?: "[]"
    }

    private suspend fun detailFor(url: String): String {
        val now = System.nanoTime()
        val entry = synchronized(detailCacheLock) {
            val existing = detailCache[url]
            // Age runs from when the request *started*, not when it finished, so a slow response
            // can't arrive already most of the way through its own lifetime.
            if (existing != null && now - existing.startedAtNanos < DETAIL_CACHE_TTL_NANOS) {
                existing
            } else {
                newDetailEntry(url, now).also { detailCache[url] = it }
            }
        }
        // Started outside the lock: keeps the JS call off the critical section, and guarantees the
        // entry is installed in the map before its completion handler can run.
        entry.job.start()
        // Deliberately unguarded: a throw here can mean the shared job failed *or* that this awaiter
        // was cancelled because the user left the screen, and evicting on the second would discard a
        // request still running for everyone else. Eviction is the job's own business.
        return entry.job.await()
    }

    // Caller must hold `detailCacheLock`.
    private fun newDetailEntry(url: String, startedAtNanos: Long): CachedDetail {
        // LAZY, so the job cannot complete before it has been stored. Started eagerly, a getDetail
        // that fails immediately — a closed runtime, an extension throwing on entry — can run its
        // completion handler before `detailCache[url] = entry` executes, so the eviction below finds
        // nothing to remove and the failure then sits in the cache, making the title unopenable.
        val job = detailScope.async(start = CoroutineStart.LAZY) {
            runtime.invoke("getDetail", listOf(url)) ?: "{}"
        }
        val entry = CachedDetail(job, startedAtNanos)
        // A failed job is evicted so a transient error isn't cached and doesn't block every later
        // open of the title. By identity, so a fresh attempt someone else installed survives.
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                synchronized(detailCacheLock) { if (detailCache[url] === entry) detailCache.remove(url) }
            }
        }
        return entry
    }

    private fun parseCatalog(raw: String?): CatalogPage {
        val json = JSONObject(raw ?: "{}")
        val list = json.optJSONArray("list") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { index ->
            val entry = list.optJSONObject(index) ?: return@mapNotNull null
            val link = entry.optString("link")
            if (link.isEmpty()) return@mapNotNull null
            MediaItem(
                url = link,
                title = entry.optString("name"),
                coverUrl = entry.optString("imageUrl").ifEmpty { null },
            )
        }
        return CatalogPage(items = items, hasNextPage = json.optBoolean("hasNextPage", false))
    }

    private fun numberInName(name: String): Float? =
        Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toFloatOrNull()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        // optString coerces non-string header values instead of throwing on them.
        return keys().asSequence().associateWith { optString(it) }
    }

    private fun JSONArray?.toSubtitleTracks(): List<SubtitleTrack> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val entry = optJSONObject(index) ?: return@mapNotNull null
            val url = entry.optString("file").ifEmpty { entry.optString("url") }
            if (url.isEmpty()) return@mapNotNull null
            val label = entry.optString("label").ifEmpty { entry.optString("language").ifEmpty { "Subtitle" } }
            SubtitleTrack(url = url, lang = label, label = label)
        }
    }

    private companion object {
        // Titles to keep getDetail JSON for. Small on purpose: what this exists to capture is the
        // two calls one details screen makes, and there is one of these per installed extension.
        const val DETAIL_CACHE_MAX = 20

        // How long a cached getDetail response stays usable. Long enough to cover the pair of calls
        // one details screen makes and a user flicking between titles; short enough that a show
        // which aired an episode while the app sat in the background is picked up on the next open
        // rather than at the next cold start.
        val DETAIL_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5)
    }
}
