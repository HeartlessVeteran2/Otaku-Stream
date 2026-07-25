package com.otakustream.core.database.tracking

import androidx.room.Entity
import androidx.room.PrimaryKey

// "Whole series", and the fallback every lookup lands on. AniList models each season of a show as a
// separate media entry, so a multi-season series can carry one link per season — but a single-season
// show, a non-Stremio source with no season data, and every link created before seasons existed all
// use this sentinel. Resolution prefers an exact season and falls back here, which is what keeps
// season-awareness additive rather than a behaviour change for everything that came before.
const val TRACKER_SEASON_WHOLE_SERIES = 0

// Maps a source's season number onto the tracker's season key. Sources without season data
// (scripted sources, local files, single-season Stremio metas) report null, and Stremio uses season 0
// for specials — neither is "season 0, the season", so both resolve through the whole-series link.
fun Int?.toTrackerSeason(): Int = this?.takeIf { it > 0 } ?: TRACKER_SEASON_WHOLE_SERIES

// Maps a local media item (optionally one season of it) to the corresponding entry on an external
// tracker (AniList media id). sourceId records which installed source the mediaUrl belongs to, so
// the AniList detail can reopen a previously-chosen source directly (0 = unknown, e.g. links created
// before this column existed or via the manual link dialog, which just re-search on the AniList side).
@Entity(tableName = "tracker_links", primaryKeys = ["mediaUrl", "season"])
data class TrackerLink(
    val mediaUrl: String,
    val trackerMediaId: Long,
    val trackerTitle: String,
    val sourceId: Long = 0,
    val season: Int = TRACKER_SEASON_WHOLE_SERIES,
)

// Single-row table holding the user's pasted tracker access token (id is always 0).
@Entity(tableName = "tracker_tokens")
data class TrackerToken(
    @PrimaryKey val id: Int = 0,
    val accessToken: String,
)
