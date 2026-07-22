package com.otakustream.core.player.cast

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CastManager"

// Owns the Google Cast session + a Media3 CastPlayer. PlayerController talks to this to hand the
// current media off to a connected Cast device (and take it back on disconnect); the player UI
// observes [isConnected] and hosts the Cast button. Everything degrades to a no-op when Google Play
// Services / Cast is unavailable, so nothing here can crash a device that can't cast.
@OptIn(UnstableApi::class)
@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // CastContext needs Google Play Services; a device without it simply never casts.
    private val castContext: CastContext? by lazy {
        runCatching { CastContext.getSharedInstance(context) }
            .onFailure { Log.i(TAG, "Cast unavailable: ${it.message}") }
            .getOrNull()
    }

    // Created lazily on the main thread (first warmUp/UI access). Registering the availability
    // listener here is why warmUp() must run before we rely on isConnected.
    private val castPlayer: CastPlayer? by lazy {
        val ctx = castContext ?: return@lazy null
        CastPlayer(ctx).apply {
            setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() { _isConnected.value = true }
                override fun onCastSessionUnavailable() { _isConnected.value = false }
            })
        }
    }

    val isAvailable: Boolean get() = castContext != null

    // Instantiate the CastPlayer so its session listener is live. Call once from the player UI
    // (main thread) before showing the Cast button.
    fun warmUp() {
        castPlayer
    }

    // Loads the current item onto the Cast device at [positionMs]. Main-thread only.
    fun castItem(mediaItem: MediaItem, positionMs: Long) {
        val player = castPlayer ?: return
        player.setMediaItem(mediaItem, positionMs)
        player.playWhenReady = true
        player.prepare()
    }

    fun currentPositionMs(): Long = castPlayer?.currentPosition ?: 0L

    fun stop() {
        castPlayer?.apply {
            stop()
            clearMediaItems()
        }
    }
}
