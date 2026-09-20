package com.auralis.music.data.download

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.auralis.music.MainActivity
import com.auralis.music.R

object PlaylistDownloadNotifications {
    const val CHANNEL_ID = "auralis_playlist_downloads"
    private const val BASE_ID = 20_000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Playlist downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress and results for offline playlist downloads"
                }
            )
        }
    }

    fun id(jobId: String): Int = BASE_ID + (jobId.hashCode() and 0x0fff)

    fun running(
        context: Context,
        job: PlaylistDownloadJobEntity,
        currentTitle: String?,
        total: Int,
        currentIndex: Int? = null
    ): Notification {
        createChannel(context)
        val cancel = PendingIntent.getBroadcast(
            context, id(job.jobId),
            Intent(context, PlaylistDownloadActionReceiver::class.java)
                .setAction(PlaylistDownloadActionReceiver.ACTION_CANCEL)
                .putExtra(PlaylistDownloadActionReceiver.EXTRA_JOB_ID, job.jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val currentProgress = (currentIndex ?: (job.completedCount + job.failedCount)).coerceIn(0, total)
        val text = buildString {
            if (currentProgress > 0) {
                append("$currentProgress of $total songs")
            } else {
                append("0 of $total songs")
            }
            if (!currentTitle.isNullOrBlank()) append(" · $currentTitle")
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_auralis)
            .setContentTitle("Downloading ${job.playlistName}")
            .setContentText(text)
            .setProgress(total.coerceAtLeast(1), currentProgress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .setContentIntent(openApp(context, job.jobId))
            .build()
    }

    fun finished(context: Context, job: PlaylistDownloadJobEntity, total: Int): Notification {
        createChannel(context)
        val complete = job.status == PlaylistDownloadStatus.COMPLETE.name
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_auralis)
            .setContentTitle(if (complete) "${job.playlistName} downloaded" else job.playlistName)
            .setContentText(
                if (complete) "$total songs downloaded"
                else "${job.completedCount} of $total downloaded${if (job.failedCount > 0) " · ${job.failedCount} failed" else ""}"
            )
            .setOngoing(false).setAutoCancel(true).setOnlyAlertOnce(false)
            .setContentIntent(openApp(context, job.jobId))
        if (!complete && job.status != PlaylistDownloadStatus.CANCELLED.name) {
            val retryPendingIntent = PendingIntent.getBroadcast(
                context,
                id(job.jobId) + 1,
                Intent(context, PlaylistDownloadActionReceiver::class.java)
                    .setAction(PlaylistDownloadActionReceiver.ACTION_RETRY)
                    .putExtra(PlaylistDownloadActionReceiver.EXTRA_JOB_ID, job.jobId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_popup_sync, "Retry", retryPendingIntent)
        }
        return builder.build()
    }

    private fun openApp(context: Context, jobId: String, retry: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, id(jobId) + if (retry) 1 else 0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("PLAYLIST_DOWNLOAD_JOB_ID", jobId)
            if (retry) putExtra("RETRY_PLAYLIST_DOWNLOAD", true)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
