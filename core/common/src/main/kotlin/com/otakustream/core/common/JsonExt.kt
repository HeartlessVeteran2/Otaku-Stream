package com.otakustream.core.common

import org.json.JSONArray
import org.json.JSONObject

// Null-safe accessors over Android's org.json. The stock optString returns the literal string
// "null" for a JSON null and "" for a missing key, which leaks into parsed models; these helpers
// collapse both to a real Kotlin null/empty so downstream nullability checks behave. Shared so
// every parser (Stremio manifests, add-on collections, …) treats JSON null identically.

fun JSONObject.stringOrEmpty(key: String): String = if (isNull(key)) "" else optString(key)

fun JSONObject.stringOrNull(key: String): String? = stringOrEmpty(key).ifEmpty { null }

fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

fun JSONObject.stringListOrEmpty(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.stringOrNull(it) }
}

fun JSONArray.stringOrNull(index: Int): String? = if (isNull(index)) null else optString(index).ifEmpty { null }
