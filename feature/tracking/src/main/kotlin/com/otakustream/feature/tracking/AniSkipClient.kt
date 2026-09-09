package com.otakustream.feature.tracking

import com.otakustream.core.common.runCatchingCancellable
import com.otakustream.core.network.await
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
// An interface for the same reason as AniListClient: one network call, and MediaDetailsViewModel
// holds it to build the player's skip lookup.
interface AniSkipClient {
    suspend fun fetch(malId: Long, episodeNumber: Int, episodeLengthSec: Long): List<AniSkipInterval>
}

@Singleton
class AniSkipClientImpl @Inject constructor(
    private val httpClient: OkHttpClient,
) : AniSkipClient {
    // The interface above promises an empty list on every failure, so this is the one place that
    // has to make it true. Both of today's callers happen to wrap the call in a runCatching of
    // their own, but a contract that holds only because every caller defends itself is not a
    // contract — the next one to read that comment and trust it would let an IOException or a
    // JSONException out of a best-effort lookup and into the player.
    //
    // runCatchingCancellable, not runCatching: await() suspends, and swallowing the cancellation
    // would keep this running after the playback that asked for it is gone.
    override suspend fun fetch(malId: Long, episodeNumber: Int, episodeLengthSec: Long): List<AniSkipInterval> =
        withContext(Dispatchers.IO) {
            if (malId <= 0 || episodeNumber <= 0 || episodeLengthSec <= 0) return@withContext emptyList()
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber" +
                "?types[]=op&types[]=ed&types[]=recap&episodeLength=$episodeLengthSec"
            val request = Request.Builder().url(url).get().build()
            runCatchingCancellable {
                httpClient.newCall(request).await().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    parseAniSkipBody(response.body?.string().orEmpty())
                }
            }.getOrDefault(emptyList())
        }
}

// A community API's body is not a promise, and org.json throws on anything it does not recognise —
// including an empty string, an HTML error page, or `results` holding something that isn't an
// object. The interface says a malformed body is an empty list, so the translation happens here.
//
// Pure and non-suspending, so the plain runCatching has no cancellation to swallow. Separated from
// the request so it can be tested without a server.
internal fun parseAniSkipBody(body: String): List<AniSkipInterval> = runCatching {
    val root = JSONObject(body)
    if (!root.optBoolean("found", false)) return@runCatching emptyList()
    val results = root.optJSONArray("results") ?: return@runCatching emptyList()
    (0 until results.length()).mapNotNull { index ->
        val entry = results.optJSONObject(index) ?: return@mapNotNull null
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
}.getOrDefault(emptyList())
