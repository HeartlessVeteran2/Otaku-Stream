package com.otakustream.core.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.otakustream.core.player.PlayerViewModel
import com.otakustream.core.player.ResizeMode

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoUrl: String,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = LocalContext.current.findActivity()
    val isInPip by rememberIsInPictureInPictureMode()
    var controlsVisible by remember { mutableStateOf(true) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(videoUrl) {
        viewModel.play(videoUrl)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // Entering PiP also triggers ON_PAUSE — only pause playback when the
                    // activity is actually backgrounded, not when it's still visible in PiP.
                    if (activity?.isInPictureInPictureMode != true) {
                        viewModel.controller.pause()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    player = viewModel.controller.player
                }
            },
            update = { it.resizeMode = uiState.resizeMode.toAndroidXResizeMode() },
        )

        if (!isInPip) {
            GestureOverlay(
                modifier = Modifier.fillMaxSize(),
                onSeekBy = viewModel::seekBy,
                onVolumeDeltaChange = viewModel::adjustVolume,
                onBrightnessDeltaChange = { delta -> activity?.adjustScreenBrightnessBy(delta) },
                onTap = { controlsVisible = !controlsVisible },
                onLongPressSpeedStart = viewModel::beginSpeedBoost,
                onLongPressSpeedEnd = viewModel::endSpeedBoost,
            )

            if (uiState.statsOverlayVisible) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Codec: ${uiState.codecName ?: "?"}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Resolution: ${uiState.videoWidth}x${uiState.videoHeight}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Bitrate: ${uiState.videoBitrateBps / 1000} kbps", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Text("Dropped frames: ${uiState.droppedFrameCount}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            uiState.activeSkipSegment?.let {
                Button(
                    onClick = viewModel::skipActiveSegment,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Text("Skip")
                }
            }

            if (uiState.isBuffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            uiState.error?.let { message ->
                Text(
                    text = message,
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }

            if (controlsVisible) {
                PlayerControlsOverlay(
                    uiState = uiState,
                    onPlayPauseClick = viewModel::togglePlayPause,
                    onSeekTo = viewModel::seekTo,
                    onTracksClick = { showTrackSheet = true },
                    onMarkSegmentStart = viewModel::markSegmentStart,
                    onMarkSegmentEnd = viewModel::markSegmentEnd,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    trailingControls = {
                        SpeedPickerMenu(currentSpeed = uiState.playbackSpeed, onSpeedSelected = viewModel::setPlaybackSpeed)
                        IconButton(onClick = viewModel::cycleResizeMode) {
                            Icon(imageVector = Icons.Filled.AspectRatio, contentDescription = "Change video scaling")
                        }
                        IconButton(onClick = viewModel::toggleStatsOverlay) {
                            Icon(imageVector = Icons.Filled.Info, contentDescription = "Toggle stats overlay")
                        }
                        IconButton(onClick = { showEqualizerSheet = true }) {
                            Icon(imageVector = Icons.Filled.Equalizer, contentDescription = "Audio equalizer")
                        }
                    },
                )
            }

            if (showTrackSheet) {
                TrackSelectionSheet(
                    uiState = uiState,
                    onSelectAudio = viewModel::selectAudioTrack,
                    onSelectSubtitle = viewModel::selectSubtitleTrack,
                    onSelectQuality = viewModel::selectVideoQuality,
                    onSubtitlesEnabledChange = viewModel::setSubtitlesEnabled,
                    onDismiss = { showTrackSheet = false },
                )
            }

            if (showEqualizerSheet) {
                EqualizerSheet(
                    selectedPreset = uiState.equalizerPreset,
                    onSelectPreset = viewModel::setEqualizerPreset,
                    onDismiss = { showEqualizerSheet = false },
                )
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.adjustScreenBrightnessBy(delta: Float) {
    val params = window.attributes
    val current = if (params.screenBrightness < 0f) 0.5f else params.screenBrightness
    params.screenBrightness = (current + delta).coerceIn(0.01f, 1f)
    window.attributes = params
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun ResizeMode.toAndroidXResizeMode(): Int = when (this) {
    ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    ResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}
