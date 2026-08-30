package com.otakustream.core.database.download

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// What a downloaded episode is *called*, and what it belongs to.
//
// Media3's own download index already tracks state, progress and bytes, and this table deliberately
// duplicates none of that — two records of the same fact drift, and Media3's is authoritative
// because it is the one the downloader writes. What Media3 does not carry is anything a person
// would recognise: a DownloadRequest is a url and some bytes. Without this, the Downloads list
// could only show urls.
//
// videoUrl is the primary key and is the same url watch history, resume position and the download
// request all key on, so a downloaded episode is one entity across the whole app rather than a
// parallel one that happens to look similar.
@Entity(
    tableName = "downloads",
    indices = [Index("requestedAtEpochMs"), Index("mediaUrl"), Index("episodeUrl")],
)
data class DownloadEntry(
    // The resolved stream url. This is what was actually fetched, and what Media3's download index
    // is keyed on, so it is the join back to progress and state.
    @PrimaryKey val videoUrl: String,
    // The show, not the episode — what the Downloads list groups under.
    val mediaUrl: String,
    // The episode's own url, which is what an episode row in the details screen holds. Without it a
    // row could not tell whether its episode is downloaded: the stream url is only discovered by
    // resolving the episode, which is exactly the network round trip the row is trying to avoid.
    val episodeUrl: String,
    val sourceId: Long,
    val mediaTitle: String,
    val episodeName: String?,
    // Nullable and Float to match Episode.episodeNumber, which carries .5 for specials.
    val episodeNumber: Float?,
    val coverUrl: String?,
    val requestedAtEpochMs: Long,
    // The two things the player is handed per-video that the download used to drop on the floor.
    //
    // PlayerController builds a fresh data source factory for every playback precisely because
    // headers are per-video — a Referer or an auth header the host requires. The downloader ran off
    // one shared factory with none of them, so any source that needs them would 403, and the failure
    // would look like the source being broken.
    //
    // isM3U8 matters for a different reason: plenty of extension-resolved HLS urls carry no .m3u8
    // extension, and without the hint Media3 treats one as a progressive file. That does not error
    // — it downloads the playlist *text*, a few kilobytes, and reports success. "Saved", with
    // nothing playable behind it, is the worst shape this feature could fail in.
    val headersJson: String?,
    val isM3U8: Boolean = false,
)
