package com.otakustream.feature.sources

import com.otakustream.core.database.tracking.TrackingRepository
import com.otakustream.core.database.tracking.toTrackerSeason
import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.MediaItem
import com.otakustream.core.sources.api.VideoSource

// Another installed source that holds the same show, and the url it knows the show by.
//
// The url is not incidental: a source can only resolve streams for an Episode it produced itself,
// so reaching a peer's streams means asking the peer for its own episode list first.
data class PeerSource(val source: VideoSource, val mediaUrl: String)

// Which other installed sources are linked to the same AniList entry as this title.
//
// This is the join that lets four add-ons behave like one library. Linking a title records
// (mediaUrl, sourceId) against an AniList media id, so several sources linked to the same id are —
// by the user's own assertion — the same show. That assertion is the only identity worth trusting
// here: titles differ between sources, romanised differently, with and without "Season 2", so
// matching on them would confidently pool the wrong show.
//
// Season-aware, resolved against the *episode's* season rather than whatever is selected on screen,
// matching how watch progress is pushed (TrackingManager.onEpisodeWatched) — auto-play can carry
// playback out of the season the user was looking at, and the pool has to follow it.
//
// Top-level rather than a ViewModel method because auto-play needs it too, and the auto-play
// resolver deliberately captures nothing but singletons and primitives: it outlives the screen.
suspend fun findPeerSources(
    sources: SourceRepository,
    tracking: TrackingRepository,
    mediaUrl: String,
    primarySourceId: Long,
    season: Int?,
): List<PeerSource> {
    val link = tracking.getLink(mediaUrl, season.toTrackerSeason()) ?: return emptyList()
    return tracking.getLinksByTrackerId(link.trackerMediaId)
        .filter { it.mediaUrl != mediaUrl }
        // sourceId 0 means "unknown" — links made before the column existed, or through the manual
        // link dialog. There is no source to ask, and guessing one from the url would ask a source
        // a question about a url it has never seen.
        .filter { it.sourceId != 0L }
        .distinctBy { it.mediaUrl }
        .mapNotNull { peer ->
            sources.getSource(peer.sourceId)
                ?.takeIf { it.id != primarySourceId }
                ?.let { PeerSource(it, peer.mediaUrl) }
        }
        // Two links can point at the same source with different urls (the show listed twice in one
        // add-on). Asking that source twice is two round trips for one answer.
        .distinctBy { it.source.id }
}

// A cost this deduplication does not cover, recorded rather than left to be rediscovered:
// StremioVideoSource.getVideoList already fans out to every installed *stream* provider (Torrentio
// and the like), and it does so per catalog add-on. So a show linked from two different Stremio
// catalog add-ons asks Torrentio the same question twice. The pooled list is still correct —
// duplicate streams share a url and are deduplicated — but it is two requests for one answer, and
// with three such links, three.
//
// Not fixed here. The fix is a short-lived in-flight cache on the /stream url inside
// StremioStreamProviderRegistry, which is the one object all those sources share; adding a caching
// layer to the path that resolves playback, with no device to watch it on, is the more expensive
// mistake. It only bites when the same show is deliberately linked from two Stremio catalog
// add-ons, which is not what pooling is mainly for — a Stremio add-on plus a Mangayomi extension
// share no providers at all.

// One source's streams for an episode, tagged with where they came from and what can be read off
// the text the source wrote about them.
suspend fun streamOptionsFrom(source: VideoSource, episode: Episode): List<StreamOption> =
    source.getVideoList(episode).map { video ->
        StreamOption(
            video = video,
            sourceId = source.id,
            sourceName = source.name,
            metadata = parseStreamMetadata(video.quality, video.description),
        )
    }

// The peer's own entry for the episode being played, or null when its list has no such episode.
//
// `mediaTitle` is only what to call the MediaItem while asking; sources resolve by url. It is
// passed rather than left blank because a scripted extension is free to log or display it.
suspend fun peerEpisodeFor(peer: PeerSource, mediaTitle: String, target: Episode): Episode? =
    peer.source
        .getEpisodeList(MediaItem(url = peer.mediaUrl, title = mediaTitle))
        .matchingEpisode(target)
