package com.auralis.music.data.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.Activity
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.work.*
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

object PlaylistDownloadCoordinator {
    const val LEGACY_STORAGE_PERMISSION_REQUEST = 104
    const val BACKEND_UIDT = "UIDT"
    const val BACKEND_WORK_MANAGER = "WORK_MANAGER"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _jobs = MutableStateFlow<Map<String, PlaylistDownloadJobEntity>>(emptyMap())
    val jobs: StateFlow<Map<String, PlaylistDownloadJobEntity>> = _jobs.asStateFlow()
    @Volatile private var appContext: Context? = null
    private var observer: Job? = null
    @Volatile private var pendingLegacyEnqueue: PendingEnqueue? = null
    @Volatile private var pendingLegacyRetry: String? = null

    private data class PendingEnqueue(val playlistId: String, val playlistName: String, val tracks: List<Track>)

    @Synchronized fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PlaylistDownloadNotifications.createChannel(context)
        val repository = PlaylistDownloadRepository(context)
        observer = scope.launch {
            repository.observeAll().collect { rows -> _jobs.value = indexPlaylistJobs(rows) }
        }
        scope.launch {
            AuralisDownloadManager.awaitInitialized(context.applicationContext)
            reconcileAll(context.applicationContext)
        }
    }

    fun enqueue(context: Context, playlistId: String, playlistName: String, tracks: List<Track>) {
        init(context)
        if (!hasLegacyStoragePermission(context)) {
            pendingLegacyEnqueue = PendingEnqueue(playlistId, playlistName, tracks)
            requestLegacyStoragePermission(context)
            return
        }
        enqueuePersisted(context, playlistId, playlistName, tracks)
    }

    private fun enqueuePersisted(context: Context, playlistId: String, playlistName: String, tracks: List<Track>) {
        scope.launch {
            val backend = backendForApi(Build.VERSION.SDK_INT)
            val repository = PlaylistDownloadRepository(context)
            val existing = repository.getForPlaylist(playlistId)
            if (existing?.status == PlaylistDownloadStatus.DOWNLOADING.name && !existing.cancelRequested) return@launch
            val job = repository.createOrUpdate(playlistId, playlistName, tracks, backend)
            if (job.status == PlaylistDownloadStatus.COMPLETE.name) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        "\"$playlistName\" is already downloaded and available offline!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                scheduleOrPersistFailure(context.applicationContext, repository, job)
            }
        }
    }

    fun retry(context: Context, jobId: String) {
        init(context)
        if (!hasLegacyStoragePermission(context)) {
            pendingLegacyRetry = jobId
            requestLegacyStoragePermission(context)
            return
        }
        scope.launch {
            val repository = PlaylistDownloadRepository(context)
            val old = repository.get(jobId) ?: return@launch
            val job = repository.createOrUpdate(old.playlistId, old.playlistName, repository.tracks(old),
                backendForApi(Build.VERSION.SDK_INT))
            scheduleOrPersistFailure(context.applicationContext, repository, job)
        }
    }

    fun onLegacyStoragePermissionResult(activity: Activity, granted: Boolean) {
        val enqueue = pendingLegacyEnqueue.also { pendingLegacyEnqueue = null }
        val retryJobId = pendingLegacyRetry.also { pendingLegacyRetry = null }
        if (!granted) {
            android.widget.Toast.makeText(activity, "Storage permission is required to save playlists to Downloads", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (enqueue != null) enqueuePersisted(activity, enqueue.playlistId, enqueue.playlistName, enqueue.tracks)
        if (retryJobId != null) retry(activity, retryJobId)
    }

    private fun hasLegacyStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestLegacyStoragePermission(context: Context) {
        val activity = context as? Activity
        if (activity == null) {
            android.widget.Toast.makeText(context, "Open Auralis to allow saving playlists to Downloads", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        androidx.core.app.ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            LEGACY_STORAGE_PERMISSION_REQUEST
        )
    }

    fun cancel(context: Context, jobId: String) {
        init(context)
        scope.launch {
            PlaylistDownloadRepository(context).requestCancel(jobId)
            if (Build.VERSION.SDK_INT >= 34) {
                context.getSystemService(JobScheduler::class.java).cancel(PlaylistDownloadRepository.platformJobId(jobId))
            }
            WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
        }
    }

    fun setOfflineEnabled(context: Context, playlistId: String, enabled: Boolean, expectedTrackIds: List<String> = emptyList()) {
        init(context)
        scope.launch {
            val repository = PlaylistDownloadRepository(context)
            val job = repository.getForPlaylist(playlistId) ?: return@launch
            if (!enabled) {
                repository.setOfflineEnabled(playlistId, false)
                return@launch
            }
            AuralisDownloadManager.awaitInitialized(context)
            val files = PlaylistDownloadFiles(context)
            val resultsByTrack = repository.results(job).associateBy { it.trackId }
            val eligible = job.status == PlaylistDownloadStatus.COMPLETE.name &&
                (expectedTrackIds.isEmpty() || matchesTrackSnapshot(job, expectedTrackIds)) &&
                repository.tracks(job).all { track ->
                    AuralisDownloadManager.isDownloaded(track.id) &&
                        resultsByTrack[track.id]?.let { files.verify(it.contentUri) } == true
                }
            repository.setOfflineEnabled(playlistId, eligible)
        }
    }

    suspend fun offlineUri(context: Context, playlistId: String?, trackId: String): String? {
        if (playlistId == null) return null
        init(context)
        return withContext(Dispatchers.IO) {
            val repository = PlaylistDownloadRepository(context)
            val job = _jobs.value[playlistId] ?: repository.getForPlaylist(playlistId) ?: return@withContext null
            if (!job.offlineEnabled || job.status != PlaylistDownloadStatus.COMPLETE.name) return@withContext null
            val result = runCatching {
                json.decodeFromString<List<TrackDownloadResult>>(job.resultsJson)
                    .firstOrNull { it.trackId == trackId }
            }.getOrNull()
            if (result == null) {
                val belongsToPlaylist = runCatching {
                    json.decodeFromString<List<Track>>(job.trackSnapshotJson).any { it.id == trackId }
                }.getOrDefault(false)
                if (belongsToPlaylist) disableOfflineForMissingFile(context, repository, playlistId)
                return@withContext null
            }
            if (result.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES &&
                AuralisDownloadManager.isDownloaded(trackId) &&
                PlaylistDownloadFiles(context).verify(result.contentUri)) return@withContext result.contentUri
            disableOfflineForMissingFile(context, repository, playlistId)
            null
        }
    }

    private suspend fun disableOfflineForMissingFile(
        context: Context,
        repository: PlaylistDownloadRepository,
        playlistId: String
    ) {
        repository.setOfflineEnabled(playlistId, false)
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
                context.applicationContext,
                "An offline playlist file is missing. Retry the playlist download.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun reconcile(context: Context) {
        init(context)
        scope.launch {
            AuralisDownloadManager.awaitInitialized(context)
            reconcileAll(context.applicationContext)
        }
    }

    private suspend fun reconcileAll(context: Context) {
        val repository = PlaylistDownloadRepository(context)
        val files = PlaylistDownloadFiles(context)
        repository.getAll().forEach { job ->
            if (job.status !in setOf(
                    PlaylistDownloadStatus.COMPLETE.name,
                    PlaylistDownloadStatus.PARTIAL_FAILURE.name,
                    PlaylistDownloadStatus.FAILED.name
                )) return@forEach
            val outcome = reconcilePlaylistDownloadState(
                trackIds = repository.tracks(job).map { it.id },
                results = repository.results(job),
                offlineEnabled = job.offlineEnabled,
                isInDownloadStore = AuralisDownloadManager::isDownloaded,
                isPublicFileValid = files::verify
            )
            if (outcome.results != repository.results(job) || outcome.status.name != job.status ||
                outcome.offlineEnabled != job.offlineEnabled) {
                repository.update(job, outcome.results, outcome.status, null, outcome.offlineEnabled)
            }
        }
    }

    fun removePlaylistDownloads(context: Context, jobId: String) {
        init(context)
        scope.launch {
            val repository = PlaylistDownloadRepository(context)
            val job = repository.get(jobId) ?: return@launch
            repository.requestCancel(jobId)
            cancelScheduledWork(context, jobId)
            delay(250)
            val files = PlaylistDownloadFiles(context)
            repository.results(job).forEach { files.delete(it.contentUri) }
            files.deleteFolderIfEmpty(job.folderName)
            // Also remove each track from the main DownloadStore only if no other
            // playlist job still has that track downloaded.
            val otherJobs = repository.getAll().filter { it.jobId != jobId }
            val otherTrackIds = otherJobs.flatMap { other ->
                repository.results(other)
                    .filter { it.outcome in PlaylistDownloadRepository.SUCCESS_OUTCOMES }
                    .map { it.trackId }
            }.toSet()
            val trackIds = tracks(job).map { it.id }
            trackIds.forEach { trackId ->
                if (trackId !in otherTrackIds) {
                    AuralisDownloadManager.removeDownload(trackId)
                }
            }
            repository.delete(jobId)
            _jobs.update { removePlaylistJobFromState(it, jobId) }
            context.getSystemService(android.app.NotificationManager::class.java)
                .cancel(PlaylistDownloadNotifications.id(jobId))
        }
    }

    fun tracks(job: PlaylistDownloadJobEntity): List<Track> = runCatching {
        json.decodeFromString<List<Track>>(job.trackSnapshotJson)
    }.getOrDefault(emptyList())

    fun results(job: PlaylistDownloadJobEntity): List<TrackDownloadResult> = runCatching {
        json.decodeFromString<List<TrackDownloadResult>>(job.resultsJson)
    }.getOrDefault(emptyList())

    fun downloadedBytes(job: PlaylistDownloadJobEntity): Long = verifiedDownloadBytes(results(job))

    fun matchesTrackSnapshot(job: PlaylistDownloadJobEntity, trackIds: List<String>): Boolean = runCatching {
        val stored = json.decodeFromString<List<Track>>(job.trackSnapshotJson)
            .map { it.id }.distinct()
        stored.size == trackIds.distinct().size && stored.toSet() == trackIds.toSet()
    }.getOrDefault(false)

    internal fun backendForApi(api: Int): String = if (api >= 34) BACKEND_UIDT else BACKEND_WORK_MANAGER

    private fun cancelScheduledWork(context: Context, jobId: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(JobScheduler::class.java).cancel(PlaylistDownloadRepository.platformJobId(jobId))
        }
        WorkManager.getInstance(context).cancelUniqueWork(workName(jobId))
    }

    private suspend fun scheduleOrPersistFailure(
        context: Context,
        repository: PlaylistDownloadRepository,
        job: PlaylistDownloadJobEntity
    ) {
        try {
            schedule(context, job)
        } catch (e: Exception) {
            android.util.Log.e("AuralisDownload", "Unable to schedule playlist job ${job.jobId}", e)
            val failed = repository.update(
                job,
                repository.results(job),
                PlaylistDownloadStatus.FAILED,
                null,
                false
            )
            context.getSystemService(android.app.NotificationManager::class.java).notify(
                PlaylistDownloadNotifications.id(job.jobId),
                PlaylistDownloadNotifications.finished(context, failed, repository.tracks(failed).size)
            )
        }
    }

    private fun schedule(context: Context, job: PlaylistDownloadJobEntity) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val extras = PersistableBundle().apply { putString("jobId", job.jobId) }
                val info = JobInfo.Builder(
                    PlaylistDownloadRepository.platformJobId(job.jobId),
                    ComponentName(context, PlaylistDownloadJobService::class.java)
                ).setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(extras)
                    .build()
                val result = context.getSystemService(JobScheduler::class.java).schedule(info)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    return
                }
                android.util.Log.w("AuralisDownload", "JobScheduler UIDT returned $result, falling back to WorkManager")
            } catch (e: Exception) {
                android.util.Log.w("AuralisDownload", "UIDT scheduling failed, falling back to WorkManager: ${e.message}")
            }
        }
        val request = OneTimeWorkRequestBuilder<PlaylistDownloadWorker>()
            .setInputData(workDataOf("jobId" to job.jobId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(job.jobId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun workName(jobId: String) = "playlist_download_$jobId"
}
