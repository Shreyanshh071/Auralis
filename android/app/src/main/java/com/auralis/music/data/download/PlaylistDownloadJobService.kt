package com.auralis.music.data.download

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class PlaylistDownloadJobService : JobService() {
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        val jobId = params.extras.getString("jobId") ?: return false
        val task = scope.launch {
            val repository = PlaylistDownloadRepository(applicationContext)
            try {
                val initial = repository.get(jobId) ?: return@launch
                publish(params, initial)
                val final = PlaylistDownloadExecutor(applicationContext).execute(jobId) { publish(params, it) }
                setNotification(
                    params,
                    PlaylistDownloadNotifications.id(jobId),
                    PlaylistDownloadNotifications.finished(applicationContext, final, repository.tracks(final).size),
                    JOB_END_NOTIFICATION_POLICY_DETACH
                )
                jobFinished(params, false)
            } catch (_: CancellationException) {
                // JobScheduler will retry system-stopped work. Explicit cancellation is persisted.
            } catch (e: Exception) {
                android.util.Log.e("AuralisDownload", "UIDT playlist job failed for $jobId", e)
                jobFinished(params, true)
            } finally {
                jobs.remove(params.jobId)
            }
        }
        jobs[params.jobId] = task
        return true
    }

    private suspend fun publish(params: JobParameters, job: PlaylistDownloadJobEntity) {
        val repository = PlaylistDownloadRepository(applicationContext)
        val tracks = repository.tracks(job)
        val current = tracks.firstOrNull { it.id == job.currentTrackId }?.title
        val currentTrackIndex = tracks.indexOfFirst { it.id == job.currentTrackId }.let { if (it >= 0) it + 1 else null }
        setNotification(
            params,
            PlaylistDownloadNotifications.id(job.jobId),
            PlaylistDownloadNotifications.running(applicationContext, job, current, tracks.size, currentTrackIndex),
            JOB_END_NOTIFICATION_POLICY_DETACH
        )
    }

    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
