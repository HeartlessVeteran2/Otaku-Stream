package com.otakustream.sources.example

import com.otakustream.core.sources.api.CatalogPage
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaDetails
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.MediaStatus
import com.otakustream.core.sources.api.SourceFilter
import com.otakustream.core.sources.api.Video
import com.otakustream.core.sources.api.VideoSource

private data class CatalogEntry(val media: MediaItem, val videoUrl: String, val description: String)

// Reference VideoSource backed by public-domain sample videos, proving the pipeline end to end.
class ExampleVideoSource : VideoSource {
    override val id: Long = 1L
    override val name: String = "Example"
    override val lang: String = "en"

    private val catalog = listOf(
        CatalogEntry(
            media = MediaItem(url = "example://big-buck-bunny", title = "Big Buck Bunny"),
            videoUrl = "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            description = "Blender Foundation short film, Creative Commons Attribution 3.0.",
        ),
        CatalogEntry(
            media = MediaItem(url = "example://sintel", title = "Sintel"),
            videoUrl = "https://storage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
            description = "Blender Foundation short film, Creative Commons Attribution 3.0.",
        ),
        CatalogEntry(
            media = MediaItem(url = "example://bipbop", title = "Bip Bop Test Pattern"),
            videoUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",
            description = "Apple's public HLS test stream.",
        ),
    )

    override suspend fun getPopular(page: Int): CatalogPage = pageOf(catalog.map { it.media }, page)

    override suspend fun getLatest(page: Int): CatalogPage = pageOf(catalog.map { it.media }, page)

    override suspend fun search(query: String, filters: List<SourceFilter>, page: Int): CatalogPage =
        pageOf(catalog.map { it.media }.filter { it.title.contains(query, ignoreCase = true) }, page)

    override suspend fun getMediaDetails(media: MediaItem): MediaDetails {
        val entry = catalog.first { it.media.url == media.url }
        return MediaDetails(media = media, description = entry.description, genres = listOf("Demo"), status = MediaStatus.COMPLETED)
    }

    override suspend fun getEpisodeList(media: MediaItem): List<Episode> =
        listOf(Episode(url = media.url, name = "Full video", episodeNumber = 1f))

    override suspend fun getVideoList(episode: Episode): List<Video> {
        val entry = catalog.first { it.media.url == episode.url }
        return listOf(Video(url = entry.videoUrl, quality = "Source", isM3U8 = entry.videoUrl.endsWith(".m3u8")))
    }

    private fun pageOf(items: List<MediaItem>, page: Int): CatalogPage =
        if (page == 1) CatalogPage(items = items, hasNextPage = false) else CatalogPage(items = emptyList(), hasNextPage = false)
}
