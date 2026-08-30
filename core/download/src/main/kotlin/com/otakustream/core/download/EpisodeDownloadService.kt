package com.otakustream.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Keeps downloads running while the app is backgrounded, and gives the user progress and a way to
// stop. Same reasoning as TorrentService: without a foreground service the platform is free to stop
// the work behind a backgrounded process, so a download would stall the moment the user switched
// away, with nothing on screen to say why.
//
// Media3 drives this class rather than the app: DownloadManager starts and stops it as work appears
// and finishes, which is why nothing in the app calls startService directly.
@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class EpisodeDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    /* channelDescriptionResourceId = */ 0,
) {

    // Not named `downloadManager`: Kotlin would generate getDownloadManager() for the property,
    // which is the exact JVM signature of the override below.
    @Inject
    lateinit var manager: DownloadManager

    private val notificationHelper: DownloadNotificationHelper by lazy {
        DownloadNotificationHelper(this, CHANNEL_ID)
    }

    override fun onCreate() {
        // Before super.onCreate(): the base class may post its foreground notification during
        // startup, and on a channel that does not exist yet that notification is silently dropped —
        // which on Android 8+ means a foreground service with no visible notification.
        createChannel()
        super.onCreate()
    }

    override fun getDownloadManager(): DownloadManager = manager

    // No scheduler. One would restart interrupted downloads when constraints are met again, which
    // sounds strictly better but means the app starts using the network on its own after the user
    // has left it — for content they asked for once, possibly days ago. Resuming is a tap in the
    // Library instead.
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification = notificationHelper.buildProgressNotification(
        /* context = */ this,
        /* smallIcon = */ android.R.drawable.stat_sys_download,
        /* contentIntent = */ null,
        /* message = */ null,
        downloads,
        notMetRequirements,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                // Low: this is progress on something the user already asked for, not news. At
                // DEFAULT it would make a sound every time a download started.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "episode_downloads"

        // Distinct from the torrent service's, or the two would overwrite each other's notification
        // and whichever posted last would be the only one the user could see or stop.
        const val FOREGROUND_NOTIFICATION_ID = 0xD0 // 208
    }
}
