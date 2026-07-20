package com.otakustream.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
    private var pendingPlayUrl by mutableStateOf<String?>(null)
    private var pendingAniListToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only consume the launch intent on a fresh start — on an activity recreation
        // (e.g. process-death restore) the nav state is already restored, so re-reading it
        // would spuriously re-navigate to the player/install screen.
        if (savedInstanceState == null) {
            pendingStremioInstallUrl = intent.stremioInstallUrl()
            pendingPlayUrl = intent.playableVideoUri()
            pendingAniListToken = intent.aniListToken()
        }
        setContent {
            OtakuStreamTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(
                        pendingStremioInstallUrl = pendingStremioInstallUrl,
                        onPendingStremioInstallUrlConsumed = { pendingStremioInstallUrl = null },
                        pendingPlayUrl = pendingPlayUrl,
                        onPendingPlayUrlConsumed = { pendingPlayUrl = null },
                        pendingAniListToken = pendingAniListToken,
                        onPendingAniListTokenConsumed = { pendingAniListToken = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingStremioInstallUrl = intent.stremioInstallUrl()
        pendingPlayUrl = intent.playableVideoUri()
        pendingAniListToken = intent.aniListToken()
    }

    private fun Intent.stremioInstallUrl(): String? = data?.takeIf { it.scheme == "stremio" }?.toString()

    // A video opened via "Open with" / a browser video link arrives as ACTION_VIEW with an
    // http(s)/content/file data URI — hand it straight to the player.
    private fun Intent.playableVideoUri(): String? =
        takeIf { it.action == Intent.ACTION_VIEW }
            ?.data
            ?.takeIf { it.scheme in setOf("http", "https", "content", "file") }
            ?.toString()

    // AniList's implicit-grant redirect puts the token in the URL fragment:
    // otakustream://anilist-auth#access_token=...&token_type=Bearer&expires_in=...
    // encodedFragment, not fragment: getFragment() pre-decodes, so a token containing %26/%3D
    // would be corrupted before the split — split the raw fragment, then decode the value once.
    private fun Intent.aniListToken(): String? =
        data?.takeIf { it.scheme == "otakustream" && it.host == "anilist-auth" }
            ?.encodedFragment
            ?.split("&")
            ?.firstOrNull { it.startsWith("access_token=") }
            ?.removePrefix("access_token=")
            ?.ifEmpty { null }
            ?.let { Uri.decode(it) }

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
