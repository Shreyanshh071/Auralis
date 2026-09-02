package com.auralis.music.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.auralis.music.data.datastore.UpdaterDataStore
import com.auralis.music.data.network.UpdateChecker
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AppUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AppUpdateWorker"
        const val WORK_NAME = "auralis_periodic_update_check"

        fun schedulePeriodicCheck(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = PeriodicWorkRequestBuilder<AppUpdateWorker>(
                    repeatInterval = 6,
                    repeatIntervalTimeUnit = TimeUnit.HOURS
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
                Log.d(TAG, "Scheduled periodic background update check every 6 hours")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule periodic update check", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val updaterStore = UpdaterDataStore(applicationContext)
            val autoCheck = updaterStore.settingsFlow.first().autoCheckUpdates

            if (autoCheck) {
                val updateInfo = UpdateChecker.checkForUpdates(applicationContext)
                if (updateInfo.hasUpdate) {
                    Log.d(TAG, "Background update check found new version: v${updateInfo.latestVersion}")
                    UpdateChecker.showUpdateNotification(applicationContext, updateInfo)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background update check failed: ${e.message}")
            Result.retry()
        }
    }
}
