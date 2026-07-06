package com.otakustream.core.sources.api

interface VideoSource {
    val id: Long
    val name: String
    val lang: String

    suspend fun getPopular(page: Int): CatalogPage
    suspend fun getLatest(page: Int): CatalogPage
    suspend fun search(query: String, filters: List<SourceFilter>, page: Int): CatalogPage
    suspend fun getMediaDetails(media: MediaItem): MediaDetails
    suspend fun getEpisodeList(media: MediaItem): List<Episode>
    suspend fun getVideoList(episode: Episode): List<Video>
}

interface VideoSourceFactory {
    fun createSources(): List<VideoSource>
}
