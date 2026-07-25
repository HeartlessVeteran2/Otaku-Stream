package com.otakustream.core.sources.stremio.account

// Pure mapping/validation for pushing local saves to a Stremio account, split out so it can be
// unit-tested. The guard here is what stops "Push my saves" from writing junk rows into the user's
// real Stremio library: only genuine Stremio catalog entries ("type|id" with a valid type and a
// Stremio-shaped id) are eligible — a scripted/Mangayomi save (a real URL) or a malformed key is
// skipped.

// Content types Stremio recognises for a library item.
private val STREMIO_TYPES = setOf("movie", "series", "channel", "tv", "anime", "other")

// Maps a local library key + metadata to a Stremio library item, or null when it isn't a real
// Stremio catalog entry.
fun stremioLibraryItemFor(mediaUrl: String, title: String, coverUrl: String?): StremioLibraryItem? {
    if (mediaUrl.contains("://") || !mediaUrl.contains("|")) return null
    val type = mediaUrl.substringBefore("|").trim().lowercase()
    val id = mediaUrl.substringAfter("|").trim()
    if (type !in STREMIO_TYPES || !isStremioMetaId(id)) return null
    return StremioLibraryItem(id = id, type = type, name = title, poster = coverUrl, removed = false)
}

// Stremio meta ids are IMDb ids ("tt" + digits) or addon-namespaced ids ("prefix:rest",
// e.g. "kitsu:1234", "mal:5678").
fun isStremioMetaId(id: String): Boolean {
    if (id.isBlank()) return false
    if (id.length > 2 && id.startsWith("tt") && id.drop(2).all { it.isDigit() }) return true
    val colon = id.indexOf(':')
    return colon in 1 until id.length - 1
}
