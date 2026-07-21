package com.otakustream.core.sources.mangayomi

import com.otakustream.core.sources.api.stableSourceId
import com.otakustream.core.sources.mangayomi.runtime.MangayomiRuntime
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// Builds a live MangayomiVideoSource from an extension's JS source. Identity/display metadata is
// taken from the repo index entry when installing; when that isn't available (e.g. the bundled
// example), it's read from the extension's own `mangayomiSources[0]` global, which every
// Mangayomi/AnymeX extension declares.
@Singleton
class MangayomiSourceFactory @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    suspend fun create(source: String, override: MangayomiSourceMetadata? = null): MangayomiVideoSource {
        val runtime = MangayomiRuntime(source, httpClient)
        return try {
            val metadata = override ?: readSelfMetadata(runtime)
            MangayomiVideoSource(metadata, runtime)
        } catch (t: Throwable) {
            // Metadata read forces engine bringup; if the extension JS is malformed it throws here,
            // and the runtime's engine thread + native context would leak without this close.
            runCatching { runtime.close() }
            throw t
        }
    }

    private suspend fun readSelfMetadata(runtime: MangayomiRuntime): MangayomiSourceMetadata {
        val json = runtime.readGlobalJson("mangayomiSources[0]")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
        val name = json.optString("name").ifEmpty { "Mangayomi source" }
        val lang = json.optString("lang").ifEmpty { "en" }
        val declaredId = json.optLong("id", 0L)
        return MangayomiSourceMetadata(
            // Fall back to the app's stable id scheme when the extension declares no numeric id,
            // so registry dedupe still keys deterministically.
            id = if (declaredId != 0L) declaredId else stableSourceId(name, lang),
            name = name,
            lang = lang,
            baseUrl = json.optString("baseUrl"),
            iconUrl = json.optString("iconUrl").ifEmpty { null },
            version = json.optString("version"),
            isNsfw = json.optBoolean("isNsfw", false),
        )
    }
}
