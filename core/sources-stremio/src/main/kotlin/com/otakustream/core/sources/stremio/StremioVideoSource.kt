package com.otakustream.core.sources.stremio

import com.otakustream.core.database.stremio.StremioRepository
import com.otakustream.core.sources.api.CatalogPage
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.MediaStatus
import com.otakustream.core.sources.api.SourceFilter
import com.otakustream.core.sources.api.Video
import com.otakustream.core.sources.api.VideoSource
import com.otakustream.core.sources.stremio.model.StremioCatalog
import com.otakustream.core.sources.stremio.model.StremioStream
import com.otakustream.core.sources.stremio.model.parseCatalogResponse
import com.otakustream.core.sources.stremio.model.parseMetaResponse
import com.otakustream.core.sources.stremio.model.parseStreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

// One instance per (addon, catalog) pair — Stremio addons can declare multiple catalogs, and
// VideoSource models a single browsable catalog, so installing an addon registers one
// VideoSource per catalog entry in its manifest.
class StremioVideoSource(
    private val httpClient: OkHttpClient,
    private val stremioRepository: StremioRepository,
    manifestUrl: String,
    private val catalog: StremioCatalog,
    override val id: Long,
    override val name: String,
    override val lang: String = "en",
) : VideoSource {

    private val baseUrl: String = manifestUrl.removeSuffix("/manifest.json")

    override suspend fun getPopular(page: Int): CatalogPage = fetchCatalog(pagingExtra(page))

    // Stremio has no distinct "latest" concept for a catalog — alias to the same catalog fetch.
    override suspend fun getLatest(page: Int): CatalogPage = fetchCatalog(pagingExtra(page))

    override suspend fun search(query: String, filters: List<SourceFilter>, page: Int): CatalogPage {
        if ("search" !in catalog.extraNames) return CatalogPage(emptyList(), false)
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return fetchCatalog("search=$encodedQuery&skip=${(page - 1) * PAGE_SIZE}")
    }

    override suspend fun getMediaDetails(media: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val (type, id) = splitTypeId(media.url)
        val meta = parseMetaResponse(get("$baseUrl/meta/$type/$id.json"))
        MediaDetails(media = media, description = meta.description, genres = meta.genres, status = MediaStatus.UNKNOWN)
    }

    override suspend fun getEpisodeList(media: MediaItem): List<Episode> = withContext(Dispatchers.IO) {
        val (type, id) = splitTypeId(media.url)
        val meta = parseMetaResponse(get("$baseUrl/meta/$type/$id.json"))
        if (meta.videos.isEmpty()) {
            listOf(Episode(url = "$type|${meta.id}", name = meta.name, episodeNumber = 1f))
        } else {
            meta.videos.mapIndexed { index, video ->
                Episode(url = "$type|${video.id}", name = video.title, episodeNumber = (video.episode ?: (index + 1)).toFloat())
            }
        }
    }

    override suspend fun getVideoList(episode: Episode): List<Video> = withContext(Dispatchers.IO) {
        val (type, videoId) = splitTypeId(episode.url)
        val streams = parseStreamResponse(get("$baseUrl/stream/$type/$videoId.json")).streams
        val serverBaseUrl = stremioRepository.getServerBaseUrl()
        streams.mapNotNull { stream -> stream.toVideo(serverBaseUrl) }
    }

    private fun StremioStream.toVideo(serverBaseUrl: String?): Video? = when {
        url != null -> Video(url = url, quality = name ?: "default", isM3U8 = url.contains(".m3u8"))
        infoHash != null && !serverBaseUrl.isNullOrBlank() ->
            Video(url = "${serverBaseUrl.trimEnd('/')}/$infoHash/${fileIdx ?: 0}", quality = name ?: "torrent")
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

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Stremio request failed: HTTP ${response.code}" }
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
    }
}
