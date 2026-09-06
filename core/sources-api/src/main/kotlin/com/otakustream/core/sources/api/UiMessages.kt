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

    // A confirmation, optionally with something to undo it.
    //
    // The action is a suspend lambda and is invoked by whoever hosts the snackbar, on that host's
    // scope — deliberately not the caller's. A ViewModel that removes something can be cleared the
    // moment the user leaves the screen, and an undo running on its cancelled scope would do
    // nothing at all while the snackbar said it had worked.
    class Message(
        val text: String,
        val actionLabel: String? = null,
        val action: (suspend () -> Unit)? = null,
    )

    private val lock = Any()

    @Volatile
    private var sink: ((Message) -> Unit)? = null

    // Messages emitted before a sink exists (e.g. a bootstrapper finishing during startup) are
    // held so the confirmation isn't silently dropped.
    private val pending = mutableListOf<Message>()

    fun show(message: String) = show(Message(message))

    // A confirmation the user can take back. Only for changes that can be restored exactly —
    // removing a row, say. Deleting a file's bytes cannot be undone, and offering "Undo" for it
    // would be a lie; those ask first instead.
    fun showUndoable(text: String, actionLabel: String = "Undo", action: suspend () -> Unit) =
        show(Message(text, actionLabel, action))

    fun show(message: Message) {
        if (message.text.isBlank()) return
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
    fun setSink(newSink: ((Message) -> Unit)?) {
        val replay: List<Message>
        synchronized(lock) {
            sink = newSink
            replay = pending.toList()
            pending.clear()
        }
        if (newSink != null) replay.forEach(newSink)
    }
}
