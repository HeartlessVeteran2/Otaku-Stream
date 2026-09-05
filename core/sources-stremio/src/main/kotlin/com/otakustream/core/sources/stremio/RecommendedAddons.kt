package com.otakustream.core.sources.stremio

import com.otakustream.core.sources.stremio.model.AddonListOrigin
import com.otakustream.core.sources.stremio.model.OfficialAddonListing

// The add-ons that actually play anime, which no list the app fetches will ever contain.
//
// Worth being precise about the problem this solves, because the directory did not look broken.
// Stremio's official index is 7 add-ons and its community collection is ~95, and the app already
// showed all of them — the same list, from the same endpoints, that Stremio's own app shows. What
// none of those 102 entries is, is a source of video. They are subtitles, catalogs, ratings, watch
// calendars, jump-scare warnings. Stremio's collection carries no torrent or stream add-ons, so
// browsing the directory end to end could not get you anything that resolves an episode.
//
// In the real Stremio that gap is filled by knowing a URL: people paste Torrentio or Comet in from a
// forum post or an add-on guide. This list is that knowledge, written down, reviewable in the diff,
// and one tap to install.
//
// Deliberately short. A directory of everything is what the app already had and it did not help;
// this is the set worth having installed for anime, and each entry says what it is for.
internal object RecommendedAddons {

    // Ordered as it should be read: the things that resolve streams first, then the anime metadata
    // that makes those streams findable by the right ids.
    val listings: List<OfficialAddonListing> = listOf(
        recommended(
            name = "Torrentio",
            description = "The most widely used stream add-on. Pulls torrents from anime trackers " +
                "(Nyaa, AnimeTosho) and general ones, and works as-is; configure it to add a debrid " +
                "service or narrow which trackers it searches.",
            transportUrl = "https://torrentio.strem.fun/manifest.json",
            types = listOf("movie", "series", "anime"),
            resources = listOf("stream"),
            isConfigurable = true,
        ),
        recommended(
            name = "Comet",
            description = "Stream add-on with strong anime coverage and Kitsu id support, so it " +
                "answers for titles Cinemeta has no IMDb entry for. Works unconfigured.",
            transportUrl = "https://comet.elfhosted.com/manifest.json",
            types = listOf("movie", "series", "anime", "other"),
            resources = listOf("stream"),
            isConfigurable = true,
        ),
        recommended(
            name = "MediaFusion",
            description = "Streams and catalogs in one, including dedicated anime catalogs. " +
                "Understands MyAnimeList ids alongside IMDb and TMDB.",
            transportUrl = "https://mediafusion.elfhosted.com/manifest.json",
            types = listOf("movie", "series", "tv", "events"),
            resources = listOf("catalog", "stream", "meta"),
            isConfigurable = true,
        ),
        recommended(
            name = "StremThru Torz",
            description = "Stream add-on backed by a shared torrent index. A useful second opinion " +
                "when the others come back empty.",
            transportUrl = "https://stremthru.elfhosted.com/stremio/torz/manifest.json",
            resources = listOf("stream"),
            isConfigurable = true,
        ),
        recommended(
            name = "Jackettio",
            description = "Streams from your own Jackett or Prowlarr indexers. Needs configuring " +
                "with your instance before it returns anything.",
            transportUrl = "https://jackettio.elfhosted.com/manifest.json",
            types = listOf("movie", "series"),
            resources = listOf("stream"),
            isConfigurable = true,
            configurationRequired = true,
        ),
        recommended(
            name = "AIOStreams",
            description = "Queries several stream add-ons at once and merges the results. Serves " +
                "nothing at all until you configure which add-ons it should use.",
            transportUrl = "https://aiostreams.elfhosted.com/manifest.json",
            // Genuinely empty until configured — its unconfigured manifest declares no resources and
            // no types, which is why it is marked required rather than merely configurable.
            resources = emptyList(),
            isConfigurable = true,
            configurationRequired = true,
        ),
        recommended(
            name = "Anime Kitsu",
            description = "Anime catalogs and metadata from Kitsu, and the add-on that gives titles " +
                "their kitsu: ids — which is what the stream add-ons above match anime on. Install " +
                "this if you install nothing else here.",
            transportUrl = "https://anime-kitsu.strem.fun/manifest.json",
            types = listOf("anime", "movie", "series"),
            resources = listOf("catalog", "meta"),
        ),
        recommended(
            name = "Anime Catalogs",
            description = "Seasonal, airing and top-rated anime catalogs to browse, sourced from " +
                "MyAnimeList, AniList and Kitsu.",
            transportUrl = "https://1fe84bc728af-stremio-anime-catalogs.baby-beamup.club/manifest.json",
            types = listOf("anime", "movie", "series"),
            resources = listOf("catalog", "meta"),
        ),
        recommended(
            name = "Torrent Catalogs",
            description = "Browse by what is currently well-seeded rather than by what is popular. " +
                "Pairs with a stream add-on above.",
            transportUrl = "https://torrent-catalogs.strem.fun/manifest.json",
            types = listOf("movie", "series"),
            resources = listOf("catalog", "meta"),
        ),
    )

    // The manifest URLs above, normalized, so the directory can drop the duplicates that appear in
    // the fetched lists rather than showing an add-on twice under two different headings.
    val transportUrls: Set<String> = listings.map { normalizeStremioManifestUrl(it.transportUrl) }.toSet()

    private fun recommended(
        name: String,
        description: String,
        transportUrl: String,
        types: List<String> = emptyList(),
        resources: List<String> = emptyList(),
        isConfigurable: Boolean = false,
        configurationRequired: Boolean = false,
    ) = OfficialAddonListing(
        name = name,
        description = description,
        // No logo. These are hand-written entries and a logo URL would be one more thing to go
        // stale; the add-on's real logo appears once it is installed and its manifest is read.
        logoUrl = null,
        transportUrl = transportUrl,
        types = types,
        origin = AddonListOrigin.RECOMMENDED,
        resources = resources,
        isConfigurable = isConfigurable,
        configurationRequired = configurationRequired,
    )
}
