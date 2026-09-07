package com.otakustream.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// A friendly placeholder for "there's nothing here (yet)" states: an icon, a short title, a
// plain-language explanation, and up to two ways out. Shared across every feature module so
// empty/first-run states read consistently.
//
// The second action exists because this app has two separate source ecosystems — Stremio add-ons
// and Mangayomi/AnymeX JavaScript extensions — and an empty state that offers only one of them
// tells the user the other does not exist. That is exactly how it read: the add-on directory was
// one tap from every empty screen and extensions were three taps down a Settings menu, so the
// obvious conclusion was that the app only does Stremio.
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            // No extra top padding. The Column already spaces its children by 8dp, and the extra
            // 8dp here put the primary 16dp below the message while the secondary sat 8dp below the
            // primary — two actions with visibly different gaps around them.
            Button(onClick = onAction) { Text(actionLabel) }
        }
        // Lower emphasis than the primary, and only drawn when both halves are present — a label
        // with no handler would be a button that does nothing, the same trap UiMessages.Message
        // guards against for snackbar actions.
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
        }
    }
}
