package com.otakustream.core.sources.mangayomi.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            fail("expected the wedged call to be reported")
        } catch (expected: ExtensionWedgedException) {
            assertTrue("the message should name the method", expected.message!!.contains("getPopular"))
        }
    }

    // The half that matters most. Bounding the first call is easy; the failure was that everything
    // *after* it queued behind the dead thread and waited out its own budget in turn, one at a time,
    // for as long as the user kept trying.
    @Test(timeout = 10_000)
    fun `the next call fails immediately rather than queueing behind the wedged one`() = runBlocking {
        val watchdog = watchdog(budgetMs = 500L)

        try {
            watchdog.run("getPopular") { wedge() }
            fail("expected the wedged call to be reported")
        } catch (expected: ExtensionWedgedException) {
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
        val finished = CountDownLatch(1)

        try {
            watchdog.run("getPopular") {
                Thread.sleep(600)
                finished.countDown()
            }
            fail("expected the overrun to be reported")
        } catch (expected: ExtensionWedgedException) {
            // Reported, but the thread was never actually lost.
        }
        assertTrue("the slow call should still finish", finished.await(5, TimeUnit.SECONDS))
        assertFalse("a call that returned must not leave the engine marked", watchdog.isWedged)

        assertEquals("ok", watchdog.run("search") { "ok" })
    }

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
        val watchdog = watchdog(budgetMs = 400L)
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queuedResult = AtomicReference<Throwable?>()
        val queuedDone = CountDownLatch(1)
        val wedgeStarted = CountDownLatch(1)

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
                    watchdog.run("search") { "never reached" }
                } catch (t: Throwable) {
                    queuedResult.set(t)
                } finally {
                    queuedDone.countDown()
                }
            }

            assertTrue("the queued call should not wait forever", queuedDone.await(10, TimeUnit.SECONDS))
            assertTrue(
                "expected ExtensionWedgedException, got ${queuedResult.get()}",
                queuedResult.get() is ExtensionWedgedException,
            )
        } finally {
            callers.cancel()
        }
    }

    // The app's source error handling catches Exception, not Throwable, and treats a
    // CancellationException as its own coroutine being cancelled. A wedge reported as either would
    // take down more than the one source it is about.
    @Test(timeout = 10_000)
    fun `a wedge surfaces as a plain Exception`() = runBlocking {
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
