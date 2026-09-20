package com.auralis.music.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

class PlaylistDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString("jobId") ?: return Result.failure()
        val repository = PlaylistDownloadRepository(applicationContext)
        return try {
            val initial = repository.get(jobId) ?: return Result.failure()
            setForeground(info(initial))
            PlaylistDownloadExecutor(applicationContext).execute(jobId) { setForeground(info(it)) }
            val final = requireNotNull(repository.get(jobId))
            applicationContext.getSystemService(android.app.NotificationManager::class.java)
                .notify(PlaylistDownloadNotifications.id(jobId), PlaylistDownloadNotifications.finished(applicationContext, final, repository.tracks(final).size))
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuralisDownload", "Background playlist worker failed for $jobId", e)
            Result.retry()
        }
    }

    private suspend fun info(job: PlaylistDownloadJobEntity): ForegroundInfo {
        val repository = PlaylistDownloadRepository(applicationContext)
        val tracks = repository.tracks(job)
        val current = tracks.firstOrNull { it.id == job.currentTrackId }?.title
        val currentTrackIndex = tracks.indexOfFirst { it.id == job.currentTrackId }.let { if (it >= 0) it + 1 else null }
        return ForegroundInfo(
            PlaylistDownloadNotifications.id(job.jobId),
            PlaylistDownloadNotifications.running(applicationContext, job, current, tracks.size, currentTrackIndex),
            if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }
}
