package com.otakustream.core.sources.mangayomi.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable

// One extension call took longer than the engine's whole budget.
//
// Says "slow", not "broken". The call may still come back — a big catalog page on a weak signal
// does — and if it does, the extension goes on working. Only the refusal below means the thread is
// actually gone.
//
// An Exception rather than an Error, and deliberately not a CancellationException: the app's source
// error handling catches Exception, and a CancellationException thrown out of a suspend function is
// treated by the coroutines machinery as "this coroutine was cancelled" — the caller's own scope
// would be torn down instead of one source reporting a failure.
class ExtensionTimeoutException(method: String, val afterMs: Long) : RuntimeException(
    "This extension took too long running '$method'.",
)

// A call refused because the engine's thread never came back from an earlier one.
//
// This is the one that means stuck. Retrying cannot help — the thread is gone until the extension
// is rebuilt — so the message says reload rather than try again.
class ExtensionWedgedException(method: String) : RuntimeException(
    "This extension stopped responding while running '$method' and has to be reloaded.",
)

// Bounds how long a caller waits on a single-threaded script engine, and remembers when that engine
// did not come back.
//
// QuickJS is strictly single-threaded, so MangayomiRuntime confines its whole engine to one
// executor thread. That makes `while (true) {}` in an extension unrecoverable: the wrapper exposes
// no interrupt hook, so nothing can take the thread back, and every later call to that extension
// queues behind the runaway one — forever, with no error, for the life of the process. Rhino's side
// of the app has had a wall-clock deadline since the same failure was found there; QuickJS never
// got one.
//
// This cannot unblock the wedged thread. What it can do is stop the *caller* waiting on it, and
// stop every later caller queueing behind it: once a call overruns its budget the engine is marked,
// and calls after that fail immediately with a message that says what happened instead of hanging.
// That trades an unbounded silent wedge for a bounded, named failure, which is the best available
// without an interrupt hook.
//
// Not automatic recovery, on purpose. Rebuilding the runtime on a fresh thread is a real mechanism
// — MangayomiSourceFactory.createFromRecord already does exactly that for a preferences change —
// but doing it *automatically* on a wedge would be worse than the failure it treats: an extension
// that wedges deterministically wedges the replacement too, and each attempt parks another daemon
// thread holding another live native context for the life of the process. Recovery stays the user's
// action, which the sources screen already offers as reload or uninstall.
internal class EngineWatchdog(
    dispatcher: CoroutineDispatcher,
    private val budgetMs: Long,
) : Closeable {
    // The engine work is run here rather than as a child of the caller, and that detachment is the
    // mechanism — a timeout around the call site alone does nothing at all.
    //
    // `withTimeout { withContext(engineDispatcher) { block() } }` reads like a bound and is not
    // one: withTimeout is structured, so it cancels its child and then *waits for it to finish*.
    // The child here is ordinary blocking code with no suspension point, so the cancellation is
    // never observed, the child never finishes, and withTimeout waits exactly as long as the wedge
    // does. That version was written first and every wedge test hung on it. HomeViewModel records
    // the same discovery from the other end: "withTimeoutOrNull only ends work that cooperates with
    // cancellation, and the scripted sources do not."
    //
    // Detached, the call becomes `async` on a scope of its own plus `await` at the call site — and
    // await *is* a suspension point, so the timeout can end the waiting without needing to end the
    // work. The work is then left running on the engine thread, which is not a leak that can be
    // avoided: the thread is gone either way, and this is what stops it taking the caller with it.
    //
    // SupervisorJob so one wedged call cannot cancel a sibling.
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    // One lock over both pieces of bookkeeping, and it is the third design here rather than the
    // first because the previous two were wrong in the same way.
    //
    // The question is only ever "when this call's budget expired, was its block still on the engine
    // thread?", and answering it means reading and writing two things together: where this call is
    // in its life, and which call (if any) the engine is currently blamed on. Every lock-free
    // version wrote them with two separate compare-and-swaps, and every one left an interleaving
    // where a token is installed for a call that has already finished — after which nothing will
    // ever clear it and the extension refuses every later call for the life of the process:
    //
    //  - a "did it start" flag stayed true after the block ended, so a call finishing in the same
    //    instant as its timeout still looked eligible;
    //  - a state CAS fixed that and left a smaller one: the timeout can win RUNNING -> WEDGED and
    //    then be preempted before installing its token, the engine thread finishes and finds no
    //    token to clear, and the timeout installs one afterwards.
    //
    // A lock ends the category rather than the instance. Contention is nil — these run a handful of
    // times per extension call, never on a hot path — and the critical sections are three
    // assignments. That is a better trade than another round of being clever about it.
    private val lock = Any()

    // Both guarded by `lock`; never read or written outside a synchronized block.
    private var wedgedBy: Wedge? = null

    val isWedged: Boolean get() = synchronized(lock) { wedgedBy != null }

    private class Wedge(val method: String)

    // Where one call is in its life on the engine thread.
    private enum class CallState { QUEUED, RUNNING, WEDGED, FINISHED }

    // Distinguishes "the block returned null" from "the budget expired", which withTimeoutOrNull's
    // own null cannot: invoke() legitimately returns a null String.
    private class Completed<T>(val value: T)

    suspend fun <T> run(method: String, block: () -> T): T {
        // Before the dispatch, so a refusal costs nothing and does not itself join the queue.
        synchronized(lock) { wedgedBy }?.let { throw ExtensionWedgedException(it.method) }

        val mine = Wedge(method)
        var state = CallState.QUEUED
        val work = engineScope.async {
            synchronized(lock) { if (state == CallState.QUEUED) state = CallState.RUNNING }
            try {
                block()
            } finally {
                // The engine thread reached the end of the call, so it is available again —
                // whether the call returned a value or threw.
                //
                // This is what tells a wedged engine from a merely slow one. A call that overran
                // its budget but does eventually finish clears its own mark on the way out, and the
                // extension goes back to working; only one that never returns leaves the mark
                // standing. Without it the first slow catalog page on a bad connection would
                // disable the extension permanently, which is the failure this class exists to
                // prevent, reintroduced from the other side.
                //
                // Both together, under the lock, so a timeout cannot land between them and blame
                // a call that has already finished.
                //
                // Only its own mark: a call that finishes late must not clear the mark left by a
                // different call that is still gone.
                synchronized(lock) {
                    state = CallState.FINISHED
                    if (wedgedBy === mine) wedgedBy = null
                }
            }
        }

        // withTimeoutOrNull rather than a catch on TimeoutCancellationException, and the difference
        // is not a style one.
        //
        // Every caller of an extension already wraps it in a deadline of its own —
        // HomeViewModel, CatalogViewModel and MediaDetailsViewModel all use
        // withTimeoutOrNull(15_000). A bare `catch (e: TimeoutCancellationException)` catches
        // *theirs* as readily as this one's, so every source merely slower than fifteen seconds
        // would have been marked wedged and told to reload — and their withTimeoutOrNull would
        // never see its own exception come back, so the rail would report an error rather than a
        // timeout. False positives on slow sources are far commoner than real wedges; that version
        // would have been worse than the bug it fixes.
        //
        // withTimeoutOrNull checks `e.coroutine === coroutine` before swallowing, which is exactly
        // the identity test needed: null means *this* budget expired, and anything else is someone
        // else's cancellation, passed through untouched.
        val completed = try {
            withTimeoutOrNull(budgetMs) { Completed(work.await()) }
        } catch (cancellation: CancellationException) {
            // The caller gave up — its own deadline, or a newer search superseding this one. Drop
            // the work if it has not started; one already on the engine thread cannot be stopped,
            // and cancelling it changes nothing except that its finally still clears the mark.
            // Without this, a screen that times out every fifteen seconds leaves a queue of stale
            // calls that later run and delay the one the user is waiting on.
            work.cancel()
            throw cancellation
        }

        if (completed == null) {
            // Only a call still *on* the engine thread may say the engine is gone, and the test and
            // the mark happen together so nothing can change underneath them. RUNNING excludes a
            // call still QUEUED — a symptom of someone else holding the thread, not a report about
            // it — and one already FINISHED, whose cleanup has run and will not run again to clear
            // anything. The null check leaves an earlier call's mark standing: it names the call
            // that actually has the thread.
            synchronized(lock) {
                if (state == CallState.RUNNING) {
                    state = CallState.WEDGED
                    if (wedgedBy == null) wedgedBy = mine
                }
            }
            // Same reasoning as caller cancellation — if this call never got the thread, it is
            // stale now and must not run later.
            work.cancel()
            throw ExtensionTimeoutException(method, budgetMs)
        }
        return completed.value
    }

    // Drops calls that have not started yet. One already running cannot be stopped — that is the
    // wedge this cannot repair — which is why the wedge is reported rather than fixed.
    override fun close() {
        engineScope.cancel()
    }
}
