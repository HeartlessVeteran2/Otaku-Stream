package com.otakustream.core.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.otakustream.core.database.skip.SkipSegmentType
import com.otakustream.core.player.PlayerUiState
import java.util.Locale

@Composable
fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    onPlayPauseClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onTracksClick: () -> Unit,
    onMarkSegmentStart: () -> Unit,
    onMarkSegmentEnd: (SkipSegmentType) -> Unit,
    modifier: Modifier = Modifier,
    trailingControls: @Composable RowScope.() -> Unit = {},
) {
    val durationMs = uiState.durationMs.coerceAtLeast(0L)
    var draftPositionMs by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.9f))),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val highlightColor = MaterialTheme.colorScheme.tertiary
        Box(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = (draftPositionMs ?: uiState.positionMs.toFloat()).coerceIn(0f, durationMs.toFloat().coerceAtLeast(1f)),
                onValueChange = { draftPositionMs = it },
                onValueChangeFinished = {
                    draftPositionMs?.let { onSeekTo(it.toLong()) }
                    draftPositionMs = null
                },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.tertiary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
            // AniSkip/manual segments drawn as short marks just beneath the track so their
            // position on the timeline is visible before you reach them.
            if (durationMs > 0 && uiState.skipSegments.isNotEmpty()) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val inset = 10.dp.toPx()
                    val trackWidth = (size.width - inset * 2).coerceAtLeast(0f)
                    val y = size.height / 2f + 6.dp.toPx()
                    uiState.skipSegments.forEach { segment ->
                        val startX = inset + trackWidth * (segment.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        val endX = inset + trackWidth * (segment.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        drawLine(
                            color = highlightColor,
                            start = Offset(startX, y),
                            end = Offset(endX, y),
                            strokeWidth = 4.dp.toPx(),
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDurationMs(draftPositionMs?.toLong() ?: uiState.positionMs),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = formatDurationMs(durationMs), color = MaterialTheme.colorScheme.onBackground)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            ) {
                Icon(
                    imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            trailingControls()
            Spacer(modifier = Modifier.weight(1f))
            val markButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)
            if (uiState.isMarkingSegment) {
                TextButton(onClick = { onMarkSegmentEnd(SkipSegmentType.INTRO) }, colors = markButtonColors) {
                    Text("Intro ends here")
                }
                TextButton(onClick = { onMarkSegmentEnd(SkipSegmentType.OUTRO) }, colors = markButtonColors) {
                    Text("Outro ends here")
                }
            } else {
                TextButton(onClick = onMarkSegmentStart, colors = markButtonColors) {
                    Text("Mark intro/outro start")
                }
            }
            IconButton(onClick = onTracksClick) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Tracks",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
