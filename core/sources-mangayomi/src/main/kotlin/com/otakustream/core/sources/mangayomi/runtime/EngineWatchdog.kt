package com.otakustream.core.sources.mangayomi.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.io.Closeable

// An extension that stopped responding, and the method it was last seen in.
//
// An Exception rather than an Error, and deliberately not a CancellationException: the app's source
// error handling catches Exception, and a CancellationException thrown out of a suspend function is
// treated by the coroutines machinery as "this coroutine was cancelled" — the caller's own scope
// would be torn down instead of one source reporting a failure.
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
// stop every later caller queueing behind it: once a call overruns its budget the engine is marked
// wedged, and calls after that fail immediately with a message that says what happened instead of
// hanging. That trades an unbounded silent wedge for a bounded, named failure, which is the best
// available without an interrupt hook.
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
    // SupervisorJob so one wedged call cannot cancel a sibling, and the job is deliberately not
    // cancelled on timeout — a call that does eventually finish has to reach the finally below and
    // clear its own mark.
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    // The method whose call never came back, or null while the engine is answering.
    //
    // Volatile because it is written on whichever thread timed out and read on every caller's
    // thread, with no lock between them — this must not be a per-thread cached value, or the thread
    // that did not observe the wedge would queue behind it exactly as before.
    @Volatile
    private var wedgedBy: String? = null

    val isWedged: Boolean get() = wedgedBy != null

    suspend fun <T> run(method: String, block: () -> T): T {
        // Before the dispatch, so a refusal costs nothing and does not itself join the queue.
        wedgedBy?.let { throw ExtensionWedgedException(it) }
        val work = engineScope.async {
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
                wedgedBy = null
            }
        }
        return try {
            withTimeout(budgetMs) { work.await() }
        } catch (timeout: TimeoutCancellationException) {
            // The work is left running on purpose — see engineScope. Recording that it did not come
            // back is the whole point: the next caller is told rather than made to wait.
            wedgedBy = method
            throw ExtensionWedgedException(method)
        }
    }

    // Drops calls that have not started yet. It cannot stop one already running on the engine
    // thread; nothing can, which is why the wedge is reported rather than repaired.
    override fun close() {
        engineScope.cancel()
    }
}
