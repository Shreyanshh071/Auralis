package com.auralis.music.data.sync

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** Each step is retryable. Never remove Auth before verified cloud cleanup. */
interface AccountDeletionActions {
    suspend fun blockRemoteWrites()
    suspend fun stopRoomSession()
    suspend fun clearRooms()
    suspend fun clearLibrary()
    suspend fun verifyCleanup()
    suspend fun deleteAuth()
}

suspend fun deleteAccountOnSpark(actions: AccountDeletionActions) {
    actions.blockRemoteWrites()
    actions.stopRoomSession()
    actions.clearRooms()
    actions.clearLibrary()
    actions.verifyCleanup()
    actions.deleteAuth()
}

class SparkAccountDeletion(
    private val db: FirebaseFirestore,
    private val user: FirebaseUser,
    private val stopSession: suspend () -> Unit
) : AccountDeletionActions {
    private val uid = user.uid
    private val userDoc = db.collection("users").document(uid)
    private fun ownedRooms() = db.collection("rooms").whereEqualTo("hostId", uid)
    private fun memberships() = db.collectionGroup("members").whereEqualTo("id", uid)
    private fun requests() = db.collectionGroup("recommendations").whereEqualTo("recommendedByUid", uid)
    private fun votes() = db.collectionGroup("recommendations").whereArrayContains("upvotes", uid)

    override suspend fun blockRemoteWrites() {
        val marker = db.collection("account_deletion_blocks").document(uid)
        db.runTransaction { transaction ->
            if (!transaction.get(marker).exists()) {
                transaction.set(marker, mapOf("requestedAt" to FieldValue.serverTimestamp()))
            }
        }.await()
    }
    override suspend fun stopRoomSession() = stopSession()

    override suspend fun clearRooms() {
        val journal = userDoc.collection("room_cleanup")
        scan(ownedRooms()) { room ->
            // A durable cloud journal remembers child cleanup if the parent deletion succeeds
            // and the process/network fails before members are removed.
            journal.document(room.id).set(mapOf("roomCode" to room.id)).await()
        }
        scan(journal) { entry ->
            val room = db.collection("rooms").document(entry.id)
            val current = room.get(Source.SERVER).await()
            if (current.exists()) {
                // Never delete a different host's room if a code has since been reused.
                check(current.getString("hostId") == uid) { "Room code was reused; manual cleanup required" }
                room.update("status", "closed").await()
                deleteCollection(room.collection("recommendations"))
                requireEmpty(room.collection("recommendations"))
                room.delete().await()
            }
            deleteCollection(room.collection("members"))
            requireEmpty(room.collection("members"))
            entry.reference.delete().await()
        }
        scan(requests()) { doc -> if (isRoomChild(doc.reference, "recommendations")) doc.reference.delete().await() }
        scan(votes()) { doc ->
            if (isRoomChild(doc.reference, "recommendations")) {
                // Transaction won't recreate a recommendation concurrently deleted by its host.
                db.runTransaction { tx ->
                    if (tx.get(doc.reference).exists()) tx.update(doc.reference, "upvotes", FieldValue.arrayRemove(uid))
                }.await()
            }
        }
        scan(memberships()) { doc -> if (isRoomChild(doc.reference, "members")) doc.reference.delete().await() }
    }

    override suspend fun clearLibrary() {
        deleteCollection(userDoc.collection("listening"))
        userDoc.delete().await()
    }
    override suspend fun verifyCleanup() {
        check(!userDoc.get(Source.SERVER).await().exists()) { "Library deletion is pending" }
        requireEmpty(userDoc.collection("listening"))
        requireEmpty(ownedRooms())
        requireEmpty(userDoc.collection("room_cleanup"))
        for ((query, group) in listOf(memberships() to "members", requests() to "recommendations", votes() to "recommendations")) {
            scan(query) { doc -> check(!isRoomChild(doc.reference, group)) { "Room cleanup is pending" } }
        }
    }
    override suspend fun deleteAuth() { user.delete().await() }

    private suspend fun requireEmpty(query: Query) {
        check(query.limit(1).get(Source.SERVER).await().isEmpty) { "Cloud cleanup is pending" }
    }
    private suspend fun deleteCollection(query: Query) = scan(query) { it.reference.delete().await() }

    private suspend fun scan(query: Query, action: suspend (DocumentSnapshot) -> Unit) {
        var cursor: DocumentSnapshot? = null
        while (true) {
            var page = query.orderBy(FieldPath.documentId()).limit(200)
            cursor?.let { page = page.startAfter(it) }
            val docs = page.get(Source.SERVER).await().documents
            if (docs.isEmpty()) return
            for (doc in docs) action(doc)
            cursor = docs.last()
        }
    }
    private fun isRoomChild(ref: DocumentReference, group: String): Boolean {
        val parts = ref.path.split('/')
        return parts.size == 4 && parts[0] == "rooms" && parts[2] == group
    }
}
