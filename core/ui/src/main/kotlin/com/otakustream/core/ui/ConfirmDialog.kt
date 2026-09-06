package com.otakustream.core.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

// "Are you sure" for the things that cannot be taken back.
//
// The app had ten destructive actions and four dialogs, and the gap was not where you would guess:
// clearing watch history asked, while deleting a download's bytes, clearing gigabytes of torrent
// cache and signing out of AniList (which drops the Keystore token) all fired on a single tap. The
// add-on Remove was a plain TextButton in a dense row beside a Switch — an easy mis-tap that
// silently dropped a source and everything it was feeding into Home.
//
// The rule this encodes: if the change can be restored exactly, it happens immediately and offers
// Undo on the snackbar (see UiMessages.showUndoable). If it cannot — bytes deleted, a token
// dropped, a cache emptied — it asks first, because "Undo" there would be a lie.
@Composable
fun ConfirmDialog(
    title: String,
    // What will actually happen, in plain terms. Worth stating rather than "This cannot be undone":
    // a dialog that does not say what it is about to delete is one people learn to tap through.
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            ) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
