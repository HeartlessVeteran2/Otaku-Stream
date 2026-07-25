package com.otakustream.core.common

import kotlin.coroutines.cancellation.CancellationException

// Like [runCatching], but never swallows coroutine cancellation. Structured concurrency signals
// cancellation by throwing CancellationException inside suspend functions; a bare `runCatching`
// (or `try/catch (e: Exception)`) would catch it and defeat the cancellation, leaving coroutines
// running after their scope is gone. This rethrows it and wraps only genuine failures.
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
