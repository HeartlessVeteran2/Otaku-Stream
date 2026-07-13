package com.otakustream.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory

// Falls back to a working decoder (possibly software) if the device's preferred one fails to
// initialize, rather than failing playback outright. This is the pragmatic, scoped substitute
// for "plays more formats reliably" — true FFmpeg-level format parity would mean bundling a
// native decoder extension module, which is out of scope.
@OptIn(UnstableApi::class)
class PlayerRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }
}
