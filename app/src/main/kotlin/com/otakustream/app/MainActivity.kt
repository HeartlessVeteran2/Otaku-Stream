package com.otakustream.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import com.otakustream.core.sources.api.PendingPlayback
import com.otakustream.core.sources.api.Video
import com.otakustream.core.torrent.MagnetLinks
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private val MIN_PIP_ASPECT_RATIO = 1 / 2.39
private val MAX_PIP_ASPECT_RATIO = 2.39

// Schemes an ACTION_VIEW intent can hand straight to the player. Compared lowercased.
private val PLAYABLE_SCHEMES = setOf("http", "https", "content", "file")

// A magnet link that arrived from outside the app and has not been agreed to yet. Holds the raw
// link so nothing is re-derived after the user confirms, and the name only so the prompt can say
// what it is about to fetch.
private data class PendingMagnet(val magnet: String, val displayName: String)

// The two halves of an AniList implicit-grant redirect. The state is nullable because a forged
// redirect simply won't carry one — which is precisely the case the check exists to reject.
private data class AniListRedirect(val token: String, val state: String?)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // dagger.Lazy: PlayerController is a @Singleton that builds an ExoPlayer (renderers, track
    // selector, audio-capability probe) in its constructor. Plain field injection made Hilt do all
    // of that on the main thread during onCreate of every cold start — even though this Activity
    // only reads it in onUserLeaveHint for Picture-in-Picture, and the user may never play
    // anything. The player screen's ViewModel constructs it for real when playback starts.
    //
    // Leaving the app without ever playing does construct it here, once, to answer "is something
    // playing?" — that is off the cold-start critical path, which is what this is about.
    @Inject
    lateinit var playerControllerLazy: Lazy<PlayerController>

    private var pendingStremioInstallUrl by mutableStateOf<String?>(null)
    private var pendingPlayUrl by mutableStateOf<String?>(null)
    private var pendingAniListRedirect by mutableStateOf<AniListRedirect?>(null)

    // A magnet link waits here for the user to confirm it, rather than going straight to the player.
    // Unlike opening an http video, starting a torrent joins a swarm: it announces the user's IP
    // address to every peer on it and begins uploading. Any web page can hand this app a magnet:
    // link, so doing that on a single tap — with no statement of what is about to be fetched — is
    // the app making a network-visible decision on the user's behalf.
    private var pendingMagnet by mutableStateOf<PendingMagnet?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: swaps the Splash theme for the app theme and keeps the
        // system splash on screen until the first frame instead of flashing a blank window.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only consume the launch intent on a fresh start — on an activity recreation
        // (e.g. process-death restore) the nav state is already restored, so re-reading it
        // would spuriously re-navigate to the player/install screen.
        if (savedInstanceState == null) {
            pendingStremioInstallUrl = intent.stremioInstallUrl()
            pendingPlayUrl = intent.playableVideoUri()
            pendingAniListRedirect = intent.aniListRedirect()
            pendingMagnet = intent.pendingMagnet()
        }
        setContent {
            OtakuStreamTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(
                        pendingStremioInstallUrl = pendingStremioInstallUrl,
                        onPendingStremioInstallUrlConsumed = { pendingStremioInstallUrl = null },
                        pendingPlayUrl = pendingPlayUrl,
                        onPendingPlayUrlConsumed = { pendingPlayUrl = null },
                        pendingAniListToken = pendingAniListRedirect?.token,
                        pendingAniListState = pendingAniListRedirect?.state,
                        onPendingAniListTokenConsumed = { pendingAniListRedirect = null },
                        pendingMagnetName = pendingMagnet?.displayName,
                        onMagnetConfirmed = {
                            // The stash happens here, on confirm, not at parse time — so declining
                            // leaves nothing behind for a later playback to pick up.
                            pendingMagnet?.let { pendingPlayUrl = magnetToPlayableUrl(it.magnet) }
                            pendingMagnet = null
                        },
                        onMagnetDismissed = { pendingMagnet = null },
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
        pendingAniListRedirect = intent.aniListRedirect()
        pendingMagnet = intent.pendingMagnet()
    }

    private fun Intent.stremioInstallUrl(): String? = data?.takeIf { it.scheme == "stremio" }?.toString()

    // A video opened via "Open with" / a browser video link arrives as ACTION_VIEW with an
    // http(s)/content/file data URI — hand it straight to the player. A magnet link arrives the same
    // way and is translated to the app's own torrent:// identity first.
    private fun Intent.playableVideoUri(): String? {
        val uri = takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        // Schemes are case-insensitive per RFC 3986 and Uri doesn't normalise them, so a sender that
        // writes "HTTP://" or "MAGNET:?" hands us a scheme that wouldn't match a lowercase literal.
        val scheme = uri.scheme?.lowercase() ?: return null
        // Magnets deliberately do not resolve here — they go through pendingMagnet and a confirmation.
        return uri.takeIf { scheme in PLAYABLE_SCHEMES }?.toString()
    }

    // Parsed early only so the prompt can name what it is about to download; nothing is started and
    // nothing is stashed until the user confirms.
    private fun Intent.pendingMagnet(): PendingMagnet? {
        val uri = takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        if (uri.scheme?.lowercase() != "magnet") return null
        val raw = uri.toString()
        val link = MagnetLinks.parse(raw) ?: return null
        // An unnamed magnet is legitimate — dn is optional — and the prompt still has to say
        // something honest rather than an empty string.
        return PendingMagnet(magnet = raw, displayName = link.displayName ?: "this torrent")
    }

    // AniList's implicit-grant redirect puts the token in the URL fragment:
    // otakustream://anilist-auth#access_token=...&token_type=Bearer&expires_in=...
    // encodedFragment, not fragment: getFragment() pre-decodes, so a token containing %26/%3D
    // would be corrupted before the split — split the raw fragment, then decode the value once.
    //
    // The `state` travels with it and is checked before the token is stored — see AniListAuthState.
    // Both are pulled from the same fragment here so a redirect can never arrive half-parsed, with
    // a token and no state to judge it by.
    private fun Intent.aniListRedirect(): AniListRedirect? {
        val fragment = data?.takeIf { it.scheme == "otakustream" && it.host == "anilist-auth" }
            ?.encodedFragment ?: return null
        val fields = fragment.split("&").mapNotNull { field ->
            val name = field.substringBefore('=', missingDelimiterValue = "")
            if (name.isEmpty() || '=' !in field) null else name to Uri.decode(field.substringAfter('='))
        }.toMap()
        val token = fields["access_token"]?.ifEmpty { null } ?: return null
        return AniListRedirect(token = token, state = fields["state"])
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Don't let a PiP check be the thing that builds the player. This runs during the leave
        // transition, so constructing ExoPlayer here would add jank to every Home-button press in a
        // session that never played anything — and the answer would be "not playing" regardless,
        // since only the player screen brings the controller into existence.
        if (!PlayerController.exists) return

        val state = playerControllerLazy.get().uiState.value
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
