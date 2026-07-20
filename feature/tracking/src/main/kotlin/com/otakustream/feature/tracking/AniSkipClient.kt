package com.otakustream.feature.tracking

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// One skip interval as returned by AniSkip, already normalized to intro/outro/recap.
data class AniSkipInterval(val startMs: Long, val endMs: Long, val kind: String) {
    companion object {
        const val KIND_INTRO = "intro"
        const val KIND_OUTRO = "outro"
        const val KIND_RECAP = "recap"
    }
}

// Community intro/outro timings from https://aniskip.com — keyed by MyAnimeList id + episode
// number. Entirely best-effort: any failure (no data, network error, malformed body) yields an
// empty list so playback is never affected.
@Singleton
class AniSkipClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    suspend fun fetch(malId: Long, episodeNumber: Int, episodeLengthSec: Long): List<AniSkipInterval> =
        withContext(Dispatchers.IO) {
            if (malId <= 0 || episodeNumber <= 0 || episodeLengthSec <= 0) return@withContext emptyList()
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber" +
                "?types[]=op&types[]=ed&types[]=recap&episodeLength=$episodeLengthSec"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyString = response.body?.string()
                if (bodyString.isNullOrBlank()) return@withContext emptyList()
                val root = JSONObject(bodyString)
                if (!root.optBoolean("found", false)) return@withContext emptyList()
                val results = root.optJSONArray("results") ?: return@withContext emptyList()
                (0 until results.length()).mapNotNull { index ->
                    val entry = results.getJSONObject(index)
                    val interval = entry.optJSONObject("interval") ?: return@mapNotNull null
                    val startSec = interval.optDouble("startTime", -1.0)
                    val endSec = interval.optDouble("endTime", -1.0)
                    if (startSec < 0 || endSec <= startSec) return@mapNotNull null
                    val kind = when (entry.optString("skipType")) {
                        "op" -> AniSkipInterval.KIND_INTRO
                        "ed" -> AniSkipInterval.KIND_OUTRO
                        "recap" -> AniSkipInterval.KIND_RECAP
                        else -> return@mapNotNull null
                    }
                    AniSkipInterval((startSec * 1000).toLong(), (endSec * 1000).toLong(), kind)
                }
            }
        }
}
