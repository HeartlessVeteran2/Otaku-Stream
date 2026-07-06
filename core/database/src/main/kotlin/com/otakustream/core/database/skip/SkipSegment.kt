package com.otakustream.core.database.skip

enum class SkipSegmentType { INTRO, OUTRO }

data class SkipSegment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val type: SkipSegmentType,
)

internal fun SkipSegmentEntity.toDomain() = SkipSegment(
    id = id,
    startMs = startMs,
    endMs = endMs,
    type = SkipSegmentType.valueOf(type),
)
