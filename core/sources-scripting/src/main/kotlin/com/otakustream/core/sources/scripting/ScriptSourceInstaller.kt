package com.otakustream.core.sources.scripting

import com.otakustream.core.database.scripted.ScriptedSourceRecord
import com.otakustream.core.database.scripted.ScriptedSourceRepository
import com.otakustream.core.sources.api.stableSourceId as sharedStableSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

fun stableSourceId(name: String, lang: String): Long = sharedStableSourceId(name, lang)

class ScriptSourceInstaller @Inject constructor(
    private val httpClient: OkHttpClient,
    private val scriptEngine: ScriptEngine,
    private val repository: ScriptedSourceRepository,
) {
    suspend fun installFromUrl(scriptUrl: String): ScriptedVideoSource = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(scriptUrl).build()
        val content = httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Failed to download script: HTTP ${response.code}" }
            response.body?.string() ?: error("Empty script body")
        }
        val source = buildSource(scriptUrl, content)
        repository.save(
            ScriptedSourceRecord(scriptUrl = scriptUrl, scriptContent = content, name = source.name, lang = source.lang, version = 1),
        )
        source
    }

    suspend fun uninstall(scriptUrl: String) = repository.delete(scriptUrl)

    fun buildSource(scriptUrl: String, content: String): ScriptedVideoSource {
        val scope = scriptEngine.load(content, scriptUrl)
        val name = scriptEngine.readString(scope, "SOURCE_NAME", scriptUrl)
        val lang = scriptEngine.readString(scope, "SOURCE_LANG", "en")
        return ScriptedVideoSource(
            engine = scriptEngine,
            scope = scope,
            id = stableSourceId(name, lang),
            name = name,
            lang = lang,
        )
    }
}
