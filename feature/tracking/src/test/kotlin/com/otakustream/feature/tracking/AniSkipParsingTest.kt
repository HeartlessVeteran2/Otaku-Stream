package com.otakustream.feature.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

// What AniSkip's interface promises: "any failure (no data, network error, malformed body) yields
// an empty list so playback is never affected."
//
// The body half of that promise is what these pin. It used to hold only by luck — the parser threw
// straight out of `fetch` on anything org.json disliked, and playback was unaffected purely because
// both of today's callers happen to wrap the call in a runCatching of their own. A third caller
// reading the interface comment and believing it would have been the bug.
class AniSkipParsingTest {

    @Test
    fun `a body that is not JSON at all is no skip times, not an exception`() {
        // A captive portal, a Cloudflare interstitial, or a plain outage page. All of them arrive
        // with a 200 and none of them are JSON.
        assertEquals(emptyList<AniSkipInterval>(), parseAniSkipBody("<html>502 Bad Gateway</html>"))
        assertEquals(emptyList<AniSkipInterval>(), parseAniSkipBody(""))
    }

    @Test
    fun `a well-formed answer becomes intervals in milliseconds`() {
        val intervals = parseAniSkipBody(
            """
            {"found":true,"results":[
              {"skipType":"op","interval":{"startTime":85.5,"endTime":175.5}},
              {"skipType":"ed","interval":{"startTime":1320.0,"endTime":1410.0}}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                AniSkipInterval(85_500L, 175_500L, AniSkipInterval.KIND_INTRO),
                AniSkipInterval(1_320_000L, 1_410_000L, AniSkipInterval.KIND_OUTRO),
            ),
            intervals,
        )
    }

    @Test
    fun `entries the player could not act on are dropped, and the rest still parse`() {
        // A skip type this app has no button for, an interval that ends before it starts, an entry
        // missing its interval entirely, and an entry that isn't an object. Dropping the whole
        // response over any one of them would cost the user the intro skip that was right there.
        val intervals = parseAniSkipBody(
            """
            {"found":true,"results":[
              {"skipType":"mixed-op","interval":{"startTime":0.0,"endTime":90.0}},
              {"skipType":"op","interval":{"startTime":175.5,"endTime":85.5}},
              {"skipType":"ed"},
              "not an object",
              {"skipType":"recap","interval":{"startTime":10.0,"endTime":40.0}}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf(AniSkipInterval(10_000L, 40_000L, AniSkipInterval.KIND_RECAP)), intervals)
    }

    @Test
    fun `found false is respected even when results are present`() {
        // AniSkip answers 200 with found=false when it has nothing for the episode, and has been
        // seen to send an empty-ish results array alongside it. found is the authority.
        assertEquals(
            emptyList<AniSkipInterval>(),
            parseAniSkipBody("""{"found":false,"results":[{"skipType":"op","interval":{"startTime":1.0,"endTime":2.0}}]}"""),
        )
    }
}
