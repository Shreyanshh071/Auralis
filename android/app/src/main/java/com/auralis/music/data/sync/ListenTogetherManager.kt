package com.auralis.music.data.sync

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.random.Random

data class RoomMember(
    val id: String = "",
    val name: String = "",
    val isHost: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis(),
    val avatarColorHex: String = "#7C4DFF"
)

data class NativeRoomState(
    val id: String = "",
    val code: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = 0,
    val isPlaying: Boolean = false,
    val playbackPosition: Long = 0,
    val playbackRate: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis(),
    val status: String = "active"
)

class ListenTogetherManager(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private var roomListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null

    suspend fun ensureAuthenticated(): String {
        val currentUser = auth.currentUser
        if (currentUser != null) {
            return currentUser.uid
        }
        val authResult = auth.signInAnonymously().await()
        return authResult.user?.uid ?: throw IllegalStateException("Failed to authenticate with Firebase")
    }

    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = StringBuilder()
        for (i in 0 until 6) {
            code.append(chars[Random.nextInt(chars.length)])
        }
        return code.toString()
    }

    suspend fun createRoom(
        hostDisplayName: String,
        initialTrack: Track?,
        queue: List<Track> = emptyList(),
        isPlaying: Boolean = false,
        playbackPositionMs: Long = 0L
    ): Pair<String, String> {
        val uid = ensureAuthenticated()
        val roomCode = generateRoomCode()
        val displayName = hostDisplayName.ifBlank { "Host" }

        val roomDoc = firestore.collection("rooms").document(roomCode)
        val now = System.currentTimeMillis()

        val trackMap: Map<String, Any?>? = initialTrack?.let {
            mapOf(
                "id" to it.id,
                "title" to it.title,
                "artist" to it.artist,
                "album" to it.album,
                "thumbnail" to it.thumbnail,
                "duration" to it.duration
            )
        }

        val queueList = queue.take(50).map { t ->
            mapOf(
                "id" to t.id,
                "title" to t.title,
                "artist" to t.artist,
                "album" to t.album,
                "thumbnail" to t.thumbnail,
                "duration" to t.duration
            )
        }

        // Room Data matching hasValidRoomShape() exactly
        val roomData = hashMapOf(
            "id" to roomCode,
            "code" to roomCode,
            "hostId" to uid,
            "hostName" to displayName,
            "currentTrack" to trackMap,
            "queue" to queueList,
            "queueIndex" to 0,
            "isPlaying" to isPlaying,
            "playbackPosition" to playbackPositionMs,
            "playbackRate" to 1.0,
            "updatedAt" to now,
            "status" to "active"
        )

        roomDoc.set(roomData).await()

        // Member Data matching hasValidMemberShape() exactly (id, name, isHost, lastSeen)
        val memberData = hashMapOf(
            "id" to uid,
            "name" to displayName,
            "isHost" to true,
            "lastSeen" to now,
            "joinedAt" to now,
            "avatarColorHex" to "#D4E157"
        )
        roomDoc.collection("members").document(uid).set(memberData).await()

        return Pair(roomCode, uid)
    }

    suspend fun joinRoom(roomCode: String, memberDisplayName: String): NativeRoomState {
        val uid = ensureAuthenticated()
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)

        val roomDoc = firestore.collection("rooms").document(normalizedCode)
        val snapshot = roomDoc.get().await()

        if (!snapshot.exists()) {
            throw IllegalArgumentException("Room $normalizedCode not found. Please check code.")
        }

        val status = snapshot.getString("status")
        if (status != "active") {
            throw IllegalStateException("This room is no longer active.")
        }

        val displayName = memberDisplayName.ifBlank { "Guest_${uid.take(4)}" }
        val now = System.currentTimeMillis()

        val memberData = hashMapOf(
            "id" to uid,
            "name" to displayName,
            "isHost" to false,
            "lastSeen" to now,
            "joinedAt" to now,
            "avatarColorHex" to "#D4E157"
        )
        roomDoc.collection("members").document(uid).set(memberData).await()

        return parseRoomState(snapshot)
    }

    suspend fun updateHostPlayback(
        roomCode: String,
        currentTrack: Track?,
        isPlaying: Boolean,
        playbackPositionMs: Long,
        queue: List<Track> = emptyList()
    ) {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val roomDoc = firestore.collection("rooms").document(normalizedCode)

        val trackMap: Map<String, Any?>? = currentTrack?.let {
            mapOf(
                "id" to it.id,
                "title" to it.title,
                "artist" to it.artist,
                "album" to it.album,
                "thumbnail" to it.thumbnail,
                "duration" to it.duration
            )
        }

        val updates = hashMapOf<String, Any?>(
            "currentTrack" to trackMap,
            "isPlaying" to isPlaying,
            "playbackPosition" to playbackPositionMs,
            "updatedAt" to System.currentTimeMillis()
        )

        if (queue.isNotEmpty()) {
            updates["queue"] = queue.take(50).map { t ->
                mapOf(
                    "id" to t.id,
                    "title" to t.title,
                    "artist" to t.artist,
                    "album" to t.album,
                    "thumbnail" to t.thumbnail,
                    "duration" to t.duration
                )
            }
        }

        try {
            roomDoc.update(updates).await()
            android.util.Log.d("ListenTogether", "[Host Broadcast OK] room=$normalizedCode, isPlaying=$isPlaying, pos=${playbackPositionMs}ms, track=${currentTrack?.title}")
        } catch (e: Exception) {
            android.util.Log.e("ListenTogether", "[Host Broadcast Error] failed updating room $normalizedCode: ${e.message}", e)
        }
    }

    suspend fun leaveRoom(roomCode: String, isHost: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val roomDoc = firestore.collection("rooms").document(normalizedCode)

        try {
            roomDoc.collection("members").document(uid).delete().await()
            if (isHost) {
                roomDoc.update("status", "closed").await()
            }
        } catch (e: Exception) {
            android.util.Log.e("ListenTogether", "[Leave Room Error] code=$normalizedCode, isHost=$isHost: ${e.message}", e)
        }

        stopListening()
    }

    fun observeRoomState(roomCode: String): Flow<NativeRoomState?> = callbackFlow {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val roomDoc = firestore.collection("rooms").document(normalizedCode)

        val registration = roomDoc.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) {
                trySend(null)
                return@addSnapshotListener
            }
            trySend(parseRoomState(snapshot))
        }

        roomListener = registration
        awaitClose {
            registration.remove()
        }
    }

    fun observeRoomMembers(roomCode: String): Flow<List<RoomMember>> = callbackFlow {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val membersCol = firestore.collection("rooms").document(normalizedCode).collection("members")

        val registration = membersCol.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val members = snapshot.documents.mapNotNull { doc ->
                val id = doc.getString("id") ?: doc.id
                val name = doc.getString("name") ?: "Member"
                val isHost = doc.getBoolean("isHost") ?: false
                val joinedAt = doc.getLong("joinedAt") ?: doc.getLong("lastSeen") ?: System.currentTimeMillis()
                val color = doc.getString("avatarColorHex") ?: "#D4E157"
                RoomMember(id, name, isHost, joinedAt, color)
            }
            trySend(members)
        }

        membersListener = registration
        awaitClose {
            registration.remove()
        }
    }

    fun stopListening() {
        roomListener?.remove()
        roomListener = null
        membersListener?.remove()
        membersListener = null
    }

    private fun parseRoomState(doc: DocumentSnapshot): NativeRoomState {
        val id = doc.getString("id") ?: doc.id
        val code = doc.getString("code") ?: id
        val hostId = doc.getString("hostId") ?: ""
        val hostName = doc.getString("hostName") ?: "Host"
        val isPlaying = doc.getBoolean("isPlaying") ?: false
        val playbackPosition = doc.getLong("playbackPosition") ?: 0L
        val playbackRate = (doc.getDouble("playbackRate") ?: 1.0).toFloat()
        val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
        val status = doc.getString("status") ?: "active"

        val trackRaw = doc.get("currentTrack") as? Map<*, *>
        val currentTrack = trackRaw?.let {
            Track(
                id = it["id"] as? String ?: "",
                title = it["title"] as? String ?: "",
                artist = it["artist"] as? String ?: "",
                album = it["album"] as? String ?: "",
                thumbnail = it["thumbnail"] as? String ?: "",
                duration = (it["duration"] as? Long) ?: 0L,
                source = TrackSource.YOUTUBE
            )
        }

        val queueRaw = doc.get("queue") as? List<*>
        val queue = queueRaw?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            Track(
                id = m["id"] as? String ?: "",
                title = m["title"] as? String ?: "",
                artist = m["artist"] as? String ?: "",
                album = m["album"] as? String ?: "",
                thumbnail = m["thumbnail"] as? String ?: "",
                duration = (m["duration"] as? Long) ?: 0L,
                source = TrackSource.YOUTUBE
            )
        } ?: emptyList()

        return NativeRoomState(
            id = id,
            code = code,
            hostId = hostId,
            hostName = hostName,
            currentTrack = currentTrack,
            queue = queue,
            queueIndex = (doc.getLong("queueIndex") ?: 0).toInt(),
            isPlaying = isPlaying,
            playbackPosition = playbackPosition,
            playbackRate = playbackRate,
            updatedAt = updatedAt,
            status = status
        )
    }
}
