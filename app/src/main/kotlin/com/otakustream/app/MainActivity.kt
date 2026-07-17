package com.otakustream.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.otakustream.app.navigation.AppNavHost
import com.otakustream.app.ui.theme.OtakuStreamTheme
import com.otakustream.core.player.PlayerController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private val MIN_PIP_ASPECT_RATIO = 1 / 2.39
private val MAX_PIP_ASPECT_RATIO = 2.39

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerController: PlayerController

    private var pendingStremioInstallUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingStremioInstallUrl = intent.stremioInstallUrl()
        setContent {
            OtakuStreamTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(pendingStremioInstallUrl = pendingStremioInstallUrl, onPendingStremioInstallUrlConsumed = { pendingStremioInstallUrl = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingStremioInstallUrl = intent.stremioInstallUrl()
    }

    private fun Intent.stremioInstallUrl(): String? = data?.takeIf { it.scheme == "stremio" }?.toString()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val state = playerController.uiState.value
        if (!state.isPlaying || state.videoWidth <= 0 || state.videoHeight <= 0) return

        val rawRatio = state.videoWidth.toDouble() / state.videoHeight
        val ratio = if (rawRatio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
            Rational(state.videoWidth, state.videoHeight)
        } else {
            Rational(16, 9)
        }
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
    }
}
