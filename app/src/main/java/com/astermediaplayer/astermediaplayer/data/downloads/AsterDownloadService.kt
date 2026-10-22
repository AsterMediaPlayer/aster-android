package com.astermediaplayer.astermediaplayer.data.downloads

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import com.astermediaplayer.astermediaplayer.AsterApp
import com.astermediaplayer.astermediaplayer.R

/** Keeps downloads going in the background, with a notification showing how far they've got. */
@UnstableApi
class AsterDownloadService : DownloadService(NOTIFICATION_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL_ID, R.string.downloads_channel, 0) {
    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = (application as AsterApp).downloads.manager

    // Resumes after a reboot or when the network the settings allow comes back, if the app isn't running.
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, android.R.drawable.stat_sys_download, null, null, downloads, notMetRequirements)

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 7
    }
}
