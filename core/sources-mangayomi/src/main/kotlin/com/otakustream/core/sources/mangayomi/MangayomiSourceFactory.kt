package com.otakustream.core.sources.mangayomi

import com.otakustream.core.database.mangayomi.MangayomiSourceRecord
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

    suspend fun create(
        source: String,
        override: MangayomiSourceMetadata? = null,
        prefsJson: String? = null,
        // Bring the JS engine up now rather than on first use. Worth it when installing — a
        // malformed extension should fail while the user is still looking at the Install button —
        // but not at cold-start bootstrap, where it means a native QuickJS context per installed
        // extension before the home screen can render. Ignored when no metadata override is
        // supplied, since reading the extension's own metadata needs the engine anyway.
        forceBringup: Boolean = true,
    ): MangayomiVideoSource {
        val runtime = MangayomiRuntime(source, httpClient, prefsJson)
        return try {
            if (forceBringup || override == null) runtime.ensureLoaded()
            val metadata = override ?: readSelfMetadata(runtime)
            MangayomiVideoSource(metadata, runtime)
        } catch (t: Throwable) {
            // Bringup / metadata read can throw on malformed extension JS; the runtime's engine
            // thread + native context would leak without this close.
            runCatching { runtime.close() }
            throw t
        }
    }

    // Rebuilds a source from its persisted record (script + metadata + resolved preferences) — used
    // to reload an extension after its preferences change and at cold-start bootstrap.
    //
    // forceBringup = false, which is what the parameter above was added for and what this call site
    // was missing: every persisted extension was starting a QuickJS thread and parsing its whole
    // script before the home screen could render, on every cold start. Nothing here needs the engine
    // — the metadata comes from the record — and the first catalog call brings it up anyway. A
    // malformed script now surfaces on first use rather than at bootstrap, which is both later and
    // closer to where the user can see it; install still validates eagerly.
    suspend fun createFromRecord(record: MangayomiSourceRecord): MangayomiVideoSource = create(
        source = record.scriptContent,
        forceBringup = false,
        override = MangayomiSourceMetadata(
            id = record.id,
            name = record.name,
            lang = record.lang,
            baseUrl = record.baseUrl,
            iconUrl = record.iconUrl,
            version = record.version,
            isNsfw = record.isNsfw,
        ),
        prefsJson = record.prefsJson,
    )

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
