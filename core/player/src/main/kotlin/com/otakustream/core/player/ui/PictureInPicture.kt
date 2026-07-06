package com.otakustream.core.player.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

@Composable
fun rememberIsInPictureInPictureMode(): State<Boolean> {
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    val state = remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }

    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose { }
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            state.value = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    return state
}
