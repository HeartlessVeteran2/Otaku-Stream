package com.otakustream.core.sources.scripting

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// Plain class (not a Rhino BaseFunction) so it can be a shared @Singleton — ScriptEngine wraps
// this in a fresh BaseFunction per script scope instead, since BaseFunction carries Rhino scope
// state that must not be shared across scripts/threads.
// `open` is a test seam and nothing else. The deadline check that stops a script's fetch *loop*
// lives in ScriptEngine, and a test for it needs a fetch that is slow without being a real socket:
// against a fast-failing url the loop spins fast enough that Rhino's instruction observer catches
// it on its own, and the test would pass whether or not the check exists.
@Singleton
open class HttpBridge @Inject constructor(
    httpClient: OkHttpClient,
) {
    // The shared client with a shorter leash, sharing its connection pool and dispatcher.
    //
    // This call is made from inside a script, which runs inside the mutex every entry point of
    // ScriptedVideoSource holds — and it is blocking, so neither coroutine cancellation nor the
    // interpreter's own deadline can interrupt it (the deadline is checked between instructions,
    // and no instructions run while a socket is waiting). At the app-wide 60-second call timeout,
    // one unresponsive host therefore held that source's lock for a minute per call.
    //
    // Two timeouts, not one, because a single call timeout cannot tell the two failures apart.
    //
    // Stage timeouts catch the case that actually happens: a host that accepts the connection and
    // then goes quiet. `readTimeout` measures the gap *between bytes*, so a dead socket gives up in
    // eight seconds however large the page was going to be.
    //
    // The call timeout is the total, and it has to stay under the script deadline: the instruction
    // observer that enforces that deadline only runs between Rhino instructions, and a script
    // sitting inside a synchronous httpGet is executing none — so the request must give up first,
    // return control to the script, and let the observer have its chance.
    //
    // The total was ten seconds and covered the body read as well, which quietly made it a size
    // limit: a page arriving steadily but slowly — a big catalog listing on a weak mobile signal —
    // was aborted mid-transfer and reported as a broken source. Fifteen seconds of *progress* is
    // what it buys now, while a stall still fails at eight.
    private val scriptClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(SCRIPT_STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SCRIPT_STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(SCRIPT_STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(SCRIPT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    open fun httpGet(url: String, headersJson: String?): String {
        val requestBuilder = Request.Builder().url(url)
        if (!headersJson.isNullOrBlank()) {
            val headers = JSONObject(headersJson)
            headers.keys().forEach { key -> requestBuilder.addHeader(key, headers.getString(key)) }
        }

        scriptClient.newCall(requestBuilder.build()).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }
}

// Bounds how long a single in-script fetch can hold its source's lock. Must stay below
// SCRIPT_DEADLINE_MS — see the client above for why.
internal const val SCRIPT_CALL_TIMEOUT_SECONDS = 15L

// How long any one stage may stall: connect, or a gap between response bytes. Well under the total,
// so a host that has stopped responding is recognised as such rather than running out the clock.
internal const val SCRIPT_STAGE_TIMEOUT_SECONDS = 8L
