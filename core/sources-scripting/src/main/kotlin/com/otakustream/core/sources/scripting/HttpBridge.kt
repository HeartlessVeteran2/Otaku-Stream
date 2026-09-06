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
@Singleton
class HttpBridge @Inject constructor(
    httpClient: OkHttpClient,
) {
    // The shared client with a shorter leash, sharing its connection pool and dispatcher.
    //
    // This call is made from inside a script, which runs inside the mutex every entry point of
    // ScriptedVideoSource holds — and it is blocking, so neither coroutine cancellation nor the
    // interpreter's own deadline can interrupt it (the deadline is checked between instructions,
    // and no instructions run while a socket is waiting). At the app-wide 60-second call timeout,
    // one unresponsive host therefore held that source's lock for a minute per call. Twenty
    // seconds is long enough for a slow page and short enough that a dead host is an annoyance
    // rather than an outage.
    private val scriptClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(SCRIPT_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun httpGet(url: String, headersJson: String?): String {
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

// Bounds how long a single in-script fetch can hold its source's lock.
private const val SCRIPT_CALL_TIMEOUT_SECONDS = 20L
