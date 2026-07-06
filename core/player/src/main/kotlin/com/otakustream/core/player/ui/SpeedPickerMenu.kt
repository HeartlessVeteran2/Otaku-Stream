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

private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

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
