package com.auralis.music.data.network

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val htmlUrl: String? = null,
    val isChecking: Boolean = false,
    val error: String? = null
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_REPO = "Shreyanshh071/Auralis"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    suspend fun checkForUpdates(context: Context): UpdateInfo = withContext(Dispatchers.IO) {
        val currentVer = getCurrentVersion(context)
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Auralis-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        return@withContext UpdateInfo(
                            hasUpdate = false,
                            currentVersion = currentVer,
                            latestVersion = currentVer,
                            releaseTitle = "Up to date",
                            releaseNotes = "You are currently running the latest version of Auralis."
                        )
                    }
                    return@withContext UpdateInfo(
                        hasUpdate = false,
                        currentVersion = currentVer,
                        latestVersion = currentVer,
                        error = "Server response: ${response.code}"
                    )
                }

                val body = response.body?.string() ?: return@withContext UpdateInfo(
                    hasUpdate = false,
                    currentVersion = currentVer,
                    latestVersion = currentVer,
                    error = "Empty response"
                )

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                val releaseName = json.optString("name", "v$tagName")
                val releaseNotes = json.optString("body", "")
                val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_REPO/releases")

                // Find APK asset
                var downloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i)
                        val name = asset?.optString("name", "") ?: ""
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset?.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (downloadUrl == null) {
                    downloadUrl = htmlUrl
                }

                val isNewer = isVersionNewer(latest = tagName, current = currentVer)

                UpdateInfo(
                    hasUpdate = isNewer,
                    currentVersion = currentVer,
                    latestVersion = if (tagName.isNotBlank()) tagName else currentVer,
                    releaseTitle = releaseName,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    htmlUrl = htmlUrl
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            UpdateInfo(
                hasUpdate = false,
                currentVersion = currentVer,
                latestVersion = currentVer,
                error = e.localizedMessage ?: "Network connection failed"
            )
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        if (latest.isBlank()) return false
        try {
            val latestParts = latest.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
            val currentParts = current.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (_: Exception) {
            return latest != current
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Auralis-Android-App")
                .build()

            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "Auralis-v$versionName.apk")
            if (apkFile.exists()) apkFile.delete()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Failed to download APK: HTTP ${response.code}")
                val body = response.body ?: throw Exception("Empty APK response")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloadedBytes = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val prog = downloadedBytes.toFloat() / totalBytes.toFloat()
                                onProgress(prog.coerceIn(0f, 1f))
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Trigger Android Package Installer
            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download and install update", e)
            Result.failure(e)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "Please allow Auralis to install unknown apps", Toast.LENGTH_LONG).show()
                    val manageIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(manageIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "Failed to launch installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
