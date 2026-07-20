package com.otakustream.core.player

enum class SkipKind { INTRO, OUTRO, RECAP }

// The player's own skip-segment model, unifying manually-marked segments (INTRO/OUTRO from the
// database) and AniSkip-fetched ones (which also include RECAP). Kept separate from the database
// SkipSegment so adding RECAP needs no schema change.
data class PlayerSkipSegment(val startMs: Long, val endMs: Long, val kind: SkipKind) {
    val label: String
        get() = when (kind) {
            SkipKind.INTRO -> "Skip intro"
            SkipKind.OUTRO -> "Skip outro"
            SkipKind.RECAP -> "Skip recap"
        }

    fun overlaps(other: PlayerSkipSegment): Boolean = startMs < other.endMs && other.startMs < endMs
}
