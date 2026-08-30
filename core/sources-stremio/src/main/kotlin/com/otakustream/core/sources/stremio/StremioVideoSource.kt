package com.otakustream.core.sources.stremio

import com.otakustream.core.common.InFlightCache
import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.api.CatalogPage
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.MediaStatus
import com.otakustream.core.sources.api.SourceFilter
import com.otakustream.core.network.await
import com.otakustream.core.sources.api.SourceHttpException
import com.otakustream.core.sources.api.SubtitleTrack
import com.otakustream.core.sources.api.Video
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.core.sources.stremio.model.StremioCatalog
import com.otakustream.core.sources.stremio.model.StremioMeta
import com.otakustream.core.sources.stremio.model.StremioStream
import com.otakustream.core.sources.stremio.model.parseCatalogResponse
import com.otakustream.core.sources.stremio.model.parseMetaResponse
import com.otakustream.core.sources.stremio.model.parseStreamResponse
import com.otakustream.core.sources.stremio.model.parseSubtitlesResponse
import com.otakustream.core.torrent.TorrentUri
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// One instance per (addon, catalog) pair — Stremio addons can declare multiple catalogs, and
// VideoSource models a single browsable catalog, so installing an addon registers one
// VideoSource per catalog entry in its manifest.
class StremioVideoSource(
    private val httpClient: OkHttpClient,
    private val stremioRepository: StremioRepository,
    private val streamProviderRegistry: StremioStreamProviderRegistry,
    // A lambda, not a Boolean: reading it forces the native library probe, and doing that when a
    // source is constructed would run it during add-on bootstrap for every installed add-on. This
    // way it happens only when a torrent stream actually needs resolving.
    private val onDeviceTorrentsAvailable: () -> Boolean,
    manifestUrl: String,
    private val catalog: StremioCatalog,
    private val resources: Set<String>,
    override val id: Long,
    override val name: String,
    override val lang: String = "en",
) : VideoSource {

    private val baseUrl: String = manifestUrl.removeSuffix("/manifest.json")

    // getMediaDetails and getEpisodeList both derive from the same /meta response, and the details
    // screen calls them one after the other — so opening any title fetched the identical URL twice,
    // sequentially, before anything appeared on screen. InFlightCache covers both halves of that: a
    // caller arriving while the request is open joins it, and one arriving shortly after it finished
    // reuses the result until the TTL expires, measured from when the response landed.
    //
    // The scope is the source's own, not any caller's: a StremioVideoSource lives for the whole
    // process, and a job parented to whoever happened to ask first would die when they navigated
    // away. SupervisorJob so one add-on's failed meta fetch cannot take its siblings down.
    private val metaScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val metaCache = InFlightCache<String, StremioMeta>(
        scope = metaScope,
        maxEntries = META_CACHE_MAX,
        ttlMs = META_CACHE_TTL_MS,
    ) { key ->
        val (type, id) = key.split('|', limit = 2)
        parseMetaResponse(get("$baseUrl/meta/$type/$id.json"))
    }

    private suspend fun metaFor(type: String, id: String): StremioMeta = metaCache.get("$type|$id")

    override suspend fun getPopular(page: Int): CatalogPage = fetchCatalog(pagingExtra(page))

    // Stremio has no distinct "latest" concept for a catalog — alias to the same catalog fetch.
    override suspend fun getLatest(page: Int): CatalogPage = fetchCatalog(pagingExtra(page))

    override suspend fun search(query: String, filters: List<SourceFilter>, page: Int): CatalogPage {
        val extraParts = mutableListOf<String>()
        if (query.isNotBlank()) {
            // A blank query means "browse with filters only" (e.g. genre) — that only needs the
            // filter's own extra name to be declared, not "search".
            if ("search" !in catalog.extraNames) return CatalogPage(emptyList(), false)
            extraParts += "search=${encodeExtraValue(query)}"
        }
        filters.forEach { filter ->
            val value = filter.values.getOrNull(filter.selected) ?: return@forEach
            // Filters are merged globally across every registered source in the UI, so this
            // source may be handed a value from a filter it declares by name but doesn't
            // actually offer this value for (e.g. another addon's genre list) — only forward
            // values this catalog's own extra actually declared.
            val extra = catalog.extras.find { it.name == filter.name } ?: return@forEach
            if (value !in extra.options.orEmpty()) return@forEach
            extraParts += "${encodeExtraValue(filter.name)}=${encodeExtraValue(value)}"
        }
        extraParts += "skip=${(page - 1) * PAGE_SIZE}"
        return fetchCatalog(extraParts.joinToString("&"))
    }

    // URLEncoder.encode renders spaces as "+", which is correct for query strings but not
    // reliably accepted by every addon server in a path segment (where Stremio's extra
    // parameters live) — "%20" is the safer, more widely-compatible choice here.
    private fun encodeExtraValue(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // Surfaces catalog extras that declare selectable options (e.g. genre) as generic filters —
    // already parsed from the manifest, so this needs no network call despite being suspend.
    override suspend fun getAvailableFilters(): List<SourceFilter> =
        catalog.extras.mapNotNull { extra ->
            val options = extra.options?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SourceFilter(name = extra.name, values = options)
        }

    override suspend fun getMediaDetails(media: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val (type, id) = splitTypeId(media.url)
        val meta = metaFor(type, id)
        MediaDetails(
            media = media,
            description = meta.description,
            genres = meta.genres,
            status = MediaStatus.UNKNOWN,
            backgroundUrl = meta.background,
            logoUrl = meta.logo,
            imdbRating = meta.imdbRating,
            runtime = meta.runtime,
            cast = meta.cast,
            director = meta.director,
            trailerYoutubeId = meta.trailerYoutubeId,
        )
    }

    override suspend fun getEpisodeList(media: MediaItem): List<Episode> = withContext(Dispatchers.IO) {
        val (type, id) = splitTypeId(media.url)
        val meta = metaFor(type, id)
        if (meta.videos.isEmpty()) {
            listOf(Episode(url = "$type|${meta.id}", name = meta.name, episodeNumber = 1f))
        } else {
            meta.videos.mapIndexed { index, video ->
                Episode(
                    url = "$type|${video.id}",
                    name = video.title,
                    episodeNumber = (video.episode ?: (index + 1)).toFloat(),
                    season = video.season,
                )
            }
        }
    }

    override suspend fun getVideoList(episode: Episode): List<Video> = withContext(Dispatchers.IO) {
        val (type, videoId) = splitTypeId(episode.url)
        // This catalog's own /stream, plus every installed stream provider (Torrentio etc.) that
        // handles this type/id — so catalog-less stream add-ons contribute streams here. All
        // fetched concurrently; a failing or slow provider never blocks the others.
        coroutineScope {
            val ownStreamsDeferred = async { fetchStreams("$baseUrl/stream/$type/$videoId.json") }
            // Exclude this add-on's own base URL — an all-in-one add-on (catalog + stream) would
            // otherwise be queried twice.
            val providerStreamDeferreds = streamProviderRegistry
                .providersFor(STREMIO_RESOURCE_STREAM, type, videoId)
                .filter { it.baseUrl != baseUrl }
                .map { provider -> async { fetchStreams("${provider.baseUrl}/stream/$type/$videoId.json") } }
            val subtitleTracksDeferred = async { fetchAllSubtitleTracks(type, videoId) }
            val serverBaseUrl = stremioRepository.getServerBaseUrl()
            val allStreams = ownStreamsDeferred.await() + providerStreamDeferreds.awaitAll().flatten()
            val subtitleTracks = subtitleTracksDeferred.await()
            allStreams.mapNotNull { stream -> stream.toVideo(serverBaseUrl, subtitleTracks) }
                .distinctBy { it.url }
        }
    }

    // Best-effort stream fetch: the per-call timeout in get() bounds a dead/slow endpoint, and
    // failures yield no streams rather than propagating so one provider can't sink the others.
    private suspend fun fetchStreams(url: String): List<StremioStream> =
        runCatching { parseStreamResponse(get(url)).streams }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }

    // Subtitles are aggregated the same way streams are: this add-on's own /subtitles endpoint plus
    // every installed add-on that declares the subtitles resource for this type/id. Without the
    // fan-out a subtitle-only add-on (OpenSubtitles) could be installed and would never be asked
    // for anything, since it has no catalog and therefore no VideoSource of its own (issue #12).
    private suspend fun fetchAllSubtitleTracks(type: String, videoId: String): List<SubtitleTrack> =
        coroutineScope {
            // Own endpoint only when the manifest declares support — no point probing otherwise.
            val ownDeferred = if (STREMIO_RESOURCE_SUBTITLES in resources) {
                async { fetchSubtitleTracks("$baseUrl/subtitles/$type/$videoId.json", providerName = null) }
            } else {
                null
            }
            // Self-excluded by base URL so an all-in-one add-on isn't queried twice.
            val providerDeferreds = streamProviderRegistry
                .providersFor(STREMIO_RESOURCE_SUBTITLES, type, videoId)
                .filter { it.baseUrl != baseUrl }
                .map { provider ->
                    async {
                        fetchSubtitleTracks(
                            "${provider.baseUrl}/subtitles/$type/$videoId.json",
                            providerName = provider.name,
                        )
                    }
                }
            (ownDeferred?.await().orEmpty() + providerDeferreds.awaitAll().flatten())
                .distinctBy { it.url }
        }

    // Best-effort, per-provider: a dead or slow subtitle add-on yields no tracks rather than
    // sinking the others or breaking stream resolution. Tracks from another add-on carry its name
    // in the label, because merging several providers otherwise gives the user a list of
    // indistinguishable "English" entries.
    private suspend fun fetchSubtitleTracks(url: String, providerName: String?): List<SubtitleTrack> =
        runCatching {
            parseSubtitlesResponse(get(url)).subtitles.map {
                SubtitleTrack(
                    url = it.url,
                    lang = it.lang,
                    label = if (providerName.isNullOrBlank()) it.lang else "${it.lang} — $providerName",
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }

    // Two ways to play a torrent-backed stream, in this order of preference:
    //
    // 1. A configured Stremio streaming server, if the user has one. It keeps working exactly as
    //    before, and someone who went to the trouble of hosting one presumably wants it used.
    // 2. The on-device engine (issue #8), which needs no server at all.
    //
    // Before either existed, a stream with only an infoHash was silently dropped — the list looked
    // populated and played nothing.
    private fun StremioStream.toVideo(serverBaseUrl: String?, subtitleTracks: List<SubtitleTrack>): Video? = when {
        !url.isNullOrBlank() -> Video(
            url = url,
            quality = name ?: "default",
            isM3U8 = url.contains(".m3u8", ignoreCase = true),
            subtitleTracks = subtitleTracks,
        )
        !infoHash.isNullOrBlank() && !serverBaseUrl.isNullOrBlank() -> Video(
            url = "${serverBaseUrl.trimEnd('/')}/$infoHash/${fileIdx ?: 0}",
            quality = name ?: "torrent",
            subtitleTracks = subtitleTracks,
            // Carried even on the streaming-server path: harmless there (the server does its own
            // peer discovery), and it keeps the field populated from one place rather than only on
            // the on-device path added later.
            trackers = trackers,
        )
        // No server configured, so fall back to playing it on-device. torrent:// is resolved by
        // :core:torrent via the player's data source; trackers ride alongside because the url has to
        // stay a stable identity (resume position and history are keyed on it).
        !infoHash.isNullOrBlank() && onDeviceTorrentsAvailable() -> TorrentUri.build(infoHash, fileIdx)
            ?.let { torrentUrl ->
                Video(
                    url = torrentUrl,
                    quality = name ?: "torrent",
                    subtitleTracks = subtitleTracks,
                    trackers = trackers,
                )
            }
        else -> null
    }

    private suspend fun fetchCatalog(extra: String?): CatalogPage = withContext(Dispatchers.IO) {
        val path = "$baseUrl/catalog/${catalog.type}/${catalog.id}" + (extra?.let { "/$it" } ?: "") + ".json"
        val metas = parseCatalogResponse(get(path)).metas
        val items = metas.map { MediaItem(url = "${it.type.ifEmpty { catalog.type }}|${it.id}", title = it.name, coverUrl = it.poster) }
        // Stremio's protocol has no explicit "more pages available" signal; a non-empty page is
        // the only usable heuristic.
        CatalogPage(items = items, hasNextPage = items.isNotEmpty())
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        val call = httpClient.newCall(request).apply {
            // Still a per-call timeout, and still for the same reason: it is the only thing that
            // bounds a provider that accepts the connection and then says nothing. Cancellation and
            // a deadline answer different questions — await() stops a request nobody wants any more,
            // the timeout stops one nobody is going to answer.
            timeout().timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        call.await().use { response ->
            if (!response.isSuccessful) throw SourceHttpException(response.code, url)
            return response.body?.string() ?: error("Empty response body")
        }
    }

    private fun splitTypeId(value: String): Pair<String, String> {
        val separatorIndex = value.indexOf('|')
        require(separatorIndex >= 0) { "Malformed Stremio id: $value" }
        return value.substring(0, separatorIndex) to value.substring(separatorIndex + 1)
    }

    private fun pagingExtra(page: Int): String? = if (page <= 1) null else "skip=${(page - 1) * PAGE_SIZE}"

    private companion object {
        // Stremio addons commonly page in chunks around this size; used only to compute "skip",
        // not enforced by the protocol itself.
        const val PAGE_SIZE = 100

        // Cap every Stremio request so a single slow endpoint (e.g. an overloaded torrent
        // indexer) can't hold up catalog/detail/stream resolution.
        const val REQUEST_TIMEOUT_MS = 12_000L

        // Titles to keep /meta responses for. Small on purpose: the value it exists to capture is
        // the two calls one details screen makes, and there is one of these caches per registered
        // catalog, not one for the app.
        const val META_CACHE_MAX = 20

        // How long a cached /meta response stays usable, measured from when it arrived. Long enough
        // to cover the pair of calls one details screen makes and a user flicking back and forth
        // between titles; short enough that a show which aired an episode while the app sat in the
        // background is picked up on the next open rather than at the next cold start.
        val META_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5)
    }
}
