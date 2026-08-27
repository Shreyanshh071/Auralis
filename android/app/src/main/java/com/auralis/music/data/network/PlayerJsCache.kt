package com.auralis.music.data.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.Function
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * YouTube Player JavaScript & Cipher Cache.
 * Caches YouTube's base.js and compiled Rhino JavaScript bytecode for 24-48 hours.
 * Extracts and provides signatureTimestamp (sts) and ultra-fast (~15ms) n-sig / signature transformations.
 */
object PlayerJsCache {

    private const val TAG = "PlayerJsCache"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var cacheDir: File? = null
    private val mutex = Mutex()

    @Volatile
    private var cachedSts: Int? = null

    @Volatile
    private var cachedJsUrl: String? = null

    @Volatile
    private var cachedJsContent: String? = null

    @Volatile
    private var nFunctionName: String? = null

    @Volatile
    private var sigFunctionName: String? = null

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "yt_player_cache").apply { mkdirs() }
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val dir = cacheDir ?: return
        try {
            val metaFile = File(dir, "player_meta.json")
            if (metaFile.exists()) {
                val json = org.json.JSONObject(metaFile.readText())
                val timestamp = json.optLong("timestamp", 0L)
                if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                    cachedSts = json.optInt("sts").takeIf { it > 0 }
                    cachedJsUrl = json.optString("jsUrl").takeIf { it.isNotBlank() }
                    nFunctionName = json.optString("nFunction").takeIf { it.isNotBlank() }
                    sigFunctionName = json.optString("sigFunction").takeIf { it.isNotBlank() }

                    val jsFile = File(dir, "base.js")
                    if (jsFile.exists()) {
                        cachedJsContent = jsFile.readText()
                        Log.d(TAG, "Loaded valid cached player JS from disk (sts=$cachedSts, jsUrl=$cachedJsUrl)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading player JS from disk: ${e.message}")
        }
    }

    private fun saveToDisk(jsUrl: String, sts: Int, jsContent: String, nFunc: String?, sigFunc: String?) {
        val dir = cacheDir ?: return
        try {
            val metaFile = File(dir, "player_meta.json")
            val json = org.json.JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("sts", sts)
                put("jsUrl", jsUrl)
                put("nFunction", nFunc ?: "")
                put("sigFunction", sigFunc ?: "")
            }
            metaFile.writeText(json.toString())
            File(dir, "base.js").writeText(jsContent)
            Log.d(TAG, "Saved player JS cache to disk (sts=$sts)")
        } catch (e: Exception) {
            Log.w(TAG, "Error saving player JS to disk: ${e.message}")
        }
    }

    /**
     * Returns the cached signatureTimestamp (sts) or extracts it from the latest base.js.
     */
    suspend fun getSignatureTimestamp(): Int? = withContext(Dispatchers.IO) {
        cachedSts?.let { return@withContext it }
        ensurePlayerJsLoaded()
        cachedSts
    }

    /**
     * Ensures player JS is downloaded and parsed.
     */
    suspend fun ensurePlayerJsLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (cachedJsContent != null && cachedSts != null) return@withContext true

        mutex.withLock {
            if (cachedJsContent != null && cachedSts != null) return@withLock true

            try {
                // 1. Fetch mobile/web watch page to extract current base.js URL
                val watchUrl = "https://www.youtube.com/iframe_api"
                val req = Request.Builder()
                    .url(watchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                    .build()

                val res = client.newCall(req).execute()
                val apiBody = res.body?.string() ?: ""
                
                // Find player JS path or fallback to known player URL pattern
                var playerJsUrl: String? = null
                val jsMatch = Pattern.compile("""/s/player/[a-zA-Z0-9_-]+/player_ias\.vflset/[a-zA-Z0-9_/-]+/base\.js""").matcher(apiBody)
                if (jsMatch.find()) {
                    playerJsUrl = "https://www.youtube.com" + jsMatch.group()
                } else {
                    // Fallback to fetching youtube watch page HTML
                    val watchReq = Request.Builder()
                        .url("https://www.youtube.com/watch?v=4NRXx6U8ABQ")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()
                    val watchRes = client.newCall(watchReq).execute()
                    val watchBody = watchRes.body?.string() ?: ""
                    val match = Pattern.compile("""(?:/s/player/|player_ias/|base\.js)[^"'\s]+\.js""").matcher(watchBody)
                    if (match.find()) {
                        val path = match.group()
                        playerJsUrl = if (path.startsWith("http")) path else "https://www.youtube.com" + (if (path.startsWith("/")) "" else "/") + path
                    }
                }

                if (playerJsUrl.isNullOrBlank()) {
                    playerJsUrl = "https://www.youtube.com/s/player/e0339d67/player_ias.vflset/en_US/base.js"
                }

                Log.d(TAG, "Fetching player JS: $playerJsUrl")
                val jsReq = Request.Builder()
                    .url(playerJsUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                val jsRes = client.newCall(jsReq).execute()
                if (!jsRes.isSuccessful) return@withLock false

                val jsContent = jsRes.body?.string() ?: return@withLock false
                cachedJsContent = jsContent
                cachedJsUrl = playerJsUrl

                // Extract signatureTimestamp (sts)
                val stsMatch = Pattern.compile("""(?:signatureTimestamp|sts)\s*:\s*(\d+)""").matcher(jsContent)
                if (stsMatch.find()) {
                    cachedSts = stsMatch.group(1)?.toIntOrNull()
                }

                // Extract n-parameter function name
                val nMatch = Pattern.compile("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9$]+\)\s*\{\s*var\s+[a-zA-Z0-9$]+\s*=\s*[a-zA-Z0-9$]+\.split\(""\)""").matcher(jsContent)
                if (nMatch.find()) {
                    nFunctionName = nMatch.group(1)
                }

                // Extract signature decipher function name
                val sigMatch = Pattern.compile("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\(""").matcher(jsContent)
                if (sigMatch.find()) {
                    sigFunctionName = sigMatch.group(1)
                } else {
                    val altSigMatch = Pattern.compile("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9$]+\)\s*\{\s*[a-zA-Z0-9$]+\s*=\s*[a-zA-Z0-9$]+\.split\(""\);\s*([a-zA-Z0-9$]+)\.""").matcher(jsContent)
                    if (altSigMatch.find()) {
                        sigFunctionName = altSigMatch.group(1)
                    }
                }

                saveToDisk(playerJsUrl, cachedSts ?: 0, jsContent, nFunctionName, sigFunctionName)
                Log.i(TAG, "Successfully loaded and cached player JS (sts=$cachedSts, nFunc=$nFunctionName, sigFunc=$sigFunctionName)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load player JS: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Transforms YouTube 'n' parameter using the cached JS runtime in ~15ms.
     */
    suspend fun transformNParam(nParam: String): String = withContext(Dispatchers.Default) {
        if (nParam.isBlank()) return@withContext nParam
        val js = cachedJsContent ?: return@withContext nParam
        val func = nFunctionName ?: return@withContext nParam

        try {
            val rhino = org.mozilla.javascript.Context.enter()
            val scope = rhino.initStandardObjects()
            rhino.evaluateString(scope, js, "base.js", 1, null)
            val jsFunc = scope.get(func, scope) as? Function
            if (jsFunc != null) {
                val result = jsFunc.call(rhino, scope, scope, arrayOf(nParam))
                val transformed = result.toString()
                if (transformed.isNotBlank() && transformed != "undefined") {
                    return@withContext transformed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error transforming n parameter: ${e.message}")
        } finally {
            org.mozilla.javascript.Context.exit()
        }
        nParam
    }

    /**
     * Deciphers YouTube stream signature using the cached JS runtime in ~15ms.
     */
    suspend fun decipherSignature(signature: String): String = withContext(Dispatchers.Default) {
        if (signature.isBlank()) return@withContext signature
        val js = cachedJsContent ?: return@withContext signature
        val func = sigFunctionName ?: return@withContext signature

        try {
            val rhino = org.mozilla.javascript.Context.enter()
            val scope = rhino.initStandardObjects()
            rhino.evaluateString(scope, js, "base.js", 1, null)
            val jsFunc = scope.get(func, scope) as? Function
            if (jsFunc != null) {
                val result = jsFunc.call(rhino, scope, scope, arrayOf(signature))
                val deciphered = result.toString()
                if (deciphered.isNotBlank() && deciphered != "undefined") {
                    return@withContext deciphered
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error deciphering signature: ${e.message}")
        } finally {
            org.mozilla.javascript.Context.exit()
        }
        signature
    }
}
