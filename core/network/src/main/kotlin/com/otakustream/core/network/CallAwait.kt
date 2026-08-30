package com.otakustream.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Awaits an OkHttp call in a way that cancelling the coroutine actually cancels the request.
//
// The shape this replaces was `withContext(Dispatchers.IO) { call.execute() }`, which is not the
// same thing and reads as though it is. It moves the blocking call off the caller's thread, but a
// blocking call cannot be interrupted by coroutine cancellation: cancelling marks the coroutine
// cancelled and returns immediately, while `execute()` carries on underneath until the response
// arrives or the socket times out. Nobody reads the result. The thread and the connection are held
// anyway.
//
// Where that showed was AniList search. Its collectLatest cancels the previous query on every
// keystroke, but the cancelled request kept running, so typing during a slow search queued requests
// the app had already stopped caring about — and the new query's debounce started late behind them.
// The same applies to a catalog fan-out abandoned by navigating away.
//
// `enqueue` hands the request to OkHttp's dispatcher, which already runs it off this thread, so the
// explicit Dispatchers.IO hop callers used to write disappears along with the blocking.
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    // Registered before enqueue, so a coroutine already cancelled when it reaches here cancels the
    // call rather than leaving it running with nothing waiting on it.
    continuation.invokeOnCancellation {
        // Cancelling a completed call is a no-op in OkHttp, and cancel() is documented not to
        // throw — but this runs on whichever thread cancelled the coroutine, and an exception
        // escaping a cancellation handler would surface far from anything that could explain it.
        runCatching { cancel() }
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // resume, not resumeWith(success): if the continuation was already cancelled this is a
            // no-op and the response body would leak its connection. Closing it on that path is the
            // caller's contract for a cancelled await — see below.
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                // Nothing will read it, and an unclosed body holds its connection out of the pool
                // until the finalizer runs. This is the one path where the body has no owner.
                runCatching { response.close() }
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            // A cancelled call reports failure too. Resuming a cancelled continuation is a no-op,
            // so the guard is about not doing pointless work rather than correctness.
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
}
