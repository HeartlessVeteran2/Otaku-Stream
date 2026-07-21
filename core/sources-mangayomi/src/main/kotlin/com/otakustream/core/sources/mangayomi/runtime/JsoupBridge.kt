package com.otakustream.core.sources.mangayomi.runtime

import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// Backs the JS `Document`/`Element` DOM API. jsoup nodes stay on the Kotlin side, keyed by an
// int handle; the JS side only ever holds handles. All access happens on the runtime's single
// engine thread, so a plain HashMap (no synchronization) is correct. Handles are cleared after
// each extension call — a source parses HTML within one method invocation, never across.
internal class JsoupBridge {

    private val nodes = HashMap<Int, Element>()
    private var seq = 0

    private fun put(element: Element): Int {
        val handle = seq++
        nodes[handle] = element
        return handle
    }

    fun load(html: String): Int = put(Jsoup.parse(html))

    fun select(handle: Int, selector: String): String {
        val element = nodes[handle] ?: return "[]"
        val array = JSONArray()
        element.select(selector).forEach { array.put(put(it)) }
        return array.toString()
    }

    fun selectFirst(handle: Int, selector: String): Int {
        val element = nodes[handle] ?: return -1
        val first = element.selectFirst(selector) ?: return -1
        return put(first)
    }

    // abs=true resolves the attribute against the document's base URI (jsoup absUrl), matching
    // Mangayomi's `getHref`/absolute-URL helpers.
    fun attr(handle: Int, name: String, abs: Boolean): String {
        val element = nodes[handle] ?: return ""
        return if (abs) element.absUrl(name) else element.attr(name)
    }

    fun text(handle: Int): String = nodes[handle]?.text().orEmpty()

    fun html(handle: Int, outer: Boolean): String {
        val element = nodes[handle] ?: return ""
        return if (outer) element.outerHtml() else element.html()
    }

    // Called at the end of every extension method invocation to drop the call's parsed nodes.
    fun clear() {
        nodes.clear()
        seq = 0
    }
}
