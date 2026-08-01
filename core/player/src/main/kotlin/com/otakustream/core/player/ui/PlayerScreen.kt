package com.otakustream.core.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.otakustream.core.player.PlayerViewModel
import com.otakustream.core.player.ResizeMode
import com.otakustream.core.player.SubtitleEdgeStyle
import com.otakustream.core.player.SubtitleStyle

private const val PLAYER_SCREEN_TAG = "PlayerScreen"

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoUrl: String,
    // True when an installed source chose this url. Carried on the navigation route rather than
    // inferred, so it survives the app being killed and restored.
    fromSource: Boolean = false,
    onBack: () -> Unit = {},
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
    // Last values pushed into the PlayerView, so AndroidView's update lambda can skip no-op work.
    var lastAppliedResizeMode by remember { mutableStateOf<Int?>(null) }
    var lastAppliedSubtitleStyle by remember { mutableStateOf<SubtitleStyle?>(null) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }
    var showSubtitleStyleSheet by remember { mutableStateOf(false) }
    // Window brightness as a 0..1 fraction, tracked here so the gesture HUD can show a level ring.
    //
    // Null until a baseline is known. It used to start at a fabricated 0.5f, which meant the first
    // swipe did not *adjust* brightness, it jumped — so watching in a dark room at 5%, the smallest
    // nudge upward flooded the screen. Null instead leaves the system's own brightness alone until
    // something real is known: the measured value below, or failing that a read taken at the moment
    // of the first drag.
    var brightnessFraction by remember { mutableStateOf<Float?>(null) }
    // Drag distance that arrived before the baseline did. A drag in that window can't be applied
    // yet and mustn't block on the read, so it is banked here and settled below.
    var bankedBrightnessDelta by remember { mutableFloatStateOf(0f) }
    // Read off the main thread: this reaches a ContentProvider, and doing that during composition
    // put a provider round-trip on the frame the user is waiting for video on — the same mistake
    // this pass is removing elsewhere.
    LaunchedEffect(activity) {
        val measured = withContext(Dispatchers.IO) { activity?.currentScreenBrightness() }
        // Only if the user hasn't already established a baseline — a slow read must never land on
        // top of a value they set themselves.
        if (brightnessFraction == null) {
            // UNKNOWN_BRIGHTNESS only if the device reports nothing at all; otherwise this is a real
            // measurement. Any drag that happened while the read was in flight is applied on top of
            // it now, so the gesture is honoured rather than swallowed.
            val seed = measured ?: UNKNOWN_BRIGHTNESS
            val settled = (seed + bankedBrightnessDelta).coerceIn(MIN_BRIGHTNESS, 1f)
            brightnessFraction = settled
            if (bankedBrightnessDelta != 0f) {
                bankedBrightnessDelta = 0f
                activity?.setScreenBrightness(settled)
            }
        }
    }
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
    // Notices are longer than the resize label and worth reading, so they linger — but they still
    // clear themselves: a message about casting must not sit over the video for the rest of the film.
    LaunchedEffect(uiState.notice) {
        if (uiState.notice != null) {
            delay(4_000)
            viewModel.clearNotice()
        }
    }

    // Driven by the flow rather than a one-shot read, because the answer arrives from disk a moment
    // after this composes. Read once into remembered state, a first-time user would never see the
    // coach at all: the "not seen" value would land after this had already decided not to show it.
    // Null means still loading, which shows nothing — better than a flash of an overlay for someone
    // who has dismissed it before.
    val hasSeenGestureCoach by viewModel.hasSeenGestureCoach.collectAsState()
    var coachDismissed by remember { mutableStateOf(false) }
    val showGestureCoach = hasSeenGestureCoach == false && !coachDismissed

    // Android 13+ needs a runtime grant before the media-playback notification can show. Ask once
    // when playback first starts; playback itself never waits on the result.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not, playback proceeds — the notification simply won't show if denied */ }

    // The playback this screen started. Held so its cleanup stops that video and no other — see
    // PlayerController.stop.
    var playbackSession by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(videoUrl) {
        viewModel.play(videoUrl, fromSource)
        playbackSession = viewModel.controller.currentPlaybackSession
        // Bring the Cast session listener online so the route button reflects device availability.
        viewModel.warmUpCast()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val alreadyGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!alreadyGranted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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

    // Leaving the player ends the playback. Backing out used to only remove the UI: the episode kept
    // playing underneath the details screen, the media notification stayed up, and a torrent carried
    // on streaming — with the controls that could have stopped any of it now unreachable.
    //
    // This composable is only disposed when the player destination is actually left. Backgrounding
    // the app does not dispose it (the destination is still on the back stack), and neither does
    // rotating or entering PiP — the activity declares configChanges and stays on this route — so
    // background audio and PiP are unaffected.
    //
    // Scoped to the session this screen started rather than "stop whatever is playing", because
    // disposal is not the same as owning the playback. Navigating from one video straight to another
    // composes the incoming screen and disposes the outgoing one in an order Compose does not
    // promise; an unconditional stop would sometimes kill the video that had just started. The
    // controller refuses a stop for a session it has already moved past, so both orderings end the
    // same way.
    DisposableEffect(Unit) {
        onDispose { playbackSession?.let { viewModel.controller.stop(it) } }
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
            // Only touch the native view when the values it renders actually changed: this lambda
            // runs on every recomposition, and rebuilding a CaptionStyleCompat + re-issuing the
            // native setters each time is pure waste when neither input moved.
            update = { view ->
                val resize = uiState.resizeMode.toAndroidXResizeMode()
                if (lastAppliedResizeMode != resize) {
                    lastAppliedResizeMode = resize
                    view.resizeMode = resize
                }
                if (lastAppliedSubtitleStyle != subtitleStyle) {
                    lastAppliedSubtitleStyle = subtitleStyle
                    view.applySubtitleStyle(subtitleStyle)
                }
            },
            // Detach before the view is discarded. The ExoPlayer is a process-lifetime @Singleton and
            // keeps a reference to every PlayerView attached to it, so without this each visit to the
            // player left another dead view — and the Activity context it holds — alive for as long
            // as the app ran.
            onRelease = { view -> view.player = null },
        )

        if (!isInPip) {
            GestureOverlay(
                modifier = Modifier.fillMaxSize(),
                onSeekBy = viewModel::seekBy,
                onVolumeDeltaChange = viewModel::adjustVolume,
                onBrightnessDeltaChange = { delta ->
                    val base = brightnessFraction
                    if (base == null) {
                        // No baseline yet. Bank the movement instead of reading the setting here —
                        // that read hits a ContentProvider, and this runs in a pointer callback, so
                        // doing it inline would stall the very drag it is meant to serve. The
                        // LaunchedEffect above applies this the moment the value lands.
                        bankedBrightnessDelta += delta
                    } else {
                        val next = (base + delta).coerceIn(MIN_BRIGHTNESS, 1f)
                        brightnessFraction = next
                        activity?.setScreenBrightness(next)
                    }
                },
                // Read the flow's current value so the HUD ring tracks the drag without waiting
                // on a recomposition of the collected uiState.
                volumeLevel = { viewModel.uiState.value.volume },
                brightnessLevel = { brightnessFraction ?: UNKNOWN_BRIGHTNESS },
                doubleTapSeekMs = uiState.seekDurationMs,
                onTap = { controlsVisible = !controlsVisible },
                onLongPressSpeedStart = viewModel::beginSpeedBoost,
                onLongPressSpeedEnd = viewModel::endSpeedBoost,
            )

            // Brief on-screen label when the scaling mode is cycled (AnymeX shows a toast).
            // Also carries transient notices from the controller — deliberately not the error
            // overlay, which stops playback dead behind a "Playback failed" panel: a notice is
            // something the user should know while the video keeps playing.
            (resizeModeOsd ?: uiState.notice)?.let { label ->
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        // play() clears the error and re-prepares the same URL from the last position.
                        Button(onClick = { viewModel.play(videoUrl) }) { Text("Retry") }
                        OutlinedButton(onClick = onBack) { Text("Go back") }
                    }
                }
            }

            // Google Cast button. setUpMediaRouteButton throws when Cast / Play Services is
            // unavailable, so it's wrapped — on such devices the button simply doesn't appear.
            if (controlsVisible && !isInPip) {
                AndroidView(
                    factory = { ctx ->
                        // MediaRouteButton inflates the Cast chooser/controller dialogs, which
                        // require an AppCompat-descended theme. The app theme is framework Material,
                        // so wrap the button's context in an AppCompat theme — otherwise tapping the
                        // button throws "requires Theme.AppCompat" at dialog-inflation time.
                        val themedContext = ContextThemeWrapper(
                            ctx,
                            androidx.appcompat.R.style.Theme_AppCompat_DayNight_NoActionBar,
                        )
                        MediaRouteButton(themedContext).apply {
                            runCatching {
                                CastButtonFactory.setUpMediaRouteButton(themedContext, this)
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                )
            }

            // While casting, the local surface is black — say what's happening instead.
            if (uiState.isCasting) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cast,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = "Casting to your TV",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Surface the hidden gestures once, over the first playback.
            if (showGestureCoach) {
                GestureCoachOverlay(
                    onDismiss = {
                        viewModel.markGestureCoachSeen()
                        coachDismissed = true
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (controlsVisible) {
                PlayerControlsOverlay(
                    uiState = uiState,
                    progressFlow = viewModel.progress,
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

// What the screen is currently showing, as a 0..1 fraction, so a brightness gesture starts from
// there instead of from a guess. Approximate under adaptive brightness (see below). Null only when
// the setting cannot be read at all.
//
// Touches a ContentProvider, so it must not run during composition — see how PlayerScreen calls it.
private fun Activity.currentScreenBrightness(): Float? {
    // An override this window has already set — the user used the gesture earlier in this playback.
    val override = window.attributes.screenBrightness
    if (override >= 0f) return override.coerceIn(MIN_BRIGHTNESS, 1f)
    // Otherwise the window follows the system, and SCREEN_BRIGHTNESS is the manual slider value.
    //
    // Read even when adaptive brightness is on, where it is only an approximation of what is
    // actually on screen — the ambient-light adjustment is applied on top of it and is not readable
    // from here. Refusing it was worse: the caller's only remaining option was the fabricated 0.5f
    // this whole path exists to eliminate, so on a dark screen with adaptive on — the exact case
    // where a jump hurts most — the first swipe still flooded it. An approximate baseline is off by
    // the ambient adjustment; a constant is off by however far the user is from the middle.
    //
    // Settings.System reports 0..255. Not universally true — a few devices use a different maximum
    // — but it is far closer than a constant, and the next swipe corrects it.
    return runCatching {
        val value = android.provider.Settings.System.getInt(
            contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
        )
        (value / 255f).coerceIn(MIN_BRIGHTNESS, 1f)
    }.getOrNull()
}

// The window's own floor. Shared by everything on the brightness path so the bounds cannot drift
// apart: the gesture, the setter and the initial read all clamp to the same value.
private const val MIN_BRIGHTNESS = 0.01f

// Last resort when the device reports no brightness at all — neither a window override nor a
// readable setting. Not a default: every path that can establish a real value is tried first,
// because starting a *relative* gesture from a fabricated middle is what makes the first swipe jump
// rather than adjust.
private const val UNKNOWN_BRIGHTNESS = 0.5f

private fun Activity.setScreenBrightness(fraction: Float) {
    val params = window.attributes
    params.screenBrightness = fraction.coerceIn(MIN_BRIGHTNESS, 1f)
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
