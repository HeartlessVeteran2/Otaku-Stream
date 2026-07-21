package com.otakustream.core.sources.mangayomi.runtime

import android.util.Log
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors

// One QuickJS engine per installed extension. QuickJS is strictly single-threaded — the wrapper
// enforces that every context call happens on the thread that created it (checkSameThread) — so
// the whole engine is confined to one dedicated executor thread, and all suspend entry points
// hop onto it via `engineDispatcher`. Extensions are `class DefaultExtension extends MProvider`;
// their methods are async, but our host bridges (Client/Document) are synchronous blocking calls,
// so `await`-ing them just yields their value and the async method resolves within a single
// `evaluate` (QuickJS drains the promise job queue before evaluate returns).
class MangayomiRuntime(
    private val extensionSource: String,
    private val httpClient: OkHttpClient,
) : Closeable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mangayomi-js").apply { isDaemon = true }
    }
    private val engineDispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val dom = JsoupBridge()

    private var context: QuickJSContext? = null
    private var started = false

    // Filled by the __om_deliver bridge during an invoke; read back right after evaluate returns.
    private var deliverSet = false
    private var deliverOk = false
    private var deliverValue: String? = null

    // Reads a global set by the extension source, e.g. the `mangayomiSources` metadata array.
    // Returns the JSON string, or null if absent/unset.
    suspend fun readGlobalJson(expression: String): String? = withContext(engineDispatcher) {
        ensureStarted()
        val script = "(function(){try{return JSON.stringify($expression);}catch(e){return null;}})()"
        context!!.evaluate(script) as? String
    }

    // Invokes an extension method (getPopular/search/getDetail/getVideoList/getFilterList/...).
    // Returns the JSON string the method resolved to (may be the literal "null"); throws on a
    // thrown/rejected extension error.
    suspend fun invoke(method: String, args: List<Any?>): String? = withContext(engineDispatcher) {
        ensureStarted()
        val ctx = context!!
        val argsJson = JSONArray()
        args.forEach { argsJson.put(it) }
        deliverSet = false
        deliverOk = false
        deliverValue = null
        // method is a fixed host-chosen identifier (never user input); argsJson is valid JSON and
        // therefore a valid JS array literal, so embedding both directly is safe.
        val script = buildString {
            append(";(function(){try{")
            append("var a=").append(argsJson.toString()).append(";")
            append("var r=globalThis.__om_instance['").append(method).append("'].apply(globalThis.__om_instance,a);")
            append("Promise.resolve(r).then(")
            append("function(v){__om_deliver(true,JSON.stringify(v===undefined?null:v));},")
            append("function(e){__om_deliver(false,''+(e&&e.stack?e.stack:e));});")
            append("}catch(e){__om_deliver(false,''+(e&&e.stack?e.stack:e));}})();")
        }
        try {
            ctx.evaluate(script, "invoke-$method.js")
        } finally {
            dom.clear()
        }
        if (!deliverSet) error("Mangayomi extension method '$method' did not resolve")
        if (!deliverOk) error("Mangayomi extension error in '$method': ${deliverValue.orEmpty()}")
        deliverValue
    }

    private fun ensureStarted() {
        if (started) return
        QuickJSLoader.init()
        val ctx = QuickJSContext.create()
        // If any step of bringup throws (bad extension source, host-API eval error), the native
        // context is already allocated — destroy it before propagating so it doesn't leak.
        try {
            runCatching {
                ctx.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String) { Log.d(TAG, info) }
                    override fun info(info: String) { Log.i(TAG, info) }
                    override fun warn(info: String) { Log.w(TAG, info) }
                    override fun error(info: String) { Log.e(TAG, info) }
                })
            }
            val global = ctx.globalObject
            global.setProperty("__http", JSCallFunction { args -> httpBridge(args) })
            global.setProperty("__html_load", JSCallFunction { args -> dom.load(args.str(0)) })
            global.setProperty("__html_select", JSCallFunction { args -> dom.select(args.int(0), args.str(1)) })
            global.setProperty("__html_selectFirst", JSCallFunction { args -> dom.selectFirst(args.int(0), args.str(1)) })
            global.setProperty("__html_attr", JSCallFunction { args -> dom.attr(args.int(0), args.str(1), args.bool(2)) })
            global.setProperty("__html_text", JSCallFunction { args -> dom.text(args.int(0)) })
            global.setProperty("__html_html", JSCallFunction { args -> dom.html(args.int(0), args.bool(1)) })
            // Preferences bridge — always null until the preferences PR wires per-source storage.
            global.setProperty("__pref_get", JSCallFunction { null })
            global.setProperty("__om_deliver", JSCallFunction { args ->
                deliverOk = args.bool(0)
                deliverValue = args?.getOrNull(1) as? String
                deliverSet = true
                null
            })

            ctx.evaluate(MANGAYOMI_HOST_BOOTSTRAP, "mangayomi-host.js")
            ctx.evaluate(extensionSource, "extension.js")
            // Instantiate the extension and attach its source metadata (Mangayomi exposes this as
            // `this.source`, read by many extensions for baseUrl/apiUrl).
            ctx.evaluate(
                "globalThis.__om_instance = new DefaultExtension();" +
                    "try{globalThis.__om_instance.source=(typeof mangayomiSources!=='undefined'&&mangayomiSources.length)?mangayomiSources[0]:{};}catch(e){}",
                "mangayomi-instantiate.js",
            )
        } catch (t: Throwable) {
            runCatching { ctx.destroy() }
            throw t
        }
        context = ctx
        started = true
    }

    private fun httpBridge(rawArgs: Array<out Any?>?): String = try {
        val args = rawArgs ?: emptyArray()
        val method = (args.getOrNull(0) as? String)?.uppercase() ?: "GET"
        val url = args.getOrNull(1) as? String ?: error("httpGet requires a url")
        val headersJson = args.getOrNull(2) as? String
        val body = args.getOrNull(3) as? String
        val builder = Request.Builder().url(url)
        if (!headersJson.isNullOrBlank()) {
            val headers = JSONObject(headersJson)
            // optString coerces non-string header values (numbers/booleans) rather than throwing.
            headers.keys().forEach { key -> builder.addHeader(key, headers.optString(key)) }
        }
        // POST/PUT/PATCH require a non-null body in OkHttp — fall back to an empty one so a
        // body-less call from an extension doesn't throw; GET/HEAD/DELETE allow a null body.
        val requestBody = body?.toRequestBody(null)
            ?: "".toRequestBody(null).takeIf { method in BODY_REQUIRED_METHODS }
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, requestBody)
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            val out = JSONObject()
            out.put("status", response.code)
            out.put("url", response.request.url.toString())
            out.put("body", response.body?.string().orEmpty())
            val headersOut = JSONObject()
            response.headers.forEach { (name, value) -> headersOut.put(name, value) }
            out.put("headers", headersOut)
            out.toString()
        }
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: e.toString()).toString()
    }

    override fun close() {
        runCatching {
            executor.execute { runCatching { context?.destroy() } }
            executor.shutdown()
        }
        engineDispatcher.close()
    }

    private fun Array<out Any?>?.str(index: Int): String = (this?.getOrNull(index) as? String).orEmpty()
    private fun Array<out Any?>?.int(index: Int): Int = (this?.getOrNull(index) as? Number)?.toInt() ?: -1
    private fun Array<out Any?>?.bool(index: Int): Boolean = (this?.getOrNull(index) as? Boolean) ?: false

    private companion object {
        const val TAG = "MangayomiExtension"
        val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
