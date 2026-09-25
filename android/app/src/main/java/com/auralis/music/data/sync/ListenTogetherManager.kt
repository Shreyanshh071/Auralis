package com.auralis.music.data.sync

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.random.Random

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source

data class RoomMember(
    val id: String = "",
    val name: String = "",
    val isHost: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis(),
    val avatarColorHex: String = "#7C4DFF",
    /** Server time of the member's last heartbeat; null for records written by older app versions. */
    val lastSeenServerMs: Long? = null
)

data class RoomRecommendation(
    val id: String = "",
    val track: Track = Track(),
    val recommendedByUid: String = "",
    val recommendedByName: String = "",
    val note: String = "",
    val upvotes: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "pending" // "pending", "accepted", "played", "declined"
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
    /** Server time of the host's last broadcast; null until the server has stamped it. */
    val serverUpdatedAt: Long? = null,
    /** Bumped by the host on every seek so listeners jump immediately. */
    val seekVersion: Long = 0,
    val status: String = "active",
    val membersList: List<RoomMember> = emptyList()
)

class ListenTogetherManager(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        @Volatile
        var activeRoomCode: String? = null
            private set

        @Volatile
        var isHostUser: Boolean = false
            private set

        fun performTaskRemovedCleanup() {
            val roomCode = activeRoomCode ?: return
            val isHost = isHostUser
            val uid = try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null } ?: return
            try {
                val db = FirebaseFirestore.getInstance()
                val roomDoc = db.collection("rooms").document(roomCode)
                // Guests only remove their member record: the room document is host-only.
                roomDoc.collection("members").document(uid).delete()
                if (isHost) {
                    roomDoc.update("status", "closed")
                }
                android.util.Log.d("ListenTogether", "[TaskRemoved Cleanup] Successfully cleaned up room=$roomCode, isHost=$isHost")
            } catch (e: Exception) {
                android.util.Log.e("ListenTogether", "[TaskRemoved Cleanup Error]: ${e.message}")
            } finally {
                activeRoomCode = null
                isHostUser = false
            }
        }
    }

    private var roomListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null
    private var recommendationsListener: ListenerRegistration? = null

    /** Clock offset measured by the most recent [joinRoom] or [createRoom]. */
    @Volatile
    var lastJoinClockOffset: ClockOffsetSample? = null
        private set

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

    fun getAuthenticatedDisplayName(): String {
        val currentUser = auth.currentUser ?: return ""
        val name = currentUser.displayName?.takeIf { it.isNotBlank() && !isGenericListenerName(it) }
            ?: currentUser.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
        return name ?: ""
    }

    private fun isGenericListenerName(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower == "listener" || lower == "guest listener" || lower == "auralis listener"
    }

    suspend fun createRoom(
        hostDisplayName: String,
        initialTrack: Track?,
        queue: List<Track> = emptyList(),
        queueIndex: Int = 0,
        isPlaying: Boolean = false,
        playbackPositionMs: Long = 0L
    ): Pair<String, String> {
        val uid = ensureAuthenticated()
        val roomCode = generateRoomCode()
        val displayName = hostDisplayName.trim().takeIf { it.isNotBlank() && !isGenericListenerName(it) }
            ?: getAuthenticatedDisplayName().takeIf { it.isNotBlank() }
            ?: "Host"

        val roomDoc = firestore.collection("rooms").document(roomCode)
        val now = System.currentTimeMillis()

        val trackMap = initialTrack?.let(::trackToMap)
        val (queueWindow, windowIndex) = ListenTogetherSyncMath.queueWindow(queue, queueIndex)

        val hostMemberMap = mapOf(
            "id" to uid,
            "name" to displayName,
            "isHost" to true,
            "joinedAt" to now,
            "avatarColorHex" to "#D4E157"
        )

        // Room Data matching hasValidRoomShape() exactly
        val roomData = hashMapOf(
            "id" to roomCode,
            "code" to roomCode,
            "hostId" to uid,
            "hostName" to displayName,
            "currentTrack" to trackMap,
            "queue" to queueWindow.map(::trackToMap),
            "queueIndex" to windowIndex,
            "isPlaying" to isPlaying,
            "playbackPosition" to playbackPositionMs,
            "playbackRate" to 1.0,
            "updatedAt" to now,
            "serverUpdatedAt" to FieldValue.serverTimestamp(),
            "seekVersion" to 0L,
            "status" to "active",
            "membersList" to listOf(hostMemberMap),
            "memberCount" to 1
        )

        roomDoc.set(roomData).await()

        // Member Data matching hasValidMemberShape() exactly (id, name, isHost, lastSeen)
        val memberData = hashMapOf(
            "id" to uid,
            "name" to displayName,
            "isHost" to true,
            "lastSeen" to now,
            "serverSeen" to FieldValue.serverTimestamp(),
            "joinedAt" to now,
            "avatarColorHex" to "#D4E157"
        )
        val memberDoc = roomDoc.collection("members").document(uid)
        val sentAt = System.currentTimeMillis()
        memberDoc.set(memberData).await()
        lastJoinClockOffset = readClockOffset(memberDoc, sentAt, System.currentTimeMillis())
        activeRoomCode = roomCode
        isHostUser = true

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

        val displayName = memberDisplayName.trim().takeIf { it.isNotBlank() && !isGenericListenerName(it) }
            ?: getAuthenticatedDisplayName().takeIf { it.isNotBlank() }
            ?: "User_${uid.take(4)}"
        val now = System.currentTimeMillis()

        val memberData = hashMapOf(
            "id" to uid,
            "name" to displayName,
            "isHost" to false,
            "lastSeen" to now,
            "serverSeen" to FieldValue.serverTimestamp(),
            "joinedAt" to now,
            "avatarColorHex" to "#D4E157"
        )

        // The members subcollection is the roster. The room document is host-only in the
        // security rules, so guests never write to it.
        val memberDoc = roomDoc.collection("members").document(uid)
        val sentAt = System.currentTimeMillis()
        memberDoc.set(memberData).await()
        val offset = readClockOffset(memberDoc, sentAt, System.currentTimeMillis())

        // A host whose app died without closing the room stops checking in; don't join a dead room.
        val hostId = snapshot.getString("hostId").orEmpty()
        if (offset != null && hostId.isNotBlank()) {
            val hostDoc = try {
                roomDoc.collection("members").document(hostId).get(Source.SERVER).await()
            } catch (_: Exception) { null }
            val hostGone = hostDoc != null && (!hostDoc.exists() ||
                ListenTogetherSyncMath.isPresenceStale(
                    lastSeenServerMs = hostDoc.getTimestamp("serverSeen")?.toDate()?.time,
                    nowServerMs = System.currentTimeMillis() + offset.offsetMs
                ))
            if (hostGone) {
                try { memberDoc.delete().await() } catch (_: Exception) {}
                throw IllegalStateException("This room is no longer active.")
            }
        }

        lastJoinClockOffset = offset
        activeRoomCode = normalizedCode
        isHostUser = false

        return parseRoomState(snapshot)
    }

    /**
     * Refreshes this participant's presence record and measures the local clock against the server.
     * Returns null when the write or the read-back fails (e.g. offline).
     */
    suspend fun heartbeat(roomCode: String): ClockOffsetSample? {
        val uid = auth.currentUser?.uid ?: return null
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val memberDoc = firestore.collection("rooms").document(normalizedCode)
            .collection("members").document(uid)
        return try {
            val sentAt = System.currentTimeMillis()
            memberDoc.update(
                "lastSeen", sentAt,
                "serverSeen", FieldValue.serverTimestamp()
            ).await()
            readClockOffset(memberDoc, sentAt, System.currentTimeMillis())
        } catch (e: Exception) {
            android.util.Log.w("ListenTogether", "[Heartbeat] failed for room=$normalizedCode: ${e.message}")
            null
        }
    }

    /** Reads back the server stamp of a write acknowledged at [ackAt] to measure the clock offset. */
    private suspend fun readClockOffset(memberDoc: DocumentReference, sentAt: Long, ackAt: Long): ClockOffsetSample? {
        return try {
            val serverStamp = memberDoc.get(Source.SERVER).await()
                .getTimestamp("serverSeen")?.toDate()?.time ?: return null
            ListenTogetherSyncMath.clockOffsetSample(sentAt, ackAt, serverStamp)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun updateHostPlayback(
        roomCode: String,
        currentTrack: Track?,
        isPlaying: Boolean,
        playbackPositionMs: Long,
        queue: List<Track> = emptyList(),
        queueIndex: Int = -1,
        seekVersion: Long? = null
    ) {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val roomDoc = firestore.collection("rooms").document(normalizedCode)

        val updates = hashMapOf<String, Any?>(
            "currentTrack" to currentTrack?.let(::trackToMap),
            "isPlaying" to isPlaying,
            "playbackPosition" to playbackPositionMs,
            "updatedAt" to System.currentTimeMillis(),
            "serverUpdatedAt" to FieldValue.serverTimestamp()
        )

        if (queue.isNotEmpty()) {
            val hostIndex = if (queue.getOrNull(queueIndex)?.id == currentTrack?.id) queueIndex
                else queue.indexOfFirst { it.id == currentTrack?.id }.coerceAtLeast(0)
            val (window, windowIndex) = ListenTogetherSyncMath.queueWindow(queue, hostIndex)
            updates["queue"] = window.map(::trackToMap)
            updates["queueIndex"] = windowIndex
        }
        if (seekVersion != null) {
            updates["seekVersion"] = seekVersion
        }

        try {
            roomDoc.update(updates).await()
            android.util.Log.d("ListenTogether", "[Host Broadcast OK] room=$normalizedCode, isPlaying=$isPlaying, pos=${playbackPositionMs}ms, track=${currentTrack?.title}, seekVersion=$seekVersion")
        } catch (e: Exception) {
            android.util.Log.e("ListenTogether", "[Host Broadcast Error] failed updating room $normalizedCode: ${e.message}", e)
        }
    }

    suspend fun leaveRoom(roomCode: String, isHost: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val roomDoc = firestore.collection("rooms").document(normalizedCode)

        try {
            // Guests only remove their member record: the room document is host-only.
            roomDoc.collection("members").document(uid).delete().await()
            if (isHost) {
                roomDoc.update("status", "closed").await()
            }
        } catch (e: Exception) {
            android.util.Log.e("ListenTogether", "[Leave Room Error] code=$normalizedCode, isHost=$isHost: ${e.message}", e)
        } finally {
            activeRoomCode = null
            isHostUser = false
        }

        stopListening()
    }

    fun observeRoomState(roomCode: String): Flow<NativeRoomState?> = callbackFlow {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val roomDoc = firestore.collection("rooms").document(normalizedCode)

        val registration = roomDoc.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
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

        val registration = membersCol.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null || snapshot == null) {
                // Keep the last good roster instead of wiping everyone on a transient error.
                return@addSnapshotListener
            }
            val members = snapshot.documents.mapNotNull { doc ->
                val id = doc.getString("id") ?: doc.id
                val name = doc.getString("name") ?: "Member"
                val isHost = doc.getBoolean("isHost") ?: false
                val joinedAt = doc.getLong("joinedAt") ?: doc.getLong("lastSeen") ?: System.currentTimeMillis()
                val color = doc.getString("avatarColorHex") ?: "#D4E157"
                val lastSeenServer = doc.getTimestamp(
                    "serverSeen",
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )?.toDate()?.time
                RoomMember(id, name, isHost, joinedAt, color, lastSeenServer)
            }
            trySend(members)
        }

        membersListener = registration
        awaitClose {
            registration.remove()
        }
    }

    suspend fun recommendSong(
        roomCode: String,
        track: Track,
        note: String = "",
        recommenderName: String = ""
    ): String {
        val uid = ensureAuthenticated()
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val recCol = firestore.collection("rooms").document(normalizedCode).collection("recommendations")
        val recDoc = recCol.document()
        val docId = recDoc.id
        val now = System.currentTimeMillis()
        val displayName = recommenderName.ifBlank { "Guest_${uid.take(4)}" }

        val recData = hashMapOf(
            "id" to docId,
            "track" to trackToMap(track),
            "recommendedByUid" to uid,
            "recommendedByName" to displayName,
            "note" to note.trim(),
            "upvotes" to listOf(uid), // Initial upvote from recommender
            "createdAt" to now,
            "status" to "pending"
        )

        recDoc.set(recData).await()
        return docId
    }

    fun observeRecommendations(roomCode: String): Flow<List<RoomRecommendation>> = callbackFlow {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val recCol = firestore.collection("rooms")
            .document(normalizedCode)
            .collection("recommendations")

        val registration = recCol.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null || snapshot == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snapshot.documents.mapNotNull { doc ->
                parseRecommendation(doc)
            }.sortedWith(
                compareByDescending<RoomRecommendation> { it.upvotes.size }
                    .thenByDescending { it.createdAt }
            )
            trySend(list)
        }

        recommendationsListener = registration
        awaitClose {
            registration.remove()
        }
    }

    suspend fun upvoteRecommendation(roomCode: String, recommendationId: String) {
        val uid = ensureAuthenticated()
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val recDoc = firestore.collection("rooms")
            .document(normalizedCode)
            .collection("recommendations")
            .document(recommendationId)

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(recDoc)
            if (snapshot.exists()) {
                val currentUpvotes = (snapshot.get("upvotes") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                if (currentUpvotes.contains(uid)) {
                    transaction.update(recDoc, "upvotes", FieldValue.arrayRemove(uid))
                } else {
                    transaction.update(recDoc, "upvotes", FieldValue.arrayUnion(uid))
                }
            }
        }.await()
    }

    suspend fun updateRecommendationStatus(roomCode: String, recommendationId: String, status: String) {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val recDoc = firestore.collection("rooms")
            .document(normalizedCode)
            .collection("recommendations")
            .document(recommendationId)

        try {
            recDoc.update("status", status).await()
        } catch (e: Exception) {
            android.util.Log.e("ListenTogether", "Failed updating recommendation status: ${e.message}", e)
        }
    }

    suspend fun deleteRecommendation(roomCode: String, recommendationId: String) {
        val normalizedCode = roomCode.trim().uppercase(Locale.ROOT)
        val recDoc = firestore.collection("rooms")
            .document(normalizedCode)
            .collection("recommendations")
            .document(recommendationId)

        try {
            recDoc.delete().await()
        } catch (e: Exception) {
            android.util.Log.e("ListenTogether", "Failed deleting recommendation: ${e.message}", e)
        }
    }

    fun stopListening() {
        roomListener?.remove()
        roomListener = null
        membersListener?.remove()
        membersListener = null
        recommendationsListener?.remove()
        recommendationsListener = null
    }

    private fun trackToMap(track: Track): Map<String, Any?> = mapOf(
        "id" to track.id,
        "title" to track.title,
        "artist" to track.artist,
        "album" to track.album,
        "thumbnail" to track.thumbnail,
        "duration" to track.duration
    )

    private fun parseRecommendation(doc: DocumentSnapshot): RoomRecommendation? {
        val id = doc.getString("id") ?: doc.id
        val recommendedByUid = doc.getString("recommendedByUid") ?: ""
        val recommendedByName = doc.getString("recommendedByName") ?: "Listener"
        val note = doc.getString("note") ?: ""
        val createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
        val status = doc.getString("status") ?: "pending"
        val upvotesRaw = doc.get("upvotes") as? List<*>
        val upvotes = upvotesRaw?.filterIsInstance<String>() ?: emptyList()

        val trackRaw = doc.get("track") as? Map<*, *> ?: return null
        val track = Track(
            id = trackRaw["id"] as? String ?: "",
            title = trackRaw["title"] as? String ?: "",
            artist = trackRaw["artist"] as? String ?: "",
            album = trackRaw["album"] as? String ?: "",
            thumbnail = trackRaw["thumbnail"] as? String ?: "",
            duration = (trackRaw["duration"] as? Long) ?: 0L,
            source = TrackSource.YOUTUBE
        )

        return RoomRecommendation(
            id = id,
            track = track,
            recommendedByUid = recommendedByUid,
            recommendedByName = recommendedByName,
            note = note,
            upvotes = upvotes,
            createdAt = createdAt,
            status = status
        )
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
        val serverUpdatedAt = doc.getTimestamp("serverUpdatedAt")?.toDate()?.time
        val seekVersion = doc.getLong("seekVersion") ?: 0L
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

        val membersRaw = doc.get("membersList") as? List<*>
        val membersList = membersRaw?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val mId = m["id"] as? String ?: return@mapNotNull null
            val mName = m["name"] as? String ?: "Member"
            val mIsHost = m["isHost"] as? Boolean ?: false
            val mJoinedAt = (m["joinedAt"] as? Long) ?: System.currentTimeMillis()
            val mColor = m["avatarColorHex"] as? String ?: "#D4E157"
            RoomMember(mId, mName, mIsHost, mJoinedAt, mColor)
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
            serverUpdatedAt = serverUpdatedAt,
            seekVersion = seekVersion,
            status = status,
            membersList = membersList
        )
    }
}
