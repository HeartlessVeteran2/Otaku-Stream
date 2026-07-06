package com.otakustream.core.player.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

private const val SEEK_MS_PER_PX = 100L
private const val VOLUME_CHANGE_PER_PX = 0.005f
private const val BRIGHTNESS_CHANGE_PER_PX = 0.005f
private const val AXIS_LOCK_THRESHOLD_PX = 8f

private enum class DragAxis { HORIZONTAL, VERTICAL }

// One touch-down zone drives one gesture: the dominant movement direction of the first
// few pixels locks the gesture as either a seek (horizontal) or a volume/brightness
// adjustment (vertical, split by which half of the screen the touch started in).
@Composable
fun GestureOverlay(
    modifier: Modifier = Modifier,
    onSeekBy: (deltaMs: Long) -> Unit,
    onVolumeDeltaChange: (delta: Float) -> Unit,
    onBrightnessDeltaChange: (delta: Float) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startedOnLeftHalf = down.position.x < size.width / 2f
                    var axis: DragAxis? = null

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
                                onSeekBy((change.positionChange().x * SEEK_MS_PER_PX).toLong())
                                change.consume()
                            }
                            DragAxis.VERTICAL -> {
                                val delta = -change.positionChange().y
                                if (startedOnLeftHalf) {
                                    onBrightnessDeltaChange(delta * BRIGHTNESS_CHANGE_PER_PX)
                                } else {
                                    onVolumeDeltaChange(delta * VOLUME_CHANGE_PER_PX)
                                }
                                change.consume()
                            }
                            null -> Unit
                        }
                    }
                }
            },
    )
}
