package com.otakustream.core.sources.api

interface VideoSource {
    val id: Long
    val name: String
    val lang: String

    suspend fun getPopular(page: Int): CatalogPage
    suspend fun getLatest(page: Int): CatalogPage
    suspend fun search(query: String, filters: List<SourceFilter>, page: Int): CatalogPage

    // Filters this source declares support for (e.g. genre). Defaulted to none so existing
    // sources (ExampleVideoSource, scripted sources) don't need any changes to keep compiling.
    suspend fun getAvailableFilters(): List<SourceFilter> = emptyList()
    suspend fun getMediaDetails(media: MediaItem): MediaDetails
    suspend fun getEpisodeList(media: MediaItem): List<Episode>
    suspend fun getVideoList(episode: Episode): List<Video>
}

interface VideoSourceFactory {
    fun createSources(): List<VideoSource>
}
