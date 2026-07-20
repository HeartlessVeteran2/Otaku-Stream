package com.otakustream.core.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// A one-time "here's what you can do" scrim over the player, surfacing the otherwise-invisible
// gestures. Shown on first playback and dismissed with "Got it" (persisted so it never nags again).
@Composable
fun GestureCoachOverlay(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Gestures", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        GestureLine("Drag left / right — seek back or forward")
        GestureLine("Double-tap a side — jump 10 seconds")
        GestureLine("Drag up / down on the left — brightness")
        GestureLine("Drag up / down on the right — volume")
        GestureLine("Press and hold — 2× speed")
        GestureLine("Tap — show or hide the controls")
        Button(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) { Text("Got it") }
    }
}

@Composable
private fun GestureLine(text: String) {
    Text(
        text = text,
        color = Color.White,
        textAlign = TextAlign.Center,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    )
}
