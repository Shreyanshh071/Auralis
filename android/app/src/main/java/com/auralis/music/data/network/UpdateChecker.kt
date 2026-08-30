package com.auralis.music.data.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
    private const val GITHUB_REPO = "shreyanshchoubey09/Auralis"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "2.0.0"
        } catch (_: Exception) {
            "2.0.0"
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
}
