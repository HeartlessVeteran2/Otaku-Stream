package com.otakustream.core.common

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Null-safe accessors over Android's org.json. The stock optString returns the literal string
// "null" for a JSON null and "" for a missing key, which leaks into parsed models; these helpers
// collapse both to a real Kotlin null/empty so downstream nullability checks behave. Shared so
// every parser (Stremio manifests, add-on collections, …) treats JSON null identically.

fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)

fun JSONObject.stringOrNull(key: String): String? = stringOrEmpty(key).ifEmpty { null }

// getInt throws when the value is present but not coercible to a number — a string like "N/A", an
// object, an empty string. A Stremio meta with one such field would take down the parse of the whole
// response, and the caller sees an empty catalog rather than one item with a missing year. Coercion
// is kept (getInt does accept "5"), but a value that cannot be read is simply absent, which is what
// the name promises.
//
// JSONException specifically, not runCatching: "this field isn't a number" is the only failure worth
// absorbing here, and a blanket catch would swallow an OutOfMemoryError or a StackOverflowError from
// a pathological document and report it as a missing field.
fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) {
        try {
            getInt(key)
        } catch (_: JSONException) {
            null
        }
    } else {
        null
    }

fun JSONObject.stringListOrEmpty(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.stringOrNull(it) }
}

fun JSONArray.stringOrNull(index: Int): String? = if (isNull(index)) null else optString(index).ifEmpty { null }
