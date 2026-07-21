package com.otakustream.core.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SEEK_MS_PER_PX = 100L
private const val VOLUME_CHANGE_PER_PX = 0.005f
private const val BRIGHTNESS_CHANGE_PER_PX = 0.005f
private const val AXIS_LOCK_THRESHOLD_PX = 8f
private const val PULSE_DURATION_MS = 500L

private enum class DragAxis { HORIZONTAL, VERTICAL }

// A transient on-screen indicator for the active gesture: a seek offset, or a volume/brightness
// level ring — mirroring AnymeX's circular gesture indicators.
private sealed interface GestureHud {
    data class Seek(val totalMs: Long) : GestureHud
    data class Level(val icon: ImageVector, val fraction: Float) : GestureHud
}

// One touch-down zone drives one gesture: the dominant movement direction of the first
// few pixels locks the gesture as either a seek (horizontal) or a volume/brightness
// adjustment (vertical, split by which half of the screen the touch started in).
@Composable
fun GestureOverlay(
    modifier: Modifier = Modifier,
    onSeekBy: (deltaMs: Long) -> Unit,
    onVolumeDeltaChange: (delta: Float) -> Unit,
    onBrightnessDeltaChange: (delta: Float) -> Unit,
    volumeLevel: () -> Float = { 0f },
    brightnessLevel: () -> Float = { 0f },
    doubleTapSeekMs: Long = 10_000L,
    onTap: () -> Unit = {},
    onLongPressSpeedStart: () -> Unit = {},
    onLongPressSpeedEnd: () -> Unit = {},
) {
    var pulseText by remember { mutableStateOf<String?>(null) }
    // Present only while a drag is in progress; cleared when the finger lifts.
    var hud by remember { mutableStateOf<GestureHud?>(null) }
    LaunchedEffect(pulseText) {
        if (pulseText != null) {
            delay(PULSE_DURATION_MS)
            pulseText = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startedOnLeftHalf = down.position.x < size.width / 2f
                    var axis: DragAxis? = null
                    var seekAccumMs = 0L

                    fun resolveAxis(change: PointerInputChange): DragAxis? {
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (abs(dx) < AXIS_LOCK_THRESHOLD_PX && abs(dy) < AXIS_LOCK_THRESHOLD_PX) return null
                        return if (abs(dx) > abs(dy)) DragAxis.HORIZONTAL else DragAxis.VERTICAL
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        if (axis == null) {
                            axis = resolveAxis(change)
                        }

                        when (axis) {
                            DragAxis.HORIZONTAL -> {
                                val deltaMs = (change.positionChange().x * SEEK_MS_PER_PX).toLong()
                                onSeekBy(deltaMs)
                                seekAccumMs += deltaMs
                                hud = GestureHud.Seek(seekAccumMs)
                                change.consume()
                            }
                            DragAxis.VERTICAL -> {
                                val delta = -change.positionChange().y
                                if (startedOnLeftHalf) {
                                    onBrightnessDeltaChange(delta * BRIGHTNESS_CHANGE_PER_PX)
                                    hud = GestureHud.Level(Icons.Filled.BrightnessHigh, brightnessLevel())
                                } else {
                                    onVolumeDeltaChange(delta * VOLUME_CHANGE_PER_PX)
                                    hud = GestureHud.Level(Icons.Filled.VolumeUp, volumeLevel())
                                }
                                change.consume()
                            }
                            null -> Unit
                        }
                    }

                    hud = null
                    if (axis == null) {
                        onTap()
                    }
                }
            }
            // A second, independent pointerInput block — Compose gives each its own
            // gesture-recognition scope, so double-tap/long-press don't need to be threaded
            // into the drag/tap state machine above. Keyed on the seek step so a settings
            // change re-registers with the new value.
            .pointerInput(doubleTapSeekMs) {
                var speedBoostActive = false
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) {
                            onSeekBy(-doubleTapSeekMs)
                            pulseText = "-${doubleTapSeekMs / 1000}s"
                        } else {
                            onSeekBy(doubleTapSeekMs)
                            pulseText = "+${doubleTapSeekMs / 1000}s"
                        }
                    },
                    onLongPress = {
                        speedBoostActive = true
                        onLongPressSpeedStart()
                        pulseText = "2x"
                    },
                    onPress = {
                        // A cancelled gesture scope (screen closed, overlay recomposed/hidden)
                        // throws out of tryAwaitRelease() — finally ensures the boost is still
                        // ended so playback doesn't get stuck at 2x.
                        try {
                            tryAwaitRelease()
                        } finally {
                            if (speedBoostActive) {
                                speedBoostActive = false
                                onLongPressSpeedEnd()
                                if (pulseText == "2x") pulseText = null
                            }
                        }
                    },
                )
            },
    ) {
        hud?.let { current ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                when (current) {
                    is GestureHud.Seek -> Text(
                        text = formatSignedSeconds(current.totalMs),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    is GestureHud.Level -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Icon(imageVector = current.icon, contentDescription = null)
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { current.fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                text = "${(current.fraction.coerceIn(0f, 1f) * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }

        pulseText?.let { text ->
            AnimatedVisibility(visible = true, modifier = Modifier.align(Alignment.Center)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)) {
                    Text(text = text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

private fun formatSignedSeconds(ms: Long): String {
    val seconds = ms / 1000
    val sign = if (seconds >= 0) "+" else "-"
    return "$sign${abs(seconds)}s"
}
