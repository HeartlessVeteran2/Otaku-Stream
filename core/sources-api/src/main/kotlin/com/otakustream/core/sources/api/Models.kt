package com.otakustream.core.sources.api

data class MediaItem(
    val url: String,
    val title: String,
    val coverUrl: String? = null,
)

data class MediaDetails(
    val media: MediaItem,
    val description: String?,
    val genres: List<String> = emptyList(),
    val status: MediaStatus = MediaStatus.UNKNOWN,
    val backgroundUrl: String? = null,
    val logoUrl: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val trailerYoutubeId: String? = null,
)

enum class MediaStatus { ONGOING, COMPLETED, UNKNOWN }

data class Episode(
    val url: String,
    val name: String,
    val episodeNumber: Float,
    val dateUploadEpochMs: Long = 0L,
    val season: Int? = null,
)

data class Video(
    val url: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val isM3U8: Boolean = false,
)

data class SubtitleTrack(
    val url: String,
    val lang: String,
    val label: String,
)

data class CatalogPage(
    val items: List<MediaItem>,
    val hasNextPage: Boolean,
)

data class SourceFilter(
    val name: String,
    val values: List<String>,
    val selected: Int = 0,
)
