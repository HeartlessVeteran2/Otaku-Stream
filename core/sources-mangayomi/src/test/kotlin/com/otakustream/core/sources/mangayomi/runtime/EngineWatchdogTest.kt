package com.otakustream.core.sources.mangayomi.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// The failure: an extension that never returns took its source down for the life of the process.
//
// QuickJS is single-threaded, so MangayomiRuntime confines its engine to one thread, and the
// wrapper has no interrupt hook. A `while (true) {}` in an extension therefore owns that thread
// permanently — and every later call to that extension queued behind it, silently, forever. Rhino
// has had a wall-clock deadline since the same thing was found there; this side never got one.
//
// Nothing here can unwedge the thread; that is not available without an interrupt hook. What is
// being tested is that the *caller* stops waiting, and that later callers are told rather than
// joining the queue.
//
// Real time and real threads rather than a virtual-time test scheduler: the thing under test is a
// blocking call that ignores cancellation, which is precisely what virtual time cannot model. Every
// test carries a JUnit timeout so a regression fails rather than hanging the build.
class EngineWatchdogTest {

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "test-engine") }
    private val dispatcher = executor.asCoroutineDispatcher()

    // The wedged tests deliberately leave a thread blocked forever, so it is released here rather
    // than left to hold a thread for the rest of the suite.
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        dispatcher.close()
        executor.shutdownNow()
    }

    private fun watchdog(budgetMs: Long = 200L) = EngineWatchdog(dispatcher, budgetMs)

    // The mark is cleared on the engine thread, so there is no moment at which a caller may simply
    // read it. Polling with a deadline is the only honest way to assert it happens.
    private fun awaitUnwedged(watchdog: EngineWatchdog, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!watchdog.isWedged) return true
            Thread.sleep(10)
        }
        return false
    }

    // Blocks until tearDown, the way an extension in an infinite loop blocks until the process ends.
    private fun wedge(): Nothing {
        release.await()
        error("released")
    }

    @Test(timeout = 10_000)
    fun `an ordinary call returns its value`() = runBlocking {
        assertEquals("ok", watchdog().run("getPopular") { "ok" })
    }

    @Test(timeout = 10_000)
    fun `a call that never returns gives the caller an error instead of blocking`() = runBlocking {
        val watchdog = watchdog()

        try {
            watchdog.run("getPopular") { wedge() }
            fail("expected the overrun to be reported")
        } catch (expected: ExtensionTimeoutException) {
            assertTrue("the message should name the method", expected.message!!.contains("getPopular"))
        }
    }

    // The finding that would have made this class worse than the bug it fixes.
    //
    // Every screen already wraps a source call in a deadline of its own — HomeViewModel,
    // CatalogViewModel and MediaDetailsViewModel all use withTimeoutOrNull(15_000). A bare
    // `catch (e: TimeoutCancellationException)` around the await catches *those* too, so every
    // source merely slower than the caller's budget would be marked wedged and told to reload, and
    // the caller's own withTimeoutOrNull would never get its exception back — it would see an error
    // where it expected a timeout. Slow sources are far commoner than wedged ones.
    @Test(timeout = 10_000)
    fun `a caller's own timeout is not a wedge`() = runBlocking {
        // Budget far longer than the caller's, so anything that fires here is the caller's.
        val watchdog = watchdog(budgetMs = 30_000L)

        val result = withTimeoutOrNull(300) { watchdog.run("getPopular") { wedge() } }

        assertEquals("the caller's own timeout should return its own null", null, result)
        assertFalse("a caller giving up early does not mean the engine is gone", watchdog.isWedged)
    }

    // The half that matters most. Bounding the first call is easy; the failure was that everything
    // *after* it queued behind the dead thread and waited out its own budget in turn, one at a time,
    // for as long as the user kept trying.
    @Test(timeout = 10_000)
    fun `the next call fails immediately rather than queueing behind the wedged one`() = runBlocking {
        val watchdog = watchdog(budgetMs = 500L)

        try {
            watchdog.run("getPopular") { wedge() }
            fail("expected the overrun to be reported")
        } catch (expected: ExtensionTimeoutException) {
            // Now the engine thread is gone for good.
        }

        val startedAtMs = System.currentTimeMillis()
        try {
            watchdog.run("search") { "never reached" }
            fail("expected the second call to be refused")
        } catch (expected: ExtensionWedgedException) {
            // The message names the call that wedged it, not the one that was refused — that is
            // what tells the user which extension behaviour to report.
            assertTrue(expected.message!!.contains("getPopular"))
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        // Well under the 500ms budget: refused before dispatch, not after waiting its own turn.
        assertTrue("second call took ${elapsedMs}ms; it should not have waited", elapsedMs < 250)
    }

    // The counterweight, and without it this class would be a worse bug than the one it fixes: a
    // catalog page that is merely slow — a big listing on a weak signal — must not disable the
    // extension permanently. Overrunning the budget marks the engine; the call finishing unmarks it.
    @Test(timeout = 10_000)
    fun `a slow call that does come back clears the mark`() = runBlocking {
        val watchdog = watchdog(budgetMs = 200L)

        try {
            watchdog.run("getPopular") { Thread.sleep(600) }
            fail("expected the overrun to be reported")
        } catch (expected: ExtensionTimeoutException) {
            // Reported, but the thread was never actually lost.
        }

        // Waited for, not assumed. An earlier version signalled from inside the block, which runs
        // *before* the finally that clears the mark — so the assertion raced the engine thread and
        // could read the stale value. There is no way to observe the clear except by waiting for
        // it, so the test waits, and the bound is what makes it an assertion rather than a hang.
        assertTrue("a call that returned must not leave the engine marked", awaitUnwedged(watchdog))

        assertEquals("ok", watchdog.run("search") { "ok" })
    }

    // A call that finished must never be marked, even if its caller's budget expired at the same
    // instant.
    //
    // The guard this replaced was a "did it start" flag, true from the moment the block began and
    // still true after it ended — so a call that completed in the same breath as its timeout looked
    // eligible, the timeout marked the engine, and the block's finally, the only thing that clears
    // a mark, had already run. One narrow race and the extension refuses every later call for the
    // life of the process.
    //
    // Not a rare race, as it turns out. Written first with a block that returned instantly, this
    // caught nothing — a block finishing well inside its budget never reaches the timeout path at
    // all. Matching the block's duration to the budget puts completion and expiry in the same
    // instant, and against the old flag that poisons the engine on the *first* call: 399 of these
    // 400 were then refused, because once a mark is left with nothing able to clear it, everything
    // afterwards is refused too.
    //
    // The loop stays because a single call would rest on winning one race; the count is carried
    // into the failure message, where it is useful, and deliberately not asserted on — see below.
    @Test(timeout = 30_000)
    fun `a burst of calls that all finish never leaves the engine marked`() = runBlocking {
        // The block is made to take about as long as the budget, which is the whole trick: a block
        // that finishes well inside its budget never reaches the timeout path at all, and one that
        // never finishes is an ordinary wedge. Completion and expiry have to land together.
        val budgetMs = 10L
        val watchdog = watchdog(budgetMs = budgetMs)
        var refused = 0

        repeat(400) {
            try {
                watchdog.run("getPopular") { Thread.sleep(budgetMs); "ok" }
            } catch (expected: ExtensionTimeoutException) {
                // Whether any individual call beats a one-millisecond budget is genuinely up for
                // grabs. That is not what is being tested.
            } catch (wedged: ExtensionWedgedException) {
                // Nor is this, on its own. A call that has overrun and not yet returned *is* an
                // engine that might be gone, and refusing the next caller while that is unresolved
                // is the whole design. What is forbidden is the refusal outliving the call.
                refused++
            }
        }

        // Permanence is the property, and the only one asserted. Every block here returned, so
        // every mark must have been cleared by the block that left it — whereas the old flag let a
        // mark outlive its call with nothing able to clear it, and from then on the engine refused
        // everything.
        //
        // An earlier version also asserted a ceiling on `refused`, and that was wrong twice over.
        // It is not a defect for some calls to be refused: a call that has overrun and not yet
        // returned genuinely is an engine that might be gone, and refusing the next caller while
        // that is unresolved is the design. And how often it happens depends entirely on how loaded
        // the machine is — one on a quiet dev box, far more on a busy CI runner, which is exactly
        // how that assertion failed in CI after passing three times locally. A threshold there is a
        // coin toss dressed as an assertion.
        assertTrue(
            "the engine was left marked after $refused of 400 calls were refused",
            awaitUnwedged(watchdog),
        )
    }

    // A call that gave up must not still be waiting its turn to run.
    //
    // The screens time out every fifteen seconds and try again, so without this a slow extension
    // accumulates a queue of calls nobody is waiting for any more — each of which still gets the
    // engine thread eventually, ahead of the one the user is actually waiting on. Dropping the work
    // when the wait ends is what keeps a slow extension slow rather than progressively slower.
    @Test(timeout = 15_000)
    fun `a call abandoned at its budget never runs later`() {
        val watchdog = watchdog(budgetMs = 250L)
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hogStarted = CountDownLatch(1)
        // Counted down from inside the hog's *block*, not from its caller.
        //
        // The caller gives up at its budget — long before the block ends — so waiting on the caller
        // would assert while the engine thread was still busy, and a still-queued call would not
        // have run yet either. That version passed with the fix removed: it asserted before the
        // thing it forbids could possibly have happened.
        val engineFree = CountDownLatch(1)
        val abandonedRan = AtomicBoolean(false)

        try {
            callers.launch {
                runCatching {
                    watchdog.run("hog") {
                        hogStarted.countDown()
                        Thread.sleep(900)
                        engineFree.countDown()
                    }
                }
            }
            assertTrue("the hogging call should have started", hogStarted.await(10, TimeUnit.SECONDS))

            // Queued behind the hog, and abandoned at its own budget long before it gets a turn.
            callers.launch {
                runCatching { watchdog.run("abandoned") { abandonedRan.set(true) } }
            }

            assertTrue("the hogging block should finish", engineFree.await(10, TimeUnit.SECONDS))
            // The engine thread is free now, so anything still queued runs immediately.
            Thread.sleep(500)
            assertFalse("an abandoned call must not run after the fact", abandonedRan.get())
        } finally {
            callers.cancel()
        }
    }

    // Not covered here, and worth saying rather than implying. Two guards in EngineWatchdog have no
    // test that forces the race they exist for, and removing either leaves this file green:
    //
    //  - a call clears only *its own* mark (compareAndSet on the token), and
    //  - only a call that reached the engine thread may leave one (the `started` flag).
    //
    // Both are about two calls whose budgets expire in the same instant, one of them still queued.
    // The consequence is real and permanent — a queued call's mark is never cleared, because its
    // block never runs, so the extension would refuse every later call for the life of the process
    // even after the thread came back. But the engine serialises the work while the callers race,
    // and nothing here can pin which compareAndSet lands first. Kept because they are free and
    // provably right, not because a test would catch their removal.

    // A script that throws is broken, not stuck, and the two need different words — a thrown error
    // must not cost the extension every later call.
    @Test(timeout = 10_000)
    fun `a call that throws is not a wedge`() = runBlocking {
        val watchdog = watchdog()

        try {
            watchdog.run("getPopular") { error("extension blew up") }
            fail("expected the script error to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("extension blew up", expected.message)
        }

        assertFalse(watchdog.isWedged)
        assertEquals("ok", watchdog.run("search") { "ok" })
    }

    // Calls already queued when the engine wedges have to end too. They cannot be refused before
    // dispatch — nothing was wrong yet when they were submitted — so their own budget is what ends
    // them, and the test is that they end at all.
    //
    // The two callers run on Dispatchers.Default rather than as children of runBlocking. Launched
    // into runBlocking's event loop they never start at all: that loop only runs while its thread
    // is idle inside it, and this test's own Thread.sleep and latch waits block that thread — so
    // the "queued" call was never even submitted, and the test failed for a reason that had nothing
    // to do with the watchdog.
    @Test(timeout = 15_000)
    fun `a call queued before the wedge still ends`() {
        // A wide budget on purpose. The queued call has to reach `run` before the wedging call's
        // budget expires, or it is refused before dispatch and this tests the previous case over
        // again — so the gap between "queued call launched" and "a mark could exist" is made large
        // rather than merely likely.
        //
        // Large, not proven: the latch below fires immediately before `run`, and the mark cannot
        // appear for a further two seconds, but nothing here forces that ordering. A busy runner
        // that stalls a Dispatchers.Default coroutine for two seconds would make this fail rather
        // than pass wrongly, which is the right direction for a flake to point.
        val watchdog = watchdog(budgetMs = 2_000L)
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queuedResult = AtomicReference<Throwable?>()
        val queuedDone = CountDownLatch(1)
        val wedgeStarted = CountDownLatch(1)
        val queuedEntering = CountDownLatch(1)

        try {
            callers.launch {
                runCatching {
                    watchdog.run("getPopular") {
                        wedgeStarted.countDown()
                        wedge()
                    }
                }
            }
            // The second call has to be submitted while the first genuinely holds the thread, or it
            // is refused before dispatch and this tests the previous case over again.
            assertTrue("the wedging call should have started", wedgeStarted.await(5, TimeUnit.SECONDS))

            callers.launch {
                try {
                    queuedEntering.countDown()
                    watchdog.run("search") { "never reached" }
                } catch (t: Throwable) {
                    queuedResult.set(t)
                } finally {
                    queuedDone.countDown()
                }
            }
            assertTrue("the queued call should have reached run", queuedEntering.await(5, TimeUnit.SECONDS))

            assertTrue("the queued call should not wait forever", queuedDone.await(10, TimeUnit.SECONDS))
            // Exactly a timeout, not "either exception".
            //
            // Accepting ExtensionWedgedException too let this pass via the refused-before-dispatch
            // path, which is the *previous* test's scenario — so it could go green without ever
            // exercising a queued call. This call is submitted while the engine is still unmarked,
            // so it queues; and having never reached the thread it must not report itself as the
            // wedge either.
            assertTrue(
                "expected a timeout, got ${queuedResult.get()}",
                queuedResult.get() is ExtensionTimeoutException,
            )
        } finally {
            callers.cancel()
        }
    }

    // The app's source error handling catches Exception, not Throwable, and treats a
    // CancellationException as its own coroutine being cancelled. A wedge reported as either would
    // take down more than the one source it is about.
    @Test(timeout = 10_000)
    fun `an overrun surfaces as a plain Exception`() = runBlocking {
        val thrown = try {
            watchdog().run("getPopular") { wedge() }
            null
        } catch (t: Throwable) {
            t
        }

        assertTrue("expected an Exception, got ${thrown?.javaClass?.name}", thrown is Exception)
        assertFalse(
            "a CancellationException would cancel the caller's scope instead",
            thrown is kotlinx.coroutines.CancellationException,
        )
    }
}
