package com.otakustream.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks

data class TrackInfo(
    val group: TrackGroup,
    val trackIndexInGroup: Int,
    val label: String,
    val isSelected: Boolean,
)

internal fun Tracks.toTrackInfoList(trackType: Int): List<TrackInfo> =
    groups.filter { it.type == trackType }.flatMap { group ->
        (0 until group.length).map { index ->
            TrackInfo(
                group = group.mediaTrackGroup,
                trackIndexInGroup = index,
                label = group.getTrackFormat(index).describe(trackType, index),
                isSelected = group.isTrackSelected(index),
            )
        }
    }

private fun Format.describe(trackType: Int, index: Int): String = when (trackType) {
    C.TRACK_TYPE_VIDEO -> if (height > 0) "${height}p" else label ?: "Track ${index + 1}"
    else -> label ?: language ?: "Track ${index + 1}"
}
