package com.auralis.music.data.sync

import com.auralis.music.data.together.TogetherRoomSocket
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Listen Together rooms on the Cloudflare room server. On only in builds made with
 * `together.sync.url` (BuildConfig.TOGETHER_URL); blank keeps every room on Firestore. New rooms go to
 * the server when it is on; joining tries the server first and falls back to Firestore, so rooms made
 * by older app versions still work.
 */
object ListenTogetherCloud {
    private const val TAG = "ListenTogether"
    val baseUrl: String get() = com.auralis.music.BuildConfig.TOGETHER_URL
    val enabled: Boolean get() = baseUrl.isNotBlank()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { okhttp3.OkHttpClient() }
    private val sockets = ConcurrentHashMap<String, TogetherRoomSocket>()

    /** The open connection for [code], if that room is on the server. */
    fun socket(code: String): TogetherRoomSocket? = sockets[code]

    fun open(code: String): TogetherRoomSocket = sockets.getOrPut(code) {
        TogetherRoomSocket("${baseUrl.trimEnd('/')}/v1/together", code, scope, http, log = { android.util.Log.d(TAG, it) }) { refresh ->
            FirebaseAuth.getInstance().currentUser?.getIdToken(refresh)?.await()?.token
        }.also { it.start() }
    }

    fun close(code: String) { sockets.remove(code)?.stop() }

    /** Leaves without waiting: the app may be going away. */
    fun leaveInBackground(code: String) {
        val socket = sockets.remove(code) ?: return
        scope.launch {
            try { withTimeoutOrNull(2_500L) { socket.leave() } } catch (_: Exception) { } finally { socket.stop() }
        }
    }

    /** What a room that won't start can show: whether the room server answers at all. */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder().url("${baseUrl.trimEnd('/')}/healthz").build()
            val client = http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { if (it.isSuccessful) "The server answered, so it may just be slow right now" else "The Listen Together server answered ${it.code}" }
        } catch (e: java.io.InterruptedIOException) {
            "No response from the server (offline, or the network is blocking it)"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Firestore's server-filled fields have no meaning here: the server stamps its own. */
    fun withoutServerFields(map: Map<String, Any?>): Map<String, Any?> = map.filterKeys { it != "serverUpdatedAt" && it != "serverSeen" && it != "expireAt" }

    private fun long(v: Any?): Long? = (v as? Number)?.toLong()

    private fun track(raw: Any?): Track? {
        val m = raw as? Map<*, *> ?: return null
        return Track(
            id = m["id"] as? String ?: "",
            title = m["title"] as? String ?: "",
            artist = m["artist"] as? String ?: "",
            album = m["album"] as? String ?: "",
            thumbnail = m["thumbnail"] as? String ?: "",
            duration = long(m["duration"]) ?: 0L,
            source = TrackSource.YOUTUBE
        )
    }

    fun room(doc: Map<String, Any?>): NativeRoomState {
        val id = doc["id"] as? String ?: ""
        val trackRaw = doc["currentTrack"] as? Map<*, *>
        val closing = doc["closing"] as? Map<*, *>
        return NativeRoomState(
            id = id,
            code = doc["code"] as? String ?: id,
            hostId = doc["hostId"] as? String ?: "",
            hostName = doc["hostName"] as? String ?: "Host",
            currentTrack = track(trackRaw),
            queue = (doc["queue"] as? List<*>).orEmpty().mapNotNull(::track),
            queueIndex = long(doc["queueIndex"])?.toInt() ?: 0,
            isPlaying = doc["isPlaying"] as? Boolean ?: false,
            playbackPosition = long(doc["playbackPosition"]) ?: 0L,
            playbackRate = (doc["playbackRate"] as? Number)?.toFloat() ?: 1.0f,
            updatedAt = long(doc["updatedAt"]) ?: System.currentTimeMillis(),
            serverUpdatedAt = long(doc["serverUpdatedAt"]),
            seekVersion = long(doc["seekVersion"]) ?: 0L,
            status = doc["status"] as? String ?: "active",
            membersList = (doc["membersList"] as? List<*>).orEmpty().mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val mId = m["id"] as? String ?: return@mapNotNull null
                RoomMember(mId, m["name"] as? String ?: "Member", m["isHost"] as? Boolean ?: false,
                    long(m["joinedAt"]) ?: System.currentTimeMillis(), m["avatarColorHex"] as? String ?: "#D4E157")
            },
            settings = RoomSettings.from(doc["settings"]),
            currentVideoId = (trackRaw?.get("videoId") as? String)?.takeIf { it.isNotBlank() },
            closingAtMs = long(closing?.get("at")),
            closingReason = closing?.get("reason") as? String,
            playerClosed = trackRaw?.get("closed") as? Boolean ?: false,
            activity = (doc["activity"] as? List<*>).orEmpty().mapNotNull(RoomActivity::from)
        )
    }

    fun member(doc: Map<String, Any?>): RoomMember {
        val id = doc["id"] as? String ?: ""
        val command = (doc["command"] as? Map<*, *>)?.let { c ->
            val type = c["type"] as? String ?: return@let null
            GuestCommand(
                type = type,
                positionMs = long(c["positionMs"]) ?: 0L,
                seq = long(c["seq"]) ?: 0L,
                fromIndex = long(c["fromIndex"])?.toInt() ?: -1,
                toIndex = long(c["toIndex"])?.toInt() ?: -1,
                queueIds = (c["queueIds"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
                track = (c["track"] as? Map<*, *>)?.takeIf { it["id"] is String }?.let(::track)
            )
        }
        val network = doc["network"] as? Map<*, *>
        return RoomMember(
            id = id,
            name = doc["name"] as? String ?: "Member",
            isHost = doc["isHost"] as? Boolean ?: false,
            joinedAt = long(doc["joinedAt"]) ?: long(doc["lastSeen"]) ?: System.currentTimeMillis(),
            avatarColorHex = doc["avatarColorHex"] as? String ?: "#D4E157",
            lastSeenServerMs = long(doc["serverSeen"]),
            command = command,
            hasSlowConnection = network?.get("slow") as? Boolean ?: false,
            readyForTrackId = network?.get("readyFor") as? String
        )
    }

    fun recommendation(doc: Map<String, Any?>): RoomRecommendation? {
        val track = track(doc["track"]) ?: return null
        return RoomRecommendation(
            id = doc["id"] as? String ?: return null,
            track = track,
            recommendedByUid = doc["recommendedByUid"] as? String ?: "",
            recommendedByName = doc["recommendedByName"] as? String ?: "Listener",
            note = doc["note"] as? String ?: "",
            upvotes = (doc["upvotes"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
            createdAt = long(doc["createdAt"]) ?: System.currentTimeMillis(),
            status = doc["status"] as? String ?: "pending"
        )
    }
}
