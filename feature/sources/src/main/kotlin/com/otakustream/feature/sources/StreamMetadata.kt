package com.otakustream.feature.sources

import com.otakustream.core.sources.api.Episode
import com.otakustream.core.sources.api.Video
import kotlin.math.abs

// What is actually known about one stream, pulled out of the free-form text a source hands back.
//
// Every field is nullable and every one of them is routinely absent: a Mangayomi extension reports
// nothing but "1080p", while a torrent indexer writes a filename, a size, a seeder count and its own
// name into one string with emoji separators. The picker has to render both without pretending it
// knows more than it does, so "unknown" is a first-class value here rather than a zero.
data class StreamMetadata(
    // Vertical resolution in pixels — 1080, 720, 2160. The single most useful sort key, and the one
    // users actually mean by "quality".
    val resolution: Int? = null,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    // The indexer or release group behind this stream ("Nyaa", "SubsPlease"). Distinct from the
    // source name: one Stremio add-on fans out to a dozen indexers, and which one a release came
    // from is often the difference between two otherwise identical 1080p entries.
    val releaseGroup: String? = null,
)

// One entry in the pooled stream picker: the playable stream, which source produced it, and what is
// known about it.
//
// sourceName is stamped on by the aggregator rather than read off the Video, because a Video does
// not know where it came from — and once streams from four sources are in one list, "which add-on
// is this from" is the first thing the user needs and the one thing no source reports.
data class StreamOption(
    val video: Video,
    val sourceId: Long,
    val sourceName: String,
    val metadata: StreamMetadata,
) {
    // Torrent-backed streams need peers before they play, which is why seeders matter for them and
    // are meaningless for a direct HTTP stream. Derived from the url rather than carried, so it
    // cannot disagree with what the player will actually do with it.
    val isTorrent: Boolean
        get() = video.url.startsWith("torrent://", ignoreCase = true) || video.url.startsWith("magnet:", ignoreCase = true)
}

// 2160p is written half a dozen ways and almost never as "2160p".
private val RESOLUTION_REGEX = Regex("""(?<!\d)(\d{3,4})\s?[pi](?![a-z0-9])""", RegexOption.IGNORE_CASE)
private val UHD_REGEX = Regex("""\b(4k|uhd|2160)\b""", RegexOption.IGNORE_CASE)

// "💾 1.4 GB", "1.4GB", "Size: 700 MiB". Binary and decimal units both appear; both are read as
// binary, since that is what every indexer that bothers to say actually means.
private val SIZE_REGEX = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|KB|TiB|GiB|MiB|KiB)\b""", RegexOption.IGNORE_CASE)

// Torrentio writes "👤 42"; other add-ons write "42 seeders" or "Seeds: 42".
private val SEEDERS_EMOJI_REGEX = Regex("""👤\s*(\d+)""")
private val SEEDERS_WORD_REGEX = Regex("""(?:seeds?|seeders?)\s*[:=]?\s*(\d+)|(\d+)\s*(?:seeds?|seeders?)""", RegexOption.IGNORE_CASE)

// Torrentio writes "⚙️ Nyaa"; fansub releases lead with "[SubsPlease]".
private val GEAR_GROUP_REGEX = Regex("""⚙️?\s*([^\n|]+)""")
private val BRACKET_GROUP_REGEX = Regex("""^\s*\[([^\]]{1,24})\]""")

private const val KIB = 1024.0

// Reads whatever a source chose to say about a stream. Both halves are searched: add-ons put the
// resolution in `name` and the details in `description`, but not consistently, and an add-on that
// puts everything in one field is common enough that splitting the search would lose data.
//
// Pure and total — every branch falls back to null rather than throwing, because this runs over
// text written by third parties whose only contract is that it is a string.
fun parseStreamMetadata(quality: String?, description: String?): StreamMetadata {
    val text = listOfNotNull(quality?.takeIf { it.isNotBlank() }, description?.takeIf { it.isNotBlank() })
        .joinToString("\n")
    if (text.isBlank()) return StreamMetadata()
    return StreamMetadata(
        resolution = parseResolution(text),
        sizeBytes = parseSizeBytes(text),
        seeders = parseSeeders(text),
        releaseGroup = parseReleaseGroup(quality, description),
    )
}

private fun parseResolution(text: String): Int? {
    // The explicit "1080p" form wins over the 4K aliases, because a release titled
    // "4K.Remux.1080p.Downscale" is a 1080p file however it is advertised.
    RESOLUTION_REGEX.findAll(text)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        // 144–4320: anything outside that is a year, a bitrate, or an episode count that happened
        // to be followed by a p.
        .filter { it in 144..4320 }
        .maxOrNull()
        ?.let { return it }
    return if (UHD_REGEX.containsMatchIn(text)) 2160 else null
}

private fun parseSizeBytes(text: String): Long? {
    val match = SIZE_REGEX.find(text) ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    if (value <= 0.0) return null
    val multiplier = when (match.groupValues[2].lowercase().first()) {
        't' -> KIB * KIB * KIB * KIB
        'g' -> KIB * KIB * KIB
        'm' -> KIB * KIB
        else -> KIB
    }
    return (value * multiplier).toLong()
}

private fun parseSeeders(text: String): Int? {
    SEEDERS_EMOJI_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    val worded = SEEDERS_WORD_REGEX.find(text) ?: return null
    // One of the two alternatives matched, so exactly one group is non-empty.
    return worded.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
}

private fun parseReleaseGroup(quality: String?, description: String?): String? {
    val text = listOfNotNull(quality, description).joinToString("\n")
    GEAR_GROUP_REGEX.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 }
        ?.let { return it }
    // Only from the description, and only at the very start: a leading "[SubsPlease]" is the
    // release group by convention, while a bracket anywhere else is usually a tag ("[multi-audio]").
    return description?.let { BRACKET_GROUP_REGEX.find(it)?.groupValues?.get(1)?.trim()?.takeIf { g -> g.isNotEmpty() } }
}

// Best first, for a list the user reads top-down and mostly takes the first entry of.
//
// Resolution leads because it is what "better" means to someone picking a stream, and unknown
// resolution sorts last rather than in the middle — a stream that would not say is more likely to
// be a re-encode than a 4K remux.
//
// Within one resolution, a direct HTTP stream outranks a torrent regardless of seeders. That is not
// a claim it is higher quality; it is that it starts playing without waiting to find peers, and a
// 200-seeder torrent and a direct link at the same resolution are not equivalent choices on a
// phone. Torrents are then ordered by seeders, which is the only real predictor of whether one
// plays at all, and finally by size, where at a fixed resolution bigger means less compressed.
fun List<StreamOption>.sortedBestFirst(): List<StreamOption> = sortedWith(
    compareByDescending<StreamOption> { it.metadata.resolution ?: 0 }
        .thenBy { if (it.isTorrent) 1 else 0 }
        .thenByDescending { it.metadata.seeders ?: -1 }
        .thenByDescending { it.metadata.sizeBytes ?: -1L },
)

// Episode numbers come from parsing text, so two sources can land a hair apart on the same value.
private const val EPISODE_NUMBER_TOLERANCE = 0.001f

// Finds the entry in another source's episode list that is the same episode as `target`.
//
// This is the join that makes pooling possible, and the place where getting it wrong plays the
// wrong episode — so it declines to guess. Season is checked when both sides report one, because
// sources disagree about numbering: Stremio carries an explicit season, a scripted extension whose
// page covers one season only reports none, and a series with absolute numbering has episode 27 in
// season 2. When the number matches in several seasons and none of them is the season asked for,
// there is no answer that is better than "this source has nothing to contribute here".
//
// The case it cannot catch, stated rather than papered over: two sources that *both* report no
// season at all, where one counts from the start of the season and the other from the start of
// the series. Episode 3 of season 2 is episode 3 in the first and episode 15 in the second, and
// nothing in either episode list says so. When either side names a season the disagreement is
// visible and this refuses; when neither does, there is nothing to see. The pooled picker is
// what makes that survivable — the streams carry release names, so a wrong-episode row is
// visible before it is played — but it can happen. Closing it properly means comparing air
// dates or episode titles across sources, which is a larger change than this one.
fun List<Episode>.matchingEpisode(target: Episode): Episode? {
    val sameNumber = filter { abs(it.episodeNumber - target.episodeNumber) < EPISODE_NUMBER_TOLERANCE }
    if (sameNumber.isEmpty()) return null
    if (target.season != null) {
        sameNumber.firstOrNull { it.season == target.season }?.let { return it }
        // A source that reports no season is listing one season's worth of episodes and so cannot
        // contradict the target. Its unqualified entry is the only match it is able to offer, and
        // excluding it would keep every scripted extension out of the pool.
        sameNumber.firstOrNull { it.season == null }?.let { return it }
        // Every candidate names a season and none is the one asked for, so this refuses — including
        // when there is exactly one of them.
        //
        // That single-candidate case used to be taken, on the reasoning that one candidate is not
        // ambiguous: it is a source numbering its one season from 1 where the target numbers from
        // the series. That reasoning is sound and it is also indistinguishable, from here, from a
        // source that is simply showing a different season — which is what a two-cour show split
        // into two seasons by one source and left whole by AniList produces. The two look identical
        // and only one of them is safe, so taking it meant silently playing the wrong episode: the
        // worst thing this feature can do, and the exact outcome the rest of this function is
        // written to avoid.
        //
        // The cost of refusing is one peer contributing nothing to a list that several other
        // sources are still filling, and saying so under it. That is not close.
        return null
    }
    // The target names no season, so nothing here can contradict it: an unqualified entry is the
    // natural match, and a lone candidate is unambiguous.
    return sameNumber.firstOrNull { it.season == null } ?: sameNumber.singleOrNull()
}
