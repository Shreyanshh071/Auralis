package com.auralis.music.data.network.discord

import android.content.Context
import android.util.Log
import com.auralis.music.data.datastore.DiscordRpcDataStore
import com.auralis.music.domain.model.DiscordRpcSettings
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DiscordGateway"
private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
private const val DISCORD_API_ME = "https://discord.com/api/v10/users/@me"
private const val DISCORD_APP_ID = "962990036020756480"

data class DiscordUser(
    val id: String,
    val username: String,
    val globalName: String?,
    val avatarUrl: String?
)

class DiscordGatewayManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: DiscordGatewayManager? = null

        fun getInstance(context: Context): DiscordGatewayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DiscordGatewayManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val dataStore = DiscordRpcDataStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var presenceTickerJob: Job? = null
    private var lastSequence: Int? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var lastPresenceUpdateTimestamp = 0L

    private var currentSettings: DiscordRpcSettings = DiscordRpcSettings()
    private var latestTrack: Track? = null
    private var latestIsPlaying: Boolean = false
    private var latestPositionMs: Long = 0L
    private var latestDurationMs: Long = 0L

    init {
        scope.launch {
            dataStore.settingsFlow.collectLatest { settings ->
                val oldSettings = currentSettings
                currentSettings = settings

                if (settings.enableRichPresence && settings.discordToken.isNotBlank()) {
                    if (!isConnected.get() && !isConnecting.get() || oldSettings.discordToken != settings.discordToken) {
                        connect(settings.discordToken)
                    } else if (isConnected.get()) {
                        pushPresence()
                    }
                    startPresenceTicker(settings.updateIntervalSeconds)
                } else if (!settings.enableRichPresence) {
                    presenceTickerJob?.cancel()
                    if (isConnected.get()) {
                        disconnect()
                    }
                }
            }
        }
    }

    private fun startPresenceTicker(intervalSeconds: Int) {
        presenceTickerJob?.cancel()
        if (intervalSeconds <= 0) return
        presenceTickerJob = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                if (isConnected.get() && currentSettings.enableRichPresence && latestTrack != null) {
                    try {
                        val player = com.auralis.music.data.service.AuralisAudioPlayer.getInstance(context)
                        latestPositionMs = player.playbackPositionMs.value
                        latestIsPlaying = player.isPlaying.value
                        latestTrack = player.currentTrack.value
                        latestDurationMs = player.durationMs.value
                        pushPresence()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun onPlaybackStateChanged(track: Track?, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val playStateChanged = (latestIsPlaying != isPlaying)
        val trackChanged = (latestTrack?.id != track?.id)

        latestTrack = track
        latestIsPlaying = isPlaying
        latestPositionMs = positionMs
        latestDurationMs = durationMs

        if (isConnected.get() && currentSettings.enableRichPresence) {
            if (playStateChanged || trackChanged) {
                pushPresence()
            }
        }
    }

    @Synchronized
    fun connect(token: String) {
        if (token.isBlank()) return
        if (isConnecting.get()) return

        disconnect()
        isConnecting.set(true)
        Log.d(TAG, "Connecting to Discord Gateway...")

        val request = Request.Builder()
            .url(GATEWAY_URL)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connection opened to Discord Gateway")
                isConnecting.set(false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleGatewayMessage(webSocket, text, token)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                isConnected.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected.set(false)
                isConnecting.set(false)
                scheduleReconnect(token)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                isConnected.set(false)
                isConnecting.set(false)
            }
        })
    }

    private fun handleGatewayMessage(ws: WebSocket, raw: String, token: String) {
        try {
            val json = JSONObject(raw)
            val op = json.optInt("op", -1)
            val seq = if (json.has("s") && !json.isNull("s")) json.getInt("s") else null
            if (seq != null) lastSequence = seq

            val eventType = json.optString("t", "")

            when (op) {
                // OP 10: HELLO -> Start heartbeats and send IDENTIFY
                10 -> {
                    val d = json.getJSONObject("d")
                    val heartbeatInterval = d.getLong("heartbeat_interval")
                    Log.d(TAG, "Gateway HELLO received. Heartbeat interval: ${heartbeatInterval}ms")
                    startHeartbeat(ws, heartbeatInterval)
                    sendIdentify(ws, token)
                }

                // OP 11: HEARTBEAT ACK
                11 -> {
                    // Log.d(TAG, "Heartbeat acknowledged by Gateway")
                }

                // OP 0: DISPATCH
                0 -> {
                    if (eventType == "READY") {
                        isConnected.set(true)
                        isConnecting.set(false)
                        Log.d(TAG, "Discord Gateway READY! Presence session established.")

                        val d = json.getJSONObject("d")
                        val userObj = d.optJSONObject("user")
                        if (userObj != null) {
                            val username = userObj.optString("username", "")
                            val globalName = userObj.optString("global_name", username)
                            val userId = userObj.optString("id", "")
                            val avatarHash = userObj.optString("avatar", "")
                            val avatarUrl = if (avatarHash.isNotBlank() && userId.isNotBlank()) {
                                "https://cdn.discordapp.com/avatars/$userId/$avatarHash.png"
                            } else {
                                ""
                            }

                            scope.launch {
                                dataStore.setLoginState(
                                    isLoggedIn = true,
                                    username = if (globalName.isNotBlank()) globalName else username,
                                    discriminator = userObj.optString("discriminator", "0"),
                                    avatarUrl = avatarUrl,
                                    token = token
                                )
                            }
                        }

                        // Push initial presence once authenticated
                        pushPresence()
                    }
                }

                // OP 1: HEARTBEAT REQUEST
                1 -> {
                    sendHeartbeat(ws)
                }

                // OP 7 (RECONNECT) or OP 9 (INVALID_SESSION)
                7, 9 -> {
                    Log.w(TAG, "Gateway requested reconnect or invalid session (op=$op)")
                    disconnect()
                    scheduleReconnect(token)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling gateway message: ${e.message}", e)
        }
    }

    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                sendHeartbeat(ws)
            }
        }
    }

    private fun sendHeartbeat(ws: WebSocket) {
        try {
            val payload = JSONObject().apply {
                put("op", 1)
                put("d", lastSequence ?: JSONObject.NULL)
            }
            ws.send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat: ${e.message}")
        }
    }

    private val externalAssetCache = ConcurrentHashMap<String, String>()

    private suspend fun resolveExternalAssets(urls: List<String>, token: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val toFetch = mutableListOf<String>()

        for (url in urls) {
            if (url.isBlank()) continue
            val cached = externalAssetCache[url]
            if (cached != null) {
                result[url] = cached
            } else {
                toFetch.add(url)
            }
        }

        if (toFetch.isEmpty() || token.isBlank()) return result

        withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().apply {
                    val jsonArray = JSONArray()
                    toFetch.forEach { jsonArray.put(it) }
                    put("urls", jsonArray)
                }
                val request = Request.Builder()
                    .url("https://discord.com/api/v10/applications/$DISCORD_APP_ID/external-assets")
                    .addHeader("Authorization", token)
                    .addHeader("Content-Type", "application/json")
                    .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseStr = response.body?.string()
                    if (!responseStr.isNullOrBlank()) {
                        val arr = JSONArray(responseStr)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val path = obj.optString("external_asset_path")
                            val origUrl = obj.optString("url")
                            if (path.isNotBlank() && origUrl.isNotBlank()) {
                                val mpVal = "mp:$path"
                                externalAssetCache[origUrl] = mpVal
                                result[origUrl] = mpVal
                                Log.d(TAG, "Successfully proxied asset '$origUrl' -> '$mpVal'")
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Discord external-assets failed (code=${response.code}): ${response.body?.string()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception resolving Discord external assets: ${e.message}")
            }
        }
        return result
    }

    private fun sendIdentify(ws: WebSocket, token: String) {
        scope.launch {
            try {
                val (osName, browserName) = when (currentSettings.platform.lowercase()) {
                    "ios" -> Pair("iOS", "Discord iOS")
                    "desktop" -> Pair("Windows", "Discord Client")
                    "web" -> Pair("Linux", "Discord Web")
                    else -> Pair("Android", "Discord Android")
                }

                val identify = JSONObject().apply {
                    put("op", 2)
                    put("d", JSONObject().apply {
                        put("token", token)
                        put("capabilities", 16381)
                        put("properties", JSONObject().apply {
                            put("os", osName)
                            put("browser", browserName)
                            put("device", "motorola edge 50 fusion")
                        })
                        put("presence", buildPresenceData(token))
                    })
                }
                ws.send(identify.toString())
                Log.d(TAG, "Sent Gateway IDENTIFY (platform=$osName)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send IDENTIFY: ${e.message}")
            }
        }
    }

    fun pushPresence() {
        val ws = webSocket ?: return
        if (!isConnected.get()) return

        val token = currentSettings.discordToken
        if (token.isBlank()) return

        scope.launch {
            try {
                val payload = JSONObject().apply {
                    put("op", 3)
                    put("d", buildPresenceData(token))
                }
                ws.send(payload.toString())
                lastPresenceUpdateTimestamp = System.currentTimeMillis()
                Log.d(TAG, "Pushed Presence Update (op=3): Payload=$payload")
            } catch (e: Exception) {
                Log.e(TAG, "Error pushing presence: ${e.message}")
            }
        }
    }

    private suspend fun buildPresenceData(token: String): JSONObject {
        val statusString = when (currentSettings.activityStatus.lowercase()) {
            "idle" -> "idle"
            "do not disturb", "dnd" -> "dnd"
            "invisible" -> "invisible"
            else -> "online"
        }

        val track = latestTrack
        val isPlaying = latestIsPlaying
        val showWhenPaused = currentSettings.showRpcWhenPaused

        val activitiesArray = JSONArray()

        if (track != null && (isPlaying || showWhenPaused)) {
            val activity = JSONObject()

            // Activity Name & Application ID
            activity.put("name", currentSettings.activityName.ifBlank { "Auralis" })
            activity.put("application_id", DISCORD_APP_ID)

            // Type (2 = Listening, 0 = Playing, 1 = Streaming, 5 = Competing)
            val activityTypeInt = when (currentSettings.activityType.lowercase()) {
                "playing" -> 0
                "streaming" -> 1
                "competing" -> 5
                else -> 2 // Listening
            }
            activity.put("type", activityTypeInt)

            // Details Line
            val detailsText = when (currentSettings.activityDetails) {
                "Song title" -> track.title
                "Artist name" -> track.artist
                "Album name", "Album" -> track.album ?: track.title
                "Auralis", "Listening on Auralis" -> "Auralis"
                "None" -> null
                else -> track.title
            }
            if (!detailsText.isNullOrBlank()) {
                activity.put("details", if (!isPlaying) "$detailsText [Paused]" else detailsText)
            }

            // State Line
            val stateText = when (currentSettings.activityState) {
                "Artist name" -> track.artist
                "Album name" -> track.album ?: track.artist
                "Song title" -> track.title
                "Auralis", "Listening on Auralis" -> "Auralis"
                "None" -> null
                else -> track.artist
            }
            if (!stateText.isNullOrBlank()) {
                activity.put("state", stateText)
            }

            // Timestamps (Start & End)
            if (isPlaying && latestDurationMs > 0L) {
                val now = System.currentTimeMillis()
                val startTime = now - latestPositionMs.coerceAtLeast(0L)
                val endTime = startTime + latestDurationMs
                activity.put("timestamps", JSONObject().apply {
                    put("start", startTime)
                    put("end", endTime)
                })
            }

            // Assets (Artwork & Icons)
            val rawThumb = track.thumbnail.trim()
            val cleanThumbnail = when {
                rawThumb.startsWith("https://") -> rawThumb
                rawThumb.startsWith("http://") -> rawThumb.replace("http://", "https://")
                rawThumb.startsWith("//") -> "https:$rawThumb"
                track.id.isNotBlank() && !track.id.startsWith("sp_") && !track.id.startsWith("spotify:") -> "https://i.ytimg.com/vi/${track.id}/hqdefault.jpg"
                rawThumb.isNotBlank() -> "https://$rawThumb"
                else -> "https://raw.githubusercontent.com/Shreyanshh071/Auralis/main/android/app/src/main/res/drawable/ic_auralis_logo.png"
            }
            val appIconUrl = "https://raw.githubusercontent.com/Shreyanshh071/Auralis/main/android/app/src/main/res/drawable/ic_auralis_logo.png"

            val rawLargeUrl = when (currentSettings.largeImage) {
                "Album artwork" -> cleanThumbnail
                "App icon", "App logo" -> appIconUrl
                "None" -> null
                else -> cleanThumbnail
            }

            val rawSmallUrl = when (currentSettings.smallImage) {
                "App logo", "App icon" -> appIconUrl
                "Artist artwork" -> cleanThumbnail
                "Play state" -> appIconUrl
                "None" -> null
                else -> null
            }

            val urlsToResolve = listOfNotNull(rawLargeUrl, rawSmallUrl).filter { it.startsWith("http") }
            val resolvedAssets = if (urlsToResolve.isNotEmpty() && token.isNotBlank()) {
                resolveExternalAssets(urlsToResolve, token)
            } else {
                emptyMap()
            }

            val assets = JSONObject()

            if (!rawLargeUrl.isNullOrBlank()) {
                val proxiedLarge = resolvedAssets[rawLargeUrl] ?: rawLargeUrl
                assets.put("large_image", proxiedLarge)

                val largeText = when (currentSettings.largeText) {
                    "Album name" -> track.album ?: track.title
                    "Song title" -> track.title
                    "Auralis" -> "Auralis"
                    "None" -> null
                    else -> track.album ?: track.title
                }
                if (!largeText.isNullOrBlank()) {
                    assets.put("large_text", largeText)
                }
            }

            if (!rawSmallUrl.isNullOrBlank() && currentSettings.smallImage != "None") {
                val proxiedSmall = resolvedAssets[rawSmallUrl] ?: rawSmallUrl
                assets.put("small_image", proxiedSmall)
                assets.put("small_text", if (isPlaying) "Playing on Auralis" else "Paused")
            }

            if (assets.length() > 0) {
                activity.put("assets", assets)
            }

            // Action Buttons
            val buttons = JSONArray().apply {
                put("Listen on YouTube Music")
                put("Go to Auralis")
            }
            activity.put("buttons", buttons)

            val metadata = JSONObject().apply {
                val buttonUrls = JSONArray().apply {
                    val ytUrl = if (track.id.isNotBlank()) "https://music.youtube.com/watch?v=${track.id}" else "https://music.youtube.com/"
                    put(ytUrl)
                    put("https://github.com/Shreyanshh071/Auralis")
                }
                put("button_urls", buttonUrls)
            }
            activity.put("metadata", metadata)

            activitiesArray.put(activity)
        }

        return JSONObject().apply {
            put("status", statusString)
            put("since", 0)
            put("activities", activitiesArray)
            put("afk", false)
        }
    }

    suspend fun verifyAndSaveToken(token: String): Result<DiscordUser> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Token cannot be empty"))
        }

        val request = Request.Builder()
            .url(DISCORD_API_ME)
            .addHeader("Authorization", cleanToken)
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful || body.isBlank()) {
                return@withContext Result.failure(Exception("Invalid Discord token or unauthorized (code=${response.code})"))
            }

            val userJson = JSONObject(body)
            val userId = userJson.getString("id")
            val username = userJson.getString("username")
            val globalName = userJson.optString("global_name", username)
            val avatarHash = userJson.optString("avatar", "")
            val avatarUrl = if (avatarHash.isNotBlank()) "https://cdn.discordapp.com/avatars/$userId/$avatarHash.png" else ""

            val user = DiscordUser(
                id = userId,
                username = if (globalName.isNotBlank()) globalName else username,
                globalName = globalName,
                avatarUrl = avatarUrl
            )

            dataStore.setLoginState(
                isLoggedIn = true,
                username = user.username,
                discriminator = userJson.optString("discriminator", "0"),
                avatarUrl = avatarUrl,
                token = cleanToken
            )
            dataStore.setEnableRichPresence(true)

            connect(cleanToken)

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    fun disconnect() {
        presenceTickerJob?.cancel()
        presenceTickerJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (_: Exception) {}
        webSocket = null
        isConnected.set(false)
        isConnecting.set(false)
        Log.d(TAG, "Disconnected from Discord Gateway")
    }

    private fun scheduleReconnect(token: String) {
        scope.launch {
            delay(5000)
            if (currentSettings.enableRichPresence && !isConnected.get()) {
                Log.d(TAG, "Reconnecting to Discord Gateway...")
                connect(token)
            }
        }
    }
}
