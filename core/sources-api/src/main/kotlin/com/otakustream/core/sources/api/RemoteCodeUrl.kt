package com.otakustream.core.sources.api

// Where the app draws the line on how *executable* code may arrive.
//
// The app permits cleartext HTTP app-wide, deliberately, because anime mirrors and self-hosted
// add-ons frequently have no TLS and blocking them made the app look broken. That tradeoff is
// documented in SECURITY.md and it is the right one for *content*: the cost of a tampered episode
// list is a wrong episode list.
//
// It is not the right tradeoff for the JavaScript the app then runs in its own process. Over
// cleartext, anyone on the path — a café router, an ISP, whoever runs the exit hop — can replace a
// script's body in flight, and the app will evaluate whatever comes back. Sandboxed or not, that
// script holds the source's HTTP capability and speaks for a source the user chose to trust.
//
// A repo index is worse than a single script, because it is indirection: it hands back the
// `sourceCodeUrl` for every extension in it. Rewriting one cleartext index redirects every
// subsequent install, including installs of extensions the user picked by name from a list they had
// no reason to distrust.
//
// So: content may be cleartext, code may not.
object RemoteCodeUrl {

    // Loopback is exempt. A developer serving a script from `python -m http.server` on the machine
    // the emulator runs on has no network path to tamper with, and requiring TLS there would mean
    // the documented way to develop a source is the one way that doesn't work. Matched on the host
    // alone, so it cannot be spoofed by a userinfo prefix (`http://127.0.0.1@evil.test/x.js` has
    // host `evil.test`, and java.net.URI parses it that way).
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")

    // 10.0.2.2 is the Android emulator's alias for the host machine's loopback — the address the
    // emulator must use to reach a server running on the developer's own machine.

    fun isAllowed(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme == "https") return true
        if (scheme != "http") return false
        // http is only ever acceptable when it cannot leave the machine.
        return parsed.host?.lowercase() in LOOPBACK_HOSTS
    }

    // The message a user actually sees when an install is refused. Names the fix ("use https")
    // rather than the rule, because "scheme not permitted" tells someone pasting a link nothing
    // about what to do next.
    fun rejectionMessage(what: String): String =
        "$what must be served over https. Plain http can be modified in transit, and this is code " +
            "the app runs — so it is not accepted from an untrusted network."

    // Throws unless the URL is a safe origin to fetch executable code from. `what` names the thing
    // being installed, so the failure reads as a sentence at whichever screen surfaces it.
    fun require(url: String, what: String) {
        if (!isAllowed(url)) throw java.io.IOException(rejectionMessage(what))
    }
}
