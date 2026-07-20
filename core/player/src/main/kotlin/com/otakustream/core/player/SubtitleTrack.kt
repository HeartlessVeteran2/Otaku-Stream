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
    url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.TEXT_VTT
}

// For user-picked subtitle FILES (matched by display name, not URL): unknown extensions fall
// back to SubRip rather than VTT — local subtitle files are overwhelmingly .srt, while the VTT
// fallback above stays for addon-supplied stream URLs.
internal fun subtitleMimeTypeForName(name: String): String = when {
    name.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    name.endsWith(".ass", ignoreCase = true) || name.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.APPLICATION_SUBRIP
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
