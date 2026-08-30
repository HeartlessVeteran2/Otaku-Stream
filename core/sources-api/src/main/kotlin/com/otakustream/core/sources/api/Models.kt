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
    // The source's own free-form second line about this stream, when it has one: Stremio add-ons
    // put the release filename, size, seeder count and indexer here. Carried verbatim rather than
    // parsed at the source, because what a given add-on writes into it is convention, not protocol
    // — the parsing belongs somewhere it can be changed and tested without touching every source.
    //
    // Discarded entirely before pooling existed, which is why the stream picker could only ever
    // show a quality string: everything that distinguishes one 1080p release from another was in
    // here and thrown away.
    val description: String? = null,
    // Tracker announce URLs, for a `torrent://` url. Carried alongside the url rather than inside it
    // on purpose: the tracker list varies between responses for the same torrent, so folding it into
    // the url would make the url unstable — and the url is what resume position, skip markers, and
    // watch history are all keyed on. Like `headers`, this reaches the player through
    // PendingPlayback as per-video data the url itself doesn't carry.
    val trackers: List<String> = emptyList(),
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
