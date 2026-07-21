package com.otakustream.core.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SkipNext
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
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.otakustream.core.player.PlayerViewModel
import com.otakustream.core.player.ResizeMode
import com.otakustream.core.player.SubtitleEdgeStyle
import com.otakustream.core.player.SubtitleStyle

private const val PLAYER_SCREEN_TAG = "PlayerScreen"

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoUrl: String,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val subtitleStyle by viewModel.subtitleStyle.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()
    val isInPip by rememberIsInPictureInPictureMode()

    // Subtitle files arrive with wildly inconsistent MIME types across providers (.srt as
    // x-subrip/text-plain/octet-stream; .ass has no registered type at all), so filter broadly
    // and guess the format from the display name instead.
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(PLAYER_SCREEN_TAG, "Could not persist read permission for subtitle uri", it) }
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.onFailure { Log.w(PLAYER_SCREEN_TAG, "Could not resolve display name for subtitle uri", it) }
            .getOrNull() ?: uri.lastPathSegment ?: "Subtitle"
        viewModel.loadSubtitleFile(uri.toString(), displayName)
    }
    var controlsVisible by remember { mutableStateOf(true) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showSubtitleStyleSheet by remember { mutableStateOf(false) }
    // Window brightness as a 0..1 fraction, tracked here so the gesture HUD can show a level ring.
    var brightnessFraction by remember { mutableStateOf(0.5f) }
    var resizeModeOsd by remember { mutableStateOf<String?>(null) }
    var lastResizeMode by remember { mutableStateOf(uiState.resizeMode) }

    LaunchedEffect(uiState.resizeMode) {
        if (uiState.resizeMode != lastResizeMode) {
            lastResizeMode = uiState.resizeMode
            resizeModeOsd = uiState.resizeMode.displayName()
            delay(800)
            resizeModeOsd = null
        }
    }
    var showGestureCoach by remember { mutableStateOf(!viewModel.hasSeenGestureCoach) }

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
            update = { view ->
                view.resizeMode = uiState.resizeMode.toAndroidXResizeMode()
                view.applySubtitleStyle(subtitleStyle)
            },
        )

        if (!isInPip) {
            GestureOverlay(
                modifier = Modifier.fillMaxSize(),
                onSeekBy = viewModel::seekBy,
                onVolumeDeltaChange = viewModel::adjustVolume,
                onBrightnessDeltaChange = { delta ->
                    brightnessFraction = (brightnessFraction + delta).coerceIn(0.01f, 1f)
                    activity?.setScreenBrightness(brightnessFraction)
                },
                // Read the flow's current value so the HUD ring tracks the drag without waiting
                // on a recomposition of the collected uiState.
                volumeLevel = { viewModel.uiState.value.volume },
                brightnessLevel = { brightnessFraction },
                doubleTapSeekMs = uiState.seekDurationMs,
                onTap = { controlsVisible = !controlsVisible },
                onLongPressSpeedStart = viewModel::beginSpeedBoost,
                onLongPressSpeedEnd = viewModel::endSpeedBoost,
            )

            // Brief on-screen label when the scaling mode is cycled (AnymeX shows a toast).
            resizeModeOsd?.let { label ->
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
                ) {
                    Text(
                        text = label,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

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

            uiState.activeSkipSegment?.let { segment ->
                Button(
                    onClick = viewModel::skipActiveSegment,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Text(segment.label)
                }
            }

            if (uiState.isBuffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            uiState.error?.let {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = "Playback failed",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "This video couldn't be played. It may be unavailable or in an unsupported format.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Surface the hidden gestures once, over the first playback.
            if (showGestureCoach) {
                GestureCoachOverlay(
                    onDismiss = {
                        viewModel.markGestureCoachSeen()
                        showGestureCoach = false
                    },
                    modifier = Modifier.align(Alignment.Center),
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
                        if (uiState.hasNext) {
                            IconButton(onClick = viewModel::skipToNext) {
                                Icon(imageVector = Icons.Filled.SkipNext, contentDescription = "Next episode")
                            }
                        }
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
                    onLoadSubtitleFile = { subtitlePicker.launch(arrayOf("*/*")) },
                    onOpenSubtitleStyle = {
                        showTrackSheet = false
                        showSubtitleStyleSheet = true
                    },
                    onAutoSkipChange = viewModel::setAutoSkipEnabled,
                    onSeekDurationChange = viewModel::setSeekDurationMs,
                    onDismiss = { showTrackSheet = false },
                )
            }

            if (showSubtitleStyleSheet) {
                SubtitleStyleSheet(
                    style = subtitleStyle,
                    onStyleChange = viewModel::setSubtitleStyle,
                    onDismiss = { showSubtitleStyleSheet = false },
                )
            }

            if (showEqualizerSheet) {
                EqualizerSheet(
                    selectedPreset = uiState.equalizerPreset,
                    onSelectPreset = viewModel::setEqualizerPreset,
                    volumeBoostMillibels = uiState.volumeBoostMillibels,
                    onSelectVolumeBoost = viewModel::setVolumeBoost,
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

private fun Activity.setScreenBrightness(fraction: Float) {
    val params = window.attributes
    params.screenBrightness = fraction.coerceIn(0.01f, 1f)
    window.attributes = params
}

private fun ResizeMode.displayName(): String = when (this) {
    ResizeMode.FIT -> "Fit"
    ResizeMode.ZOOM -> "Zoom"
    ResizeMode.STRETCH -> "Stretch"
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun ResizeMode.toAndroidXResizeMode(): Int = when (this) {
    ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    ResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerView.applySubtitleStyle(style: SubtitleStyle) {
    val view = subtitleView ?: return
    view.setStyle(
        CaptionStyleCompat(
            style.textColor.argb,
            style.background.argb,
            android.graphics.Color.TRANSPARENT,
            style.edgeStyle.toEdgeType(),
            android.graphics.Color.BLACK,
            null,
        ),
    )
    view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.textScale)
    view.setBottomPaddingFraction(style.bottomMarginFraction)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun SubtitleEdgeStyle.toEdgeType(): Int = when (this) {
    SubtitleEdgeStyle.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    SubtitleEdgeStyle.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    SubtitleEdgeStyle.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    SubtitleEdgeStyle.RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
}
