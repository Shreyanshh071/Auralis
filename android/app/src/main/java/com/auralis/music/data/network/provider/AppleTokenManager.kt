package com.auralis.music.data.network.provider

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Manages the Apple Music Developer Web JWT token required for the public
 * Apple Music Catalog search API (https://amp-api.music.apple.com/v1/catalog/us/search).
 *
 * Scrapes the token from beta.music.apple.com's web client bundle.
 * Caches in-memory with automatic token refresh on HTTP 401.
 * Never persists to Room or blocks audio playback.
 */
class AppleTokenManager(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "AppleTokenManager"
        private const val BASE_PAGE_URL = "https://beta.music.apple.com"
        private val INDEX_JS_REGEX = Pattern.compile("""/assets/index~[^/"']+\.js""")
        private val JWT_REGEX = Pattern.compile("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""")
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    @Volatile
    private var cachedToken: String? = null
    private val mutex = Mutex()

    /**
     * Retrieves a valid cached Apple Music web token, or scrapes a fresh one if missing or forced.
     * Guaranteed to never throw; returns null on failure so callers isolate errors safely.
     */
    suspend fun getToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!forceRefresh) {
                cachedToken?.let { return@withLock it }
            }

            try {
                Log.d(TAG, "Fetching fresh Apple Music web developer token...")
                // Step 1: GET https://beta.music.apple.com to locate the active index~<hash>.js asset
                val pageReq = Request.Builder()
                    .url(BASE_PAGE_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()

                val indexJsPath = client.newCall(pageReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Failed loading Apple Music base page: HTTP ${resp.code}")
                        return@use null
                    }
                    val html = resp.body?.string() ?: return@use null
                    val matcher = INDEX_JS_REGEX.matcher(html)
                    if (matcher.find()) matcher.group(0) else null
                } ?: run {
                    Log.w(TAG, "Could not find index.js asset bundle in Apple Music page")
                    return@withLock null
                }

                val bundleUrl = "$BASE_PAGE_URL$indexJsPath"
                Log.d(TAG, "Found index bundle URL: $bundleUrl")

                // Step 2: Fetch the JS bundle and extract the JWT token
                val bundleReq = Request.Builder()
                    .url(bundleUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "*/*")
                    .build()

                val token = client.newCall(bundleReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Failed loading Apple Music JS bundle: HTTP ${resp.code}")
                        return@use null
                    }
                    val js = resp.body?.string() ?: return@use null
                    val matcher = JWT_REGEX.matcher(js)
                    if (matcher.find()) matcher.group(0) else null
                }

                if (!token.isNullOrBlank()) {
                    Log.i(TAG, "Successfully extracted Apple Music web token (len=${token.length})")
                    cachedToken = token
                    token
                } else {
                    Log.w(TAG, "No JWT token matched in Apple Music JS bundle")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception while fetching Apple Music token: ${e.message}")
                null
            }
        }
    }

    fun clearToken() {
        cachedToken = null
        Log.d(TAG, "Apple Music token cleared from memory")
    }

    // Visible for testing
    internal fun setCachedToken(token: String?) {
        cachedToken = token
    }
}
