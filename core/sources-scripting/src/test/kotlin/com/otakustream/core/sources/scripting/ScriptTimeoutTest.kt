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

    // Two engines, because the tests here ask two different questions and only one of them can
    // afford a short deadline.
    //
    // For "does a runaway script get stopped", the deadline is a cost: a real one would spend
    // fifteen seconds per test doing nothing but spinning, three times over. 250ms proves the same
    // mechanism.
    private val shortDeadline = ScriptEngine(HttpBridge(OkHttpClient())).apply { deadlineMs = 250L }

    // For "is ordinary work left alone", the deadline is the subject, and shortening it turns the
    // test into a stopwatch race against whatever machine is running it. This is what failed in CI
    // while passing locally: the 200_000-iteration loop below takes ~146ms on a dev box, which sits
    // inside 250ms with no real margin at all, and a shared runner interpreting it — Rhino runs with
    // optimizationLevel = -1 on Android, so there is no JIT to warm — is several times slower.
    //
    // The production deadline is the right one here anyway. The claim being tested is that the
    // instruction observer does not false-positive on a script doing genuine work, and the budget
    // that has to hold is the one extensions actually run under.
    private val realDeadline = ScriptEngine(HttpBridge(OkHttpClient()))

    @Test
    fun `an infinite loop is stopped instead of running forever`() {
        val scope = shortDeadline.load("function spin() { while (true) {} }", "spin.js")

        val startedAtMs = System.currentTimeMillis()
        try {
            shortDeadline.call(scope, "spin")
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
        val scope = shortDeadline.load(
            """
            function spin() { while (true) {} }
            function quick() { return "ok"; }
            """.trimIndent(),
            "mixed.js",
        )

        try {
            shortDeadline.call(scope, "spin")
            fail("expected the runaway script to be stopped")
        } catch (expected: ScriptTimeoutException) {
            // Expected.
        }

        // The same engine, the same scope, immediately afterwards.
        assertEquals("ok", shortDeadline.call(scope, "quick"))
    }

    // A script that finishes well inside the deadline must be unaffected — the observer runs on
    // every ordinary source too, and a false positive here would break every working extension.
    //
    // Deliberately on realDeadline: see the field comment. Under the production budget this loop
    // has roughly a hundredfold margin, so the test measures the observer's behaviour rather than
    // the runner's speed.
    @Test
    fun `ordinary work is not interrupted`() {
        val scope = realDeadline.load(
            """
            function work() {
              var total = 0;
              for (var i = 0; i < 200000; i++) { total += i; }
              return String(total);
            }
            """.trimIndent(),
            "work.js",
        )
        assertEquals("19999900000", realDeadline.call(scope, "work"))
    }

    // The test the first version of this deadline would have failed.
    //
    // A source that wraps its own loop in try/catch — which plenty of real extensions do defensively
    // around a whole scrape — used to catch the timeout, resume, get interrupted at the next
    // checkpoint, catch again, and never stop. The mutex stayed held and the source was wedged for
    // the life of the process, which is precisely the failure the deadline exists to prevent. It
    // only works because the observer throws an Error, which Rhino will not hand to a script's
    // catch block.
    @Test
    fun `a script cannot swallow its own timeout`() {
        val scope = shortDeadline.load(
            """
            function stubborn() {
              var caught = 0;
              while (true) {
                try {
                  while (true) {}
                } catch (e) {
                  caught++;
                }
              }
            }
            """.trimIndent(),
            "stubborn.js",
        )

        val startedAtMs = System.currentTimeMillis()
        try {
            shortDeadline.call(scope, "stubborn")
            fail("expected the script to be stopped despite catching")
        } catch (expected: ScriptTimeoutException) {
            // Control came back, and as the caller-facing type rather than an Error — the app's
            // error handling catches Exception, not Throwable.
        }
        assertTrue(
            "took ${System.currentTimeMillis() - startedAtMs}ms; a swallowed deadline never returns",
            System.currentTimeMillis() - startedAtMs < 30_000,
        )
    }

    // Nothing above may leak an Error to callers: ScriptedSourceBootstrapper deliberately catches
    // Exception rather than Throwable, so an Error escaping the engine would crash the app on a
    // slow script during startup instead of skipping that one source.
    @Test
    fun `the deadline surfaces as an Exception, never an Error`() {
        val scope = shortDeadline.load("function spin() { while (true) {} }", "spin.js")

        val thrown = try {
            shortDeadline.call(scope, "spin")
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue("expected an Exception, got ${thrown?.javaClass?.name}", thrown is Exception)
        assertTrue(thrown is ScriptTimeoutException)
    }

    // A timeout is not the same thing as a broken script, and the two need different words: one
    // source is stuck, the other is wrong. Reported as its own type so a caller can tell them apart.
    @Test
    fun `a timeout is distinguishable from a script error`() {
        val scope = shortDeadline.load(
            """
            function boom() { throw new Error("nope"); }
            function spin() { while (true) {} }
            """.trimIndent(),
            "both.js",
        )

        var timedOut = false
        try {
            shortDeadline.call(scope, "spin")
        } catch (e: ScriptTimeoutException) {
            timedOut = true
        }
        assertTrue(timedOut)

        var threwSomethingElse = false
        try {
            shortDeadline.call(scope, "boom")
        } catch (e: ScriptTimeoutException) {
            fail("a thrown error must not be reported as a timeout")
        } catch (e: Exception) {
            threwSomethingElse = true
        }
        assertTrue(threwSomethingElse)
    }

    // The two timeouts are one mechanism, and they live in different files.
    //
    // A script blocked on a socket executes no instructions, so the observer that enforces the
    // deadline cannot see it. The request giving up first is the only thing that hands control back
    // for the observer to act on — which means the call timeout has to expire *before* the deadline
    // does. Raise one without the other and a stalled fetch stops being interruptible at all, and
    // that failure shows up as a source that is wedged until the app is force-stopped, not as a
    // failing assertion anywhere near either constant. So the relationship is asserted directly.
    @Test
    fun `a fetch gives up before the script deadline does`() {
        assertTrue(
            "callTimeout ${SCRIPT_CALL_TIMEOUT_SECONDS}s must expire before the ${SCRIPT_DEADLINE_MS}ms deadline",
            SCRIPT_CALL_TIMEOUT_SECONDS * 1_000L < SCRIPT_DEADLINE_MS,
        )
        // And a stalled stage must fail well inside the total, or the two are the same timeout
        // wearing different names and a dead host still costs the full budget.
        assertTrue(
            "stage timeout ${SCRIPT_STAGE_TIMEOUT_SECONDS}s must be under the ${SCRIPT_CALL_TIMEOUT_SECONDS}s total",
            SCRIPT_STAGE_TIMEOUT_SECONDS < SCRIPT_CALL_TIMEOUT_SECONDS,
        )
    }
}
