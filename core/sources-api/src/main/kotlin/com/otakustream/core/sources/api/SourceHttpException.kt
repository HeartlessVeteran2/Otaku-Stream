package com.otakustream.core.sources.api

import java.io.IOException

// A source host answered, but with a non-2xx status.
//
// Exists so a failure can be *classified* rather than described. Before this, every HTTP failure
// left a source as `require(response.isSuccessful) { "HTTP ${response.code}" }` — an
// IllegalArgumentException whose only record of the status code was English prose inside its
// message. The catalog fan-out then reduced the whole thing to a boolean, so a 403 (this host is
// blocking us), a 404 (this add-on's endpoint moved) and a 503 (it is down right now) all reached
// the user as the same "1 source couldn't load". Recovering the code by parsing the message back
// out would be guessing at our own output; carrying it is free.
//
// IOException rather than IllegalStateException because a remote host refusing a request is not a
// bug in the caller — and because callers already treat IOException as the recoverable,
// worth-retrying case.
class SourceHttpException(
    val code: Int,
    val url: String? = null,
) : IOException(if (url.isNullOrBlank()) "HTTP $code" else "HTTP $code from $url")
