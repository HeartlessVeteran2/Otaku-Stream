package com.otakustream.core.player

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerController: PlayerController

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // addSession, not just build-and-return-from-onGetSession. This is the line that makes the
        // service legal.
        //
        // PlayerController starts this with startForegroundService(), which gives the process about
        // five seconds to call startForeground() or be killed with
        // ForegroundServiceDidNotStartInTimeException. Inside media3, startForeground() is reached
        // only through MediaNotificationManager, and MediaNotificationManager only ever hears about
        // a session that has been registered with the service. MediaSessionService registers one in
        // exactly two places — the ACTION_MEDIA_BUTTON branch of onStartCommand, and a
        // MediaController connecting through onBind — and this app does neither: it sends a bare
        // Intent and never builds a MediaController. Constructing a MediaSession inside the service
        // does not register it either; nothing in MediaSessionImpl looks for its enclosing service.
        //
        // So the session existed, onGetSession would have returned it, and nothing ever called
        // onGetSession. No notification was posted, startForeground() was never reached, and the
        // system killed the app a few seconds into every episode — or, on OEM builds that don't
        // enforce the deadline, left playback with no notification, no lock-screen controls, no
        // headphone-button handling and no background audio, all of which the README promises.
        mediaSession = MediaSession.Builder(this, playerController.player).build()
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Release the session wrapper only — the ExoPlayer instance is the app-lifetime
        // PlayerController singleton and must never be released here.
        //
        // Unregistered before release, mirroring the addSession above: the service keeps its own
        // list, and releasing a session it still holds leaves the notification manager driving a
        // dead one.
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
