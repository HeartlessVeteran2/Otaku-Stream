package com.otakustream.core.common

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

// A cache of in-flight work: concurrent callers asking for the same key join one job rather than
// starting several, and the finished result stays usable for a while afterwards.
//
// This exists as one class because two source adapters had grown their own copies of it, and the
// copies had already drifted — a fix applied to one was missing from the other. The behaviour below
// is subtle enough that duplicating it is a standing hazard, and cheap enough to test once here
// (which matters: none of the callers can be exercised without a device).
//
// The three properties worth stating, because each one is a bug that was fixed:
//
//  - The job belongs to `scope`, not to whichever caller happened to create it. Parented to a
//    caller, a shared job dies when that caller is cancelled — so a user backing out of a screen
//    kills the request the *next* caller was about to join, and the cache becomes a way to fail.
//    Pass a scope the owner controls, with a SupervisorJob so one failure can't cancel its siblings.
//
//  - Jobs start LAZY and are started by `get` *after* the entry is in the map. Started eagerly, a
//    job that fails immediately can run its completion handler before the insertion, so the
//    identity-based eviction below finds nothing to remove and the failure stays cached.
//
//  - Age is measured from completion, and only for a job that *succeeded*. From creation, a slow
//    request hands its first reader a result that has already spent most of its life; and a failed
//    job that was timestamped could be re-used by a caller arriving between the timestamp and the
//    eviction. An unfinished job has no age at all, so it is always joinable — expiring one would
//    start a duplicate alongside a request that is running perfectly well.
class InFlightCache<K : Any, V>(
    private val scope: CoroutineScope,
    private val maxEntries: Int,
    private val ttlMs: Long,
    // Injected so the TTL is testable without a clock to wait on.
    //
    // elapsedRealtime, not currentTimeMillis or nanoTime. Wall clock lets an NTP correction expire
    // every entry at once, or push one so far into the future it never expires. nanoTime is
    // monotonic but stops while the device is suspended, so a phone asleep in a pocket overnight
    // would wake with entries that had not aged a second — and picking up something that changed
    // while the app sat in the background is usually the point of having a TTL at all.
    // elapsedRealtime is monotonic *and* counts sleep.
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val produce: suspend (K) -> V,
) {

    init {
        // Ahead of the map below, which is sized from this. Zero would make every insertion instantly
        // evictable, silently turning off both the sharing and the caching while still looking like a
        // configured bound.
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    // completedAtMs is written by the job body itself, before the value is returned, so it is
    // visible to anyone who can see the job as completed. Written from invokeOnCompletion instead,
    // there is a window where `isCompleted` is already true but the timestamp is not yet set — a
    // caller landing there reads NEVER, judges the entry stale, and starts a duplicate request.
    private class Entry<V>(val job: Deferred<V>, private val completedAt: AtomicLong) {
        val completedAtMs: Long get() = completedAt.get()
    }

    // Access-ordered, so the bound evicts what has gone longest untouched rather than what was
    // added longest ago. Bounding alone does not bound staleness, which is what `ttlMs` is for:
    // access ordering makes the most-used key the *last* one evicted, so without a TTL the entry
    // most likely to have changed is exactly the one that would never be refreshed.
    // Trimming is done by `trimLocked` rather than removeEldestEntry, which can only ever consider
    // the single eldest entry — and that one may be a request still running, which must not be
    // dropped (dropping does not cancel it, it just hides it, so the next caller for that key starts
    // a duplicate). With removeEldestEntry alone, one long-running request at the head blocks all
    // eviction behind it and completed results pile up without bound.
    private val entries = LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true)

    // Guards `entries`. An access-ordered LinkedHashMap reorders itself on a *read*, so even a
    // lookup mutates it and none of it is thread-safe.
    private val lock = Any()

    suspend fun get(key: K): V {
        val entry = synchronized(lock) {
            val existing = entries[key]?.takeIf { it.isUsable() }
            existing ?: newEntry(key).also {
                entries[key] = it
                trimLocked()
            }
        }
        // Outside the lock: keeps the work off the critical section, and guarantees the entry is
        // installed before its completion handler can run. Starting an already-started job is a
        // no-op, so callers joining an existing entry cost nothing here.
        entry.job.start()
        // Deliberately unguarded. A throw here can mean the shared job failed *or* that this
        // particular awaiter was cancelled because its caller went away — and evicting on the
        // second would discard a request still running for everyone else. Eviction is the job's own
        // business, in newEntry.
        return entry.job.await()
    }

    // Evicts everything, cancelling nothing: an entry can still be awaited by a caller that has not
    // resumed yet. For callers that need to invalidate after a write rather than wait out the TTL.
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    // Drops completed entries, oldest-accessed first, until the bound is met. In-flight entries are
    // skipped rather than stopping the scan, so a slow request cannot pin the cache above its bound.
    // Caller must hold `lock`.
    private fun trimLocked() {
        if (entries.size <= maxEntries) return
        val iterator = entries.entries.iterator()
        var over = entries.size - maxEntries
        while (iterator.hasNext() && over > 0) {
            if (iterator.next().value.job.isCompleted) {
                iterator.remove()
                over--
            }
        }
    }

    private fun Entry<V>.isUsable(): Boolean {
        if (!job.isCompleted) return true
        val completedAt = completedAtMs
        return completedAt != NEVER && nowMs() - completedAt < ttlMs
    }

    // Caller must hold `lock`.
    private fun newEntry(key: K): Entry<V> {
        val completedAt = AtomicLong(NEVER)
        // The timestamp is stamped by the body, so it is set before the job reports completion. Only
        // a successful body reaches it: a throw leaves it at NEVER, which is what keeps a failure
        // from ever being judged fresh — including by a caller arriving before the eviction below.
        val job = scope.async(start = CoroutineStart.LAZY) {
            produce(key).also { completedAt.set(nowMs()) }
        }
        val entry = Entry(job, completedAt)
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                // Removed by identity, so a fresh attempt someone else already installed survives.
                synchronized(lock) { if (entries[key] === entry) entries.remove(key) }
            } else {
                // A completion frees an entry that trimLocked may have had to skip while it ran.
                synchronized(lock) { trimLocked() }
            }
        }
        return entry
    }

    private companion object {
        // Not 0: a test clock, or elapsedRealtime immediately after boot, can legitimately read
        // zero, and a sentinel that collides with a real reading would treat a failed job as fresh.
        const val NEVER = Long.MIN_VALUE
    }
}
