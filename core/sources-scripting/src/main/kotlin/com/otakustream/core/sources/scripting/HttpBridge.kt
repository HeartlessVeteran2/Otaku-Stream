package com.otakustream.core.sources.scripting

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// Plain class (not a Rhino BaseFunction) so it can be a shared @Singleton — ScriptEngine wraps
// this in a fresh BaseFunction per script scope instead, since BaseFunction carries Rhino scope
// state that must not be shared across scripts/threads.
@Singleton
class HttpBridge @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    fun httpGet(url: String, headersJson: String?): String {
        val requestBuilder = Request.Builder().url(url)
        if (!headersJson.isNullOrBlank()) {
            val headers = JSONObject(headersJson)
            headers.keys().forEach { key -> requestBuilder.addHeader(key, headers.getString(key)) }
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }
}
