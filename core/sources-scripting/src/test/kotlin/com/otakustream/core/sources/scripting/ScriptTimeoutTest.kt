package com.otakustream.core.sources.scripting

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// The test for the failure that made a bad source permanently unusable.
//
// Every entry point in ScriptedVideoSource holds a mutex across a blocking Rhino call. Cancelling a
// coroutine cannot interrupt one, so `withTimeoutOrNull` around the call returned on schedule while
// the interpreter carried on — the lock was never released, and every later search or episode
// resolve for that source blocked forever. The source reported "timed out" on everything from then
// on, retrying could not help because the retry queued behind the same lock, and only force-stopping
// the app recovered it.
//
// The fix is a deadline checked by Rhino's instruction observer, which throws and unwinds the
// interpreter — releasing the lock. These tests are the reason to believe it works.
class ScriptTimeoutTest {

    // A short deadline, so proving a runaway script is stopped costs the suite a fraction of a
    // second rather than the real fifteen seconds, three times over. The mechanism under test is
    // the same either way.
    private val engine = ScriptEngine(HttpBridge(OkHttpClient())).apply { deadlineMs = 250L }

    @Test
    fun `an infinite loop is stopped instead of running forever`() {
        val scope = engine.load("function spin() { while (true) {} }", "spin.js")

        val startedAtMs = System.currentTimeMillis()
        try {
            engine.call(scope, "spin")
            fail("expected the runaway script to be stopped")
        } catch (expected: ScriptTimeoutException) {
            // The point: control comes back at all.
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        // Bounded, and generously so — this asserts that it returns, not how promptly. Before the
        // deadline existed this call never returned and the test would hang rather than fail.
        assertTrue("took ${elapsedMs}ms, expected the deadline to stop it", elapsedMs < 30_000)
    }

    // The deadline is per call, not per engine: a source that has once been slow must not be left
    // permanently expired, or the first timeout would disable it exactly the way the mutex used to.
    @Test
    fun `the deadline resets between calls`() {
        val scope = engine.load(
            """
            function spin() { while (true) {} }
            function quick() { return "ok"; }
            """.trimIndent(),
            "mixed.js",
        )

        try {
            engine.call(scope, "spin")
            fail("expected the runaway script to be stopped")
        } catch (expected: ScriptTimeoutException) {
            // Expected.
        }

        // The same engine, the same scope, immediately afterwards.
        assertEquals("ok", engine.call(scope, "quick"))
    }

    // A script that finishes well inside the deadline must be unaffected — the observer runs on
    // every ordinary source too, and a false positive here would break every working extension.
    @Test
    fun `ordinary work is not interrupted`() {
        val scope = engine.load(
            """
            function work() {
              var total = 0;
              for (var i = 0; i < 200000; i++) { total += i; }
              return String(total);
            }
            """.trimIndent(),
            "work.js",
        )
        assertEquals("19999900000", engine.call(scope, "work"))
    }

    // A timeout is not the same thing as a broken script, and the two need different words: one
    // source is stuck, the other is wrong. Reported as its own type so a caller can tell them apart.
    @Test
    fun `a timeout is distinguishable from a script error`() {
        val scope = engine.load(
            """
            function boom() { throw new Error("nope"); }
            function spin() { while (true) {} }
            """.trimIndent(),
            "both.js",
        )

        var timedOut = false
        try {
            engine.call(scope, "spin")
        } catch (e: ScriptTimeoutException) {
            timedOut = true
        }
        assertTrue(timedOut)

        var threwSomethingElse = false
        try {
            engine.call(scope, "boom")
        } catch (e: ScriptTimeoutException) {
            fail("a thrown error must not be reported as a timeout")
        } catch (e: Exception) {
            threwSomethingElse = true
        }
        assertTrue(threwSomethingElse)
    }
}
