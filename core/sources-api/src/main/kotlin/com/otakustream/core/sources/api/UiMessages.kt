package com.otakustream.core.sources.api

// App-wide one-shot user messages ("Add-on installed", "Signed out") shown as a snackbar by
// whoever hosts the UI. Feature ViewModels call [show] without knowing anything about Compose or
// which screen is on top; the app layer registers a single sink at startup.
//
// Follows the PendingPlayback / PlaybackCompletion hand-off pattern — a plain callback registry
// rather than a Flow — because this module stays free of kotlinx-coroutines by design.
//
// Errors deliberately do NOT go through here: those stay inline next to the thing that failed,
// with a Retry affordance. This is for confirmations, which have nowhere else to live.
object UiMessages {

    private val lock = Any()

    @Volatile
    private var sink: ((String) -> Unit)? = null

    // Messages emitted before a sink exists (e.g. a bootstrapper finishing during startup) are
    // held so the confirmation isn't silently dropped.
    private val pending = mutableListOf<String>()

    fun show(message: String) {
        if (message.isBlank()) return
        val current = sink
        if (current != null) {
            current(message)
            return
        }
        synchronized(lock) {
            if (sink == null) {
                pending += message
                return
            }
        }
        sink?.invoke(message)
    }

    // Registered once by the app's UI host. Replays anything that queued up beforehand.
    fun setSink(newSink: ((String) -> Unit)?) {
        val replay: List<String>
        synchronized(lock) {
            sink = newSink
            replay = pending.toList()
            pending.clear()
        }
        if (newSink != null) replay.forEach(newSink)
    }
}
