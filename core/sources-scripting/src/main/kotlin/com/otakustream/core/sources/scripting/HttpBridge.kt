package com.otakustream.core.sources.scripting

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import javax.inject.Inject
import javax.inject.Singleton

// Exposed to scripts as the global `httpGet(url, headersJson?)` function — the only
// capability a script has beyond pure computation, keeping the sandbox surface minimal.
@Singleton
class HttpBridge @Inject constructor(
    private val httpClient: OkHttpClient,
) : BaseFunction() {

    override fun getFunctionName(): String = "httpGet"

    override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
        val url = args?.getOrNull(0) as? String ?: error("httpGet requires a url argument")
        val headersJson = args.getOrNull(1) as? String

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
