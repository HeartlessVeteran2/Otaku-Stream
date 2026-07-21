package com.otakustream.core.sources.mangayomi.runtime

// Unpacks Dean Edwards' p.a.c.k.e.r output (`eval(function(p,a,c,k,e,d){...}('...',N,N,'...'.split('|')))`).
// The vast majority of video-host extractors (Filemoon, StreamWish, VidHide, DoodStream, …) obfuscate
// their player config this way, so a working unpacker is the single most reused extractor primitive.
// Exposed to JS as unpackJs(); returns the input unchanged when it isn't packed.
internal object JsUnpacker {

    private val DETECT = Regex("""eval\(function\(p,a,c,k,e,[dr]""")
    private val ARGS = Regex(
        """\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val TOKEN = Regex("""\b\w+\b""")
    private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun unpack(source: String): String {
        if (!DETECT.containsMatchIn(source)) return source
        val match = ARGS.find(source) ?: return source
        val payload = match.groupValues[1]
            .replace("\\'", "'")
            .replace("\\\\", "\\")
        val radix = match.groupValues[2].toIntOrNull() ?: return source
        val words = match.groupValues[4].split("|")
        return TOKEN.replace(payload) { token ->
            val index = unbase(token.value, radix)
            words.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: token.value
        }
    }

    // p.a.c.k.e.r's base conversion: radix <= 36 is plain parseInt; higher radices use the
    // 0-9a-zA-Z alphabet (base62), which is what the packer emits for large dictionaries.
    private fun unbase(token: String, radix: Int): Int {
        if (radix <= 36) return token.toIntOrNull(radix) ?: -1
        var value = 0
        for (char in token) {
            val digit = BASE62.indexOf(char)
            if (digit < 0 || digit >= radix) return -1
            value = value * radix + digit
        }
        return value
    }
}
