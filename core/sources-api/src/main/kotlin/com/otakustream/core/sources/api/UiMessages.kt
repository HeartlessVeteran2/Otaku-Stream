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
    ) {
        init {
            // The two are meaningless apart: an action with no label draws no button, so the
            // lambda can never run, and a label with no action draws a button that does nothing.
            // Both are silent failures at a call site that believes it offered an undo.
            //
            // Spelled out as two cases rather than as `actionLabel.isNullOrBlank() == (action ==
            // null)`, which let a blank-but-present label through alongside a null action: both
            // sides were true, so the check passed, and the host then saw a non-null label and drew
            // the dead button this is here to prevent.
            require((action == null && actionLabel == null) || (action != null && !actionLabel.isNullOrBlank())) {
                "A snackbar action needs a label and a label needs an action"
            }
        }
    }

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

    // Delivery happens under the lock, deliberately.
    //
    // The earlier version read the sink into a local and invoked it outside any lock, which left a
    // window: the host could unregister between the read and the call, and the message would be
    // handed to a sink whose scope was already cancelled — the confirmation simply never appeared.
    // Holding the lock across the call makes delivery and unregistration mutually exclusive, so a
    // message is either delivered to a live sink or queued for the next one, never lost between the
    // two.
    //
    // Safe to call arbitrary code under this lock because the sink is ours and does one thing:
    // hands the message to a coroutine scope and returns. It must not block, and it must not call
    // back into UiMessages from another thread and wait on it.
    fun show(message: Message) {
        if (message.text.isBlank()) return
        synchronized(lock) {
            val current = sink
            if (current == null) {
                pending += message
                return
            }
            current(message)
        }
    }

    // Registered once by the app's UI host. Replays anything that queued up beforehand.
    //
    // The replay is inside the lock for the same reason: outside it, a message shown concurrently
    // would take the lock, find the new sink, and arrive ahead of confirmations that were waiting
    // for a host before it did.
    fun setSink(newSink: ((Message) -> Unit)?) {
        synchronized(lock) {
            sink = newSink
            val replay = pending.toList()
            pending.clear()
            if (newSink != null) replay.forEach(newSink)
        }
    }
}
