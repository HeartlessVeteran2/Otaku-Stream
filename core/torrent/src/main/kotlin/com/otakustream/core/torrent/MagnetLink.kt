package com.otakustream.core.torrent

import java.net.URLDecoder

// What a magnet link carries that we can use.
//
// Deliberately only these three fields. A magnet can also carry `xl` (length), `ws` (web seeds) and
// a pile of other parameters, none of which the reader needs: the torrent's own metadata is
// authoritative for everything except how to find peers, which is what `trackers` is for.
data class MagnetLink(
    val infoHash: String,
    val trackers: List<String>,
    val displayName: String?,
)

// Parses `magnet:?xt=urn:btih:…` links, which is how a torrent is shared everywhere outside
// Stremio's own stream protocol — so this is what makes "Open with" from a browser or a torrent
// index work.
//
// Pure string handling, no android.net.Uri: magnets from the wild are frequently malformed, and this
// needs to be exhaustively testable on the JVM. Uri.parse is also lenient in ways that would let a
// broken link through as a half-populated object.
object MagnetLinks {
    private const val SCHEME_PREFIX = "magnet:"
    private const val BTIH_PREFIX = "urn:btih:"

    // RFC 4648 base32. Some indexes still emit base32 info-hashes, which are 32 characters for the
    // same 160 bits a 40-character hex hash holds. Both must normalize to the same torrent:// url or
    // the two spellings become two separate resume positions.
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BASE32_HASH_LENGTH = 32
    private const val HEX_HASH_LENGTH = 40

    fun isMagnet(url: String): Boolean = url.startsWith(SCHEME_PREFIX, ignoreCase = true)

    // Returns null for anything that isn't a usable v1 magnet. Callers treat that as "not something
    // we can play" rather than an error, because these strings arrive from other apps.
    fun parse(url: String): MagnetLink? {
        if (!isMagnet(url)) return null
        val query = url.substring(SCHEME_PREFIX.length).removePrefix("?")
        if (query.isEmpty()) return null

        var infoHash: String? = null
        // A set, and capped as it fills: `distinct().take(n)` afterwards still builds the whole
        // list first, so a magnet with thousands of `tr` parameters does all that work before the
        // bound applies. LinkedHashSet keeps announce order, which trackers are listed in for a
        // reason.
        val trackers = LinkedHashSet<String>()
        var displayName: String? = null

        query.split('&').forEach { pair ->
            // A parameter with no '=' (or an empty value) is malformed; skip it rather than letting
            // it become a blank tracker url that libtorrent would then try to contact.
            val separator = pair.indexOf('=')
            if (separator <= 0 || separator == pair.length - 1) return@forEach
            val key = pair.substring(0, separator).lowercase()
            val value = decode(pair.substring(separator + 1)) ?: return@forEach

            when (key) {
                // xt can appear more than once (a v1+v2 hybrid magnet carries both btih and btmh).
                // Take the first btih and ignore the rest: v2 hashes are not supported, and picking
                // one up here would build a url that parses but can never resolve.
                "xt" -> if (infoHash == null) infoHash = normalizeHash(value)
                "tr" -> if (trackers.size < MAX_TRACKERS) trackers += value
                "dn" -> displayName = value.ifBlank { null }
            }
        }

        val hash = infoHash ?: return null
        return MagnetLink(
            infoHash = hash,
            // Bounded during the scan above rather than at each call site, because every one of
            // them feeds a link that arrived from outside the app — pasted, or shared in by another
            // app. A crafted magnet can carry thousands of `tr` parameters, and each one becomes a
            // host the session announces to and a string persisted with the torrent.
            trackers = trackers.toList(),
            displayName = displayName?.let(::sanitizeDisplayName),
        )
    }

    // `dn` is attacker-supplied text that ends up in watch history and on screen. Control characters
    // (including the newlines that would break a single-line row) are dropped, runs of whitespace
    // collapsed, and the result bounded — a megabyte-long name is not a title, it is a payload.
    private fun sanitizeDisplayName(raw: String): String? = raw
        .filter { it == ' ' || !it.isISOControl() }
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .take(MAX_DISPLAY_NAME_LENGTH)
        // take() counts UTF-16 code units, so a cut can land between the halves of a surrogate pair
        // and leave a lone high surrogate — malformed text for both the UI and the database. Drop it.
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .ifBlank { null }

    // Magnet → the app's own stable playback identity. fileIdx 0 because a magnet says nothing about
    // which file inside the torrent is meant; for the single-file torrents these links overwhelmingly
    // point at, that is also the right answer.
    fun toTorrentUrl(link: MagnetLink): String? = TorrentUri.build(link.infoHash, 0)

    private fun decode(value: String): String? =
        // A stray '%' that isn't a valid escape makes URLDecoder throw. That's a malformed
        // parameter, not a crash worth propagating to whichever app sent us the link.
        //
        // '+' is escaped before decoding, not after. URLDecoder implements
        // application/x-www-form-urlencoded, where '+' means space — but a magnet is an RFC 3986 URI,
        // where it is a literal character. Without this, a tracker url with a '+' in its announce path
        // or passkey silently gains a space and every announce to it fails.
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun normalizeHash(urn: String): String? {
        if (!urn.startsWith(BTIH_PREFIX, ignoreCase = true)) return null
        val raw = urn.substring(BTIH_PREFIX.length).trim()
        return when (raw.length) {
            // Shared with the torrent:// scheme deliberately: one definition of a valid v1 hash, so
            // the two entry points can't drift into disagreeing.
            HEX_HASH_LENGTH -> TorrentUri.normalizeInfoHash(raw)
            BASE32_HASH_LENGTH -> base32ToHex(raw)
            else -> null
        }
    }

    private fun base32ToHex(value: String): String? {
        val upper = value.uppercase()
        var buffer = 0L
        var bits = 0
        val out = StringBuilder(HEX_HASH_LENGTH)
        upper.forEach { char ->
            val index = BASE32_ALPHABET.indexOf(char)
            if (index < 0) return null
            buffer = (buffer shl 5) or index.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                val byte = ((buffer shr bits) and 0xFF).toInt()
                out.append("%02x".format(byte))
            }
        }
        // 32 base32 characters carry exactly 160 bits, so a correct hash leaves nothing over.
        return out.toString().takeIf { it.length == HEX_HASH_LENGTH }
    }

    // Generous next to what a real magnet carries (a handful to a few dozen) and small enough that a
    // crafted one cannot turn a single paste into a swarm of announce targets.
    private const val MAX_TRACKERS = 64

    // Long enough for a full release name, short enough that history rows stay rows.
    private const val MAX_DISPLAY_NAME_LENGTH = 200

    // \s in Java regex is ASCII-only, so it misses the separators that actually break a single-line
    // row: U+2028 LINE SEPARATOR, U+2029 PARAGRAPH SEPARATOR, and the non-breaking spaces in
    // \p{Z}. None of those are ISO controls either, so the filter above does not catch them.
    private val WHITESPACE_RUN = Regex("[\\s\\p{Z}\\u2028\\u2029]+")
}
