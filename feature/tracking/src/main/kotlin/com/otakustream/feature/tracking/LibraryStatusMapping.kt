package com.otakustream.feature.tracking

import com.otakustream.core.database.library.LIBRARY_STATUS_COMPLETED
import com.otakustream.core.database.library.LIBRARY_STATUS_PLANNED
import com.otakustream.core.database.library.LIBRARY_STATUS_WATCHING

// Pure mapping + decision for mirroring the local Library's watch-status buckets onto AniList.
// The local Library is the single source of truth; when a title is linked to AniList, an explicit
// status change is mirrored up. Extracted from TrackingManager so it's unit-testable without a
// network. AniList's own MediaListStatus vocabulary:
const val ANILIST_STATUS_PLANNING = "PLANNING"
const val ANILIST_STATUS_COMPLETED = "COMPLETED"

// AniList statuses that mean "already finished / rewatching" — never downgraded by a mirror.
private val TERMINAL_STATUSES = setOf(ANILIST_STATUS_COMPLETED, "REPEATING")

// Maps a local Library status bucket to the AniList status it corresponds to, or null for a bucket
// with no AniList equivalent (so the caller skips the write).
fun libraryStatusToAniList(localStatus: String): String? = when (localStatus) {
    LIBRARY_STATUS_PLANNED -> ANILIST_STATUS_PLANNING
    LIBRARY_STATUS_WATCHING -> STATUS_CURRENT
    LIBRARY_STATUS_COMPLETED -> ANILIST_STATUS_COMPLETED
    else -> null
}

// Given the viewer's current AniList status and the status the local Library wants to mirror,
// returns what to write — or null for "leave AniList untouched". Like the progress sync, a
// finished/rewatching entry (COMPLETED/REPEATING) is never downgraded to CURRENT/PLANNING, so
// re-opening a completed title locally can't erase the completion on AniList; a no-op (already the
// desired status) is skipped too.
fun decideStatusMirror(currentAniStatus: String?, desiredAniStatus: String): String? {
    if (currentAniStatus == desiredAniStatus) return null
    if (currentAniStatus in TERMINAL_STATUSES && desiredAniStatus != ANILIST_STATUS_COMPLETED) return null
    return desiredAniStatus
}
