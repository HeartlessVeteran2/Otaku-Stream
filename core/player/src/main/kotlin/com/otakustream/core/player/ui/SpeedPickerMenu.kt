package com.otakustream.core.player.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// Shared with the Playback settings screen — see PLAYBACK_SPEED_OPTIONS.
private val SPEED_OPTIONS = com.otakustream.core.player.PLAYBACK_SPEED_OPTIONS

@Composable
fun SpeedPickerMenu(currentSpeed: Float, onSpeedSelected: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = true }) {
        Text(text = "${formatSpeed(currentSpeed)}x", color = Color.White)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        SPEED_OPTIONS.forEach { speed ->
            DropdownMenuItem(
                text = { Text("${formatSpeed(speed)}x") },
                onClick = {
                    onSpeedSelected(speed)
                    expanded = false
                },
            )
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
