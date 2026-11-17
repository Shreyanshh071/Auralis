package com.auralis.music.data.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await

/**
 * The phone's one account sync client, shared by library sync and settings sync, so the app keeps
 * a single socket. With both build endpoints blank (rollout off) it is never contacted.
 */
object AccountSyncHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var instance: LibraryCloudClient? = null

    /** Whether an activity is showing; sockets are only held open then. */
    @Volatile var inForeground = true
    /** Grows each time the app comes back to the foreground, so each topic can catch up once. */
    @Volatile var foregroundCount = 0L

    val libraryUrl: String get() = com.auralis.music.BuildConfig.LIBRARY_SYNC_URL
    val accountSyncUrl: String get() = com.auralis.music.BuildConfig.ACCOUNT_SYNC_URL
    val accountSyncOn: Boolean get() = accountSyncUrl.isNotBlank()

    fun client(context: Context): LibraryCloudClient = instance ?: synchronized(this) {
        instance ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("auralis_account_prefs", Context.MODE_PRIVATE)
            val device = prefs.getString("library_device", null)
                ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("library_device", it).apply() }
            LibraryCloudClient(libraryUrl.ifBlank { accountSyncUrl }, device, FileLibraryCloudStore(java.io.File(app.filesDir, "library-cloud")),
                okhttp3.OkHttpClient(), scope, log = { android.util.Log.i("CloudSync", it) }, libraryEnabled = libraryUrl.isNotBlank(),
                token = { uid, refresh ->
                    // Only the signed-in account's token, and only for that account.
                    val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
                    if (user == null || user.isAnonymous || user.uid != uid) null
                    else runCatching { user.getIdToken(refresh).await().token }.getOrNull()
                }).also { instance = it }
        }
    }
}
