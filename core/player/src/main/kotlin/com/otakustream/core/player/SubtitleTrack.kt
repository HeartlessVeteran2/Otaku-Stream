package com.otakustream.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

data class SubtitleTrack(
    val url: String,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String = guessSubtitleMimeType(url),
)

private fun guessSubtitleMimeType(url: String): String = when {
    url.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
    else -> MimeTypes.TEXT_VTT
}

internal fun SubtitleTrack.toMedia3Config(): MediaItem.SubtitleConfiguration =
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
        .setMimeType(mimeType)
        .setLanguage(language)
        .setLabel(label)
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()

internal fun com.otakustream.core.sources.api.SubtitleTrack.toPlayerTrack(): SubtitleTrack =
    SubtitleTrack(url = url, language = lang, label = label)
