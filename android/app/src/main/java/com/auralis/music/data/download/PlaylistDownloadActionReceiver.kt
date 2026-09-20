package com.auralis.music.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlaylistDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        when (intent.action) {
            ACTION_CANCEL -> PlaylistDownloadCoordinator.cancel(context.applicationContext, jobId)
            ACTION_RETRY -> {
                android.widget.Toast.makeText(context, "Retrying playlist download...", android.widget.Toast.LENGTH_SHORT).show()
                PlaylistDownloadCoordinator.retry(context.applicationContext, jobId)
            }
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.auralis.music.CANCEL_PLAYLIST_DOWNLOAD"
        const val ACTION_RETRY = "com.auralis.music.RETRY_PLAYLIST_DOWNLOAD"
        const val EXTRA_JOB_ID = "jobId"
    }
}
