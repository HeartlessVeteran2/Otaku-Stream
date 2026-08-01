package com.otakustream.core.common

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The behaviour here is what two source adapters had each half-implemented, so it is worth pinning
// precisely. Each test names the bug it prevents — a cache test that only asserts "the second call
// is cached" would pass against every one of the broken versions.
@OptIn(ExperimentalCoroutinesApi::class)
class InFlightCacheTest {

    private var clock = 1_000L

    // The cache's own scope, deliberately not the test's: that is the arrangement under test — a job
    // parented to a caller dies with it. SupervisorJob so a produce that throws stays contained
    // instead of failing the whole test scope, and the test scheduler so virtual time still applies.
    private fun TestScope.cacheScope() =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun `concurrent callers share one production`() = runTest {
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = InFlightCache<String, String>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 1_000,
            nowMs = { clock },
        ) { key ->
            started.incrementAndGet()
            gate.await()
            "value-$key"
        }

        val first = async { cache.get("a") }
        val second = async { cache.get("a") }
        advanceUntilIdle()
        gate.complete(Unit)

        assertEquals("value-a", first.await())
        assertEquals("value-a", second.await())
        assertEquals(1, started.get())
    }

    @Test
    fun `result is reused inside the ttl and refetched after it`() = runTest {
        val started = AtomicInteger()
        val cache = InFlightCache<String, Int>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 1_000,
            nowMs = { clock },
        ) { started.incrementAndGet() }

        assertEquals(1, cache.get("a"))
        clock += 999
        assertEquals("inside the TTL the cached value is reused", 1, cache.get("a"))
        clock += 2
        assertEquals("past the TTL a fresh production runs", 2, cache.get("a"))
    }

    // The bug: timing from creation meant a slow producer handed its first reader a result that had
    // already spent most of its life waiting to exist.
    @Test
    fun `ttl runs from completion, not from creation`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val cache = InFlightCache<String, Int>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 1_000,
            nowMs = { clock },
        ) {
            started.incrementAndGet()
            gate.await()
            started.get()
        }

        val pending = async { cache.get("a") }
        advanceUntilIdle()
        // The production takes far longer than the whole TTL.
        clock += 5_000
        gate.complete(Unit)
        assertEquals(1, pending.await())

        // Timed from creation this would already be stale; timed from completion it is brand new.
        assertEquals("the freshly-arrived result is still usable", 1, cache.get("a"))
        assertEquals(1, started.get())
    }

    // The bug: an unfinished job judged "too old" would start a duplicate alongside a request that
    // was running perfectly well — the exact opposite of what the cache is for.
    @Test
    fun `an in-flight job is joined however long it has been running`() = runTest {
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = InFlightCache<String, String>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 1_000,
            nowMs = { clock },
        ) {
            started.incrementAndGet()
            gate.await()
            "done"
        }

        val first = async { cache.get("a") }
        advanceUntilIdle()
        clock += 60_000

        val second = async { cache.get("a") }
        advanceUntilIdle()
        gate.complete(Unit)

        assertEquals("done", first.await())
        assertEquals("done", second.await())
        assertEquals("the second caller joined rather than starting its own", 1, started.get())
    }

    // The bug: a failure that recorded a completion time could be served to a caller arriving
    // between the timestamp and the eviction, making a transient error look like a cached result.
    @Test
    fun `a failure is never cached`() = runTest {
        val attempts = AtomicInteger()
        val cache = InFlightCache<String, String>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 60_000,
            nowMs = { clock },
        ) {
            if (attempts.incrementAndGet() == 1) error("transient") else "recovered"
        }

        val failure = runCatching { cache.get("a") }
        assertTrue(failure.isFailure)

        // No clock movement at all: even immediately afterwards the failure must not be reused.
        assertEquals("recovered", cache.get("a"))
        assertEquals(2, attempts.get())
    }

    // The bug that started all of this: a job parented to the caller died with it, so backing out of
    // a screen cancelled the request the next caller was about to join.
    @Test
    fun `one caller going away does not kill the shared job`() = runTest {
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = InFlightCache<String, String>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 1_000,
            nowMs = { clock },
        ) {
            started.incrementAndGet()
            gate.await()
            "value"
        }

        val leaving = launch { cache.get("a") }
        advanceUntilIdle()
        val staying = async { cache.get("a") }
        advanceUntilIdle()

        leaving.cancelAndJoin()
        gate.complete(Unit)

        assertEquals("the surviving caller still gets its value", "value", staying.await())
        assertEquals("and it was never re-produced", 1, started.get())
    }

    @Test
    fun `the map is bounded`() = runTest {
        val started = AtomicInteger()
        val cache = InFlightCache<Int, Int>(
            scope = cacheScope(),
            maxEntries = 2,
            ttlMs = 60_000,
            nowMs = { clock },
        ) { started.incrementAndGet() }

        cache.get(1)
        cache.get(2)
        cache.get(3)
        val afterFilling = started.get()

        // 1 was evicted by the bound, so asking again re-produces it; 3 is still resident.
        cache.get(1)
        assertNotEquals("the evicted key was produced again", afterFilling, started.get())
        val afterRefetch = started.get()
        cache.get(3)
        assertEquals("the resident key was not", afterRefetch, started.get())
    }

    // The bug: eviction that can only consider the single eldest entry stalls completely when that
    // entry is a request still running, so completed results pile up behind it without bound.
    @Test
    fun `a long-running request does not stop the bound being enforced`() = runTest {
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val cache = InFlightCache<Int, Int>(
            scope = cacheScope(),
            maxEntries = 2,
            ttlMs = 60_000,
            nowMs = { clock },
        ) { key ->
            started.incrementAndGet()
            // Key 0 is the eldest and never finishes while the rest come and go.
            if (key == 0) gate.await()
            key
        }

        val stuck = async { cache.get(0) }
        advanceUntilIdle()

        // Well past the bound, all completing while key 0 is still running.
        for (key in 1..6) cache.get(key)
        val afterFilling = started.get()

        // The earliest completed keys must have been dropped despite the stuck entry ahead of them.
        cache.get(1)
        assertNotEquals("an old completed entry was evicted", afterFilling, started.get())

        // And the stuck request itself was never dropped — it is still the one a new caller joins.
        val beforeJoining = started.get()
        val joiner = async { cache.get(0) }
        advanceUntilIdle()
        assertEquals("the running request was joined, not restarted", beforeJoining, started.get())

        gate.complete(Unit)
        assertEquals(0, stuck.await())
        assertEquals(0, joiner.await())
    }

    @Test
    fun `clear drops cached results`() = runTest {
        val started = AtomicInteger()
        val cache = InFlightCache<String, Int>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 60_000,
            nowMs = { clock },
        ) { started.incrementAndGet() }

        assertEquals(1, cache.get("a"))
        cache.clear()
        assertEquals(2, cache.get("a"))
    }

    @Test
    fun `distinct keys do not share a result`() = runTest {
        val cache = InFlightCache<String, String>(
            scope = cacheScope(),
            maxEntries = 4,
            ttlMs = 60_000,
            nowMs = { clock },
        ) { key -> "value-$key" }

        assertEquals("value-a", cache.get("a"))
        assertEquals("value-b", cache.get("b"))
    }
}
