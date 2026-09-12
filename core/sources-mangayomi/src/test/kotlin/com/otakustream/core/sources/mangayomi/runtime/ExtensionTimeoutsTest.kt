package com.otakustream.core.sources.mangayomi.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

// A guard on three constants, not on the behaviour they configure — and worth being explicit about
// which, in the way ScriptTimeoutTest is about its own pair.
//
// What it locks is the ordering. The three bound nested things: one stalled stage of a fetch, one
// whole fetch, and one whole extension method, which commonly makes several fetches in sequence.
// Get the order wrong and each failure is misreported as the next one out — a silent host looks
// like a wedged extension, and the fix would be applied to the wrong layer.
//
// What it does not cover: that the client actually applies the two timeouts. Deleting the
// .readTimeout() call from MangayomiRuntime would leave this green. Catching that needs a server
// that stalls on demand, which needs a dependency this module does not have — the same limit the
// Rhino side's equivalent test records, and not worth pretending past.
class ExtensionTimeoutsTest {

    @Test
    fun `the timeouts nest from a stage to a fetch to a whole method`() {
        // A host that accepts the connection and then goes quiet must be recognised well inside the
        // total, or the two are the same timeout wearing different names.
        assertTrue(
            "stage ${EXTENSION_STAGE_TIMEOUT_SECONDS}s must be under the ${EXTENSION_CALL_TIMEOUT_SECONDS}s call total",
            EXTENSION_STAGE_TIMEOUT_SECONDS < EXTENSION_CALL_TIMEOUT_SECONDS,
        )

        // And one fetch must not be able to outlast the budget for the method containing it, or a
        // single slow host would be reported as an extension that stopped responding — the
        // false positive EngineWatchdog's self-clearing exists to make survivable, and which this
        // ordering is what keeps rare.
        assertTrue(
            "one ${EXTENSION_CALL_TIMEOUT_SECONDS}s fetch must fit inside the ${EXTENSION_BUDGET_MS}ms method budget",
            EXTENSION_CALL_TIMEOUT_SECONDS * 1_000L < EXTENSION_BUDGET_MS,
        )
    }
}
