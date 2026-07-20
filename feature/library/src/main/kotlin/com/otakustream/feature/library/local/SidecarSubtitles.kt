package com.otakustream.feature.library.local

import com.otakustream.core.sources.api.SubtitleTrack
import java.io.File

private val SIDECAR_EXTENSIONS = listOf("srt", "ass", "ssa", "vtt")

// Best-effort VLC-style sidecar detection for library items with a real filesystem path:
// "Show.mkv" picks up "Show.srt" and language-coded variants like "Show.en.srt" sitting next
// to it. The "$baseName." prefix requirement keeps "Show2.srt" from matching "Show.mkv".
// Scoped storage on API 29+ can make non-media files unreadable by path — the guards make this
// silently best-effort, and the player's manual "Load subtitle file" picker remains the
// guaranteed route.
internal fun findSidecarSubtitles(dataPath: String?): List<SubtitleTrack> {
    if (dataPath.isNullOrBlank()) return emptyList()
    val video = File(dataPath)
    val baseName = video.name.substringBeforeLast('.', missingDelimiterValue = "")
    if (baseName.isEmpty()) return emptyList()
    val parent = video.parentFile ?: return emptyList()
    val candidates = runCatching {
        parent.listFiles { _, name ->
            name.startsWith("$baseName.", ignoreCase = true) &&
                SIDECAR_EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) }
        }
    }.getOrNull() ?: return emptyList()
    return candidates
        .filter { runCatching { it.canRead() }.getOrDefault(false) }
        .sortedBy { it.name.lowercase() }
        .map { candidate ->
            // "Show.en.srt" → "en"; plain "Show.srt" → "".
            val lang = candidate.name.substringBeforeLast('.').drop(baseName.length).trimStart('.')
            SubtitleTrack(url = candidate.toURI().toString(), lang = lang, label = candidate.name)
        }
}
