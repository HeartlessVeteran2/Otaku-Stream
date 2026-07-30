package com.otakustream.core.torrent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Keeps an active torrent download alive while the app is backgrounded, and gives the user something
// to look at and a way out.
//
// Without this, Android is free to stop the work behind a backgrounded process, so a download would
// stall the moment the user switched away and playback would fail for no visible reason. It is also
// the honest thing to do: a foreground notification is how the platform expects an app to disclose
// that it is using the network on the user's behalf.
//
// dataSync rather than mediaPlayback: the player already runs its own mediaPlayback service, and this
// one is about moving bytes, which continues even when playback is paused.
@AndroidEntryPoint
class TorrentService : Service() {

    @Inject
    lateinit var engine: TorrentEngine

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The Stop action is a real stop, not a hide: it tears the session down so the user isn't
            // left with a dismissed notification and a torrent still running.
            engine.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForegroundCompat(buildNotification(stats = null))
        startPolling()
        // Not sticky: if the process dies there is no playback left to feed, so a restarted service
        // would hold a notification over nothing.
        return START_NOT_STICKY
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        pollJob = serviceScope.launch {
            var pollsWithNothingToReport = 0
            while (isActive) {
                val stats = engine.stats()
                // The engine stops this service when its last reader closes, so normally the loop is
                // cancelled from outside. This is the backstop for the case where that call doesn't
                // land — a permanent "Starting…" notification over no download at all is the single
                // worst way this feature could fail, because the user can't tell what it's doing and
                // Stop is the only thing that looks like it might help.
                //
                // Keyed on the reader count, not on stats being null. stats() is null for the whole of
                // a cold start — the handle doesn't exist until metadata arrives from a peer, which on
                // an unpopular torrent can take longer than any timeout worth setting — so giving up on
                // that alone would kill exactly the slow starts this service exists to protect.
                if (!engine.hasOpenReaders) {
                    if (++pollsWithNothingToReport > MAX_IDLE_POLLS) {
                        Log.i(TAG, "No torrent to report on; stopping the service")
                        stopSelf()
                        return@launch
                    }
                } else {
                    pollsWithNothingToReport = 0
                }
                notificationManager()?.notify(NOTIFICATION_ID, buildNotification(stats))
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun buildNotification(stats: TorrentStats?) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(stats?.name?.takeIf { it.isNotBlank() } ?: "Streaming a torrent")
            .setContentText(
                stats?.let { "${it.formattedRate()} · ${it.formattedPeers()}" } ?: "Starting…",
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            // Low priority and silent: this is status, not news. It must not buzz mid-episode.
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this,
                    /* requestCode = */ 0,
                    Intent(this, TorrentService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    private fun startForegroundCompat(notification: android.app.Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Torrent streaming",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a torrent is being streamed, so it can be stopped at any time."
            setShowBadge(false)
        }
        notificationManager()?.createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    override fun onDestroy() {
        pollJob?.cancel()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TorrentService"
        private const val CHANNEL_ID = "torrent_streaming"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.otakustream.core.torrent.action.STOP"

        // Once a second. Fast enough that the rate looks live, slow enough to be irrelevant to battery.
        private const val POLL_INTERVAL_MS = 1_000L

        // ~10 s with no reader open at all. Short is fine now that this tracks readers rather than
        // stats: no reader means nothing is even trying, so there is nothing to wait for.
        private const val MAX_IDLE_POLLS = 10

        // Failures are logged and swallowed rather than propagated. The download itself works without
        // this service while the app is in the foreground, so failing the playback over it would trade
        // a degraded feature for a broken one — but a silent failure would leave a background download
        // being killed by the platform with nothing to explain why.
        fun start(context: Context) {
            val intent = Intent(context, TorrentService::class.java)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "Could not start the torrent foreground service", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TorrentService::class.java)) }
                .onFailure { Log.w(TAG, "Could not stop the torrent foreground service", it) }
        }
    }
}
