package com.otakustream.feature.library.local

import com.otakustream.core.sources.api.SubtitleTrack
import java.io.File

private val SIDECAR_EXTENSIONS = listOf("srt", "ass", "ssa", "vtt")

// Best-effort VLC-style sidecar detection for library items with a real filesystem path:
// "Show.mkv" picks up "Show.srt" (etc.) sitting next to it. Scoped storage on API 29+ can make
// non-media files unreadable by path — the canRead() guard makes this silently best-effort, and
// the player's manual "Load subtitle file" picker remains the guaranteed route.
internal fun findSidecarSubtitles(dataPath: String?): List<SubtitleTrack> {
    if (dataPath.isNullOrBlank()) return emptyList()
    val video = File(dataPath)
    val baseName = video.name.substringBeforeLast('.', missingDelimiterValue = "")
    if (baseName.isEmpty()) return emptyList()
    val parent = video.parentFile ?: return emptyList()
    return SIDECAR_EXTENSIONS.mapNotNull { extension ->
        val candidate = File(parent, "$baseName.$extension")
        val readable = runCatching { candidate.exists() && candidate.canRead() }.getOrDefault(false)
        if (readable) {
            SubtitleTrack(url = candidate.toURI().toString(), lang = "", label = candidate.name)
        } else {
            null
        }
    }
}
