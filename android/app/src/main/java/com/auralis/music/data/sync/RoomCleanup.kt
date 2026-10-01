package com.auralis.music.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** UID + joinedAt bind cleanup to a departed session, not whoever currently uses the code. */
data class RoomCleanupTask(
    val id: String = UUID.randomUUID().toString(),
    val uid: String,
    val code: String,
    val host: Boolean,
    val joinedAt: Long? = null
)

fun roomCleanupStillMatches(task: RoomCleanupTask, roomExists: Boolean, hostUid: String?, memberExists: Boolean, joinedAt: Long?): Boolean {
    if (task.host && roomExists && hostUid != task.uid) return false
    return !memberExists || task.joinedAt == null || joinedAt == task.joinedAt
}

interface RoomCleanupActions {
    suspend fun sessionIsCurrent(): Boolean
    suspend fun closeRoom()
    suspend fun removeRecommendations()
    suspend fun verifyRecommendationsRemoved()
    suspend fun removeRoom()
    suspend fun removeVotes()
    suspend fun verifyVotesRemoved()
    suspend fun removeMembers()
    suspend fun verifyMembersRemoved()
}

suspend fun cleanDepartedRoom(host: Boolean, actions: RoomCleanupActions) {
    if (!actions.sessionIsCurrent()) return // A newer session must remain untouched.
    if (host) {
        actions.closeRoom()
        actions.removeRecommendations()
        actions.verifyRecommendationsRemoved()
        actions.removeRoom()
    } else {
        // Requests stay for the ongoing session; only the departing UID's votes are removed.
        actions.removeVotes()
        actions.verifyVotesRemoved()
    }
    actions.removeMembers()
    actions.verifyMembersRemoved()
}

internal class RoomCleanupPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("auralis_room_cleanup", Context.MODE_PRIVATE)
    private fun encode(task: RoomCleanupTask) = JSONObject().put("id", task.id).put("uid", task.uid)
        .put("code", task.code).put("host", task.host).put("joinedAt", task.joinedAt ?: JSONObject.NULL)
    private fun decode(json: JSONObject) = RoomCleanupTask(json.getString("id"), json.getString("uid"),
        json.getString("code"), json.getBoolean("host"), if (json.isNull("joinedAt")) null else json.getLong("joinedAt"))
    @Synchronized fun active(): RoomCleanupTask? = prefs.getString("active", null)?.let { decode(JSONObject(it)) }
    @Synchronized fun saveActive(task: RoomCleanupTask?) {
        check(prefs.edit().putString("active", task?.let { encode(it).toString() }).commit()) { "Couldn't save room session" }
    }
    @Synchronized fun pending(): List<RoomCleanupTask> {
        val array = JSONArray(prefs.getString("pending", "[]"))
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }
    @Synchronized fun enqueue(uid: String, code: String, host: Boolean): RoomCleanupTask {
        val existing = pending().firstOrNull { it.uid == uid && it.code == code }
        val task = existing ?: active()?.takeIf { it.uid == uid && it.code == code }
            ?: RoomCleanupTask(uid = uid, code = code, host = host)
        if (existing == null) savePending(pending() + task)
        if (active()?.id == task.id) saveActive(null)
        return task
    }
    @Synchronized fun remove(id: String) = savePending(pending().filterNot { it.id == id })
    @Synchronized fun forget(uid: String) {
        savePending(pending().filterNot { it.uid == uid })
        if (active()?.uid == uid) saveActive(null)
    }
    private fun savePending(tasks: List<RoomCleanupTask>) {
        val array = JSONArray(); tasks.forEach { array.put(encode(it)) }
        check(prefs.edit().putString("pending", array.toString()).commit()) { "Couldn't save cleanup retry" }
    }
}

object RoomCleanupCoordinator {
    @Volatile private var context: Context? = null
    private lateinit var store: RoomCleanupPreferences
    private val mutex = Mutex()

    @Synchronized fun init(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        store = RoomCleanupPreferences(appContext)
        // A recorded active session from an earlier process has lost its host/guest jobs.
        store.active()?.let { store.enqueue(it.uid, it.code, it.host) }
        if (store.pending().isNotEmpty()) schedule()
    }
    fun rememberActive(uid: String, code: String, host: Boolean, joinedAt: Long) {
        store.active()?.takeIf { it.uid != uid || it.code != code }?.let { store.enqueue(it.uid, it.code, it.host); schedule() }
        store.saveActive(RoomCleanupTask(uid = uid, code = code, host = host, joinedAt = joinedAt))
    }
    fun enqueue(uid: String, code: String, host: Boolean): RoomCleanupTask {
        val task = store.enqueue(uid, code, host)
        schedule()
        return task
    }
    fun clearActiveForAccountDeletion() = store.saveActive(null)
    fun discardDeletedAccount(uid: String) = store.forget(uid)
    private fun schedule() {
        val request = OneTimeWorkRequestBuilder<RoomCleanupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(checkNotNull(context)).enqueueUniqueWork("auralis-room-cleanup", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    suspend fun process(task: RoomCleanupTask, db: FirebaseFirestore = FirebaseFirestore.getInstance()): Boolean = mutex.withLock {
        if (store.pending().none { it.id == task.id }) return@withLock true
        if (FirebaseAuth.getInstance().currentUser?.uid != task.uid) return@withLock false
        try {
            // A cancelled await doesn't cancel a Firestore write; let existing writes settle first.
            db.waitForPendingWrites().await()
            cleanDepartedRoom(task.host, FirestoreRoomCleanup(db, task))
            store.remove(task.id)
            true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("ListenTogether", "Room cleanup queued for retry: ${e.javaClass.simpleName}")
            false
        }
    }
    suspend fun finishBeforeRejoin(uid: String, code: String) {
        val task = store.pending().firstOrNull { it.uid == uid && it.code == code } ?: return
        check(process(task)) { "The previous room exit is still syncing. Reconnect and try again." }
    }
    suspend fun retryCurrentAccount(): Boolean {
        if (store.pending().isEmpty()) return true
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        var done = store.pending().none { it.uid != uid }
        for (task in store.pending().filter { it.uid == uid }) if (!process(task)) done = false
        return done
    }
}

class RoomCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val done = kotlinx.coroutines.withTimeoutOrNull(120_000) { RoomCleanupCoordinator.retryCurrentAccount() } ?: false
        if (done) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Result.retry() }
}

private class FirestoreRoomCleanup(private val db: FirebaseFirestore, private val task: RoomCleanupTask) : RoomCleanupActions {
    private val room = db.collection("rooms").document(task.code)
    private val member = room.collection("members").document(task.uid)
    private var superseded = false
    private suspend fun empty(query: com.google.firebase.firestore.Query) {
        check(query.limit(1).get(Source.SERVER).await().isEmpty) { "Room cleanup incomplete" }
    }
    override suspend fun sessionIsCurrent(): Boolean {
        val root = room.get(Source.SERVER).await()
        val own = member.get(Source.SERVER).await()
        return roomCleanupStillMatches(task, root.exists(), root.getString("hostId"), own.exists(), own.getLong("joinedAt"))
    }
    override suspend fun closeRoom() {
        db.runTransaction { tx ->
            val root = tx.get(room)
            val own = tx.get(member)
            if (!roomCleanupStillMatches(task, root.exists(), root.getString("hostId"), own.exists(), own.getLong("joinedAt"))) {
                superseded = true
            } else if (root.exists()) tx.update(room, "status", "closed")
        }.await()
    }
    override suspend fun removeRecommendations() {
        if (superseded || !room.get(Source.SERVER).await().exists()) return
        scan(room.collection("recommendations")) { doc ->
            if (!superseded) db.runTransaction { tx ->
                val root = tx.get(room)
                val own = tx.get(member)
                if (!root.exists() || root.getString("status") != "closed" ||
                    !roomCleanupStillMatches(task, true, root.getString("hostId"), own.exists(), own.getLong("joinedAt"))) {
                    superseded = true
                } else tx.delete(doc.reference)
            }.await()
        }
    }
    override suspend fun verifyRecommendationsRemoved() {
        if (!superseded && room.get(Source.SERVER).await().exists()) empty(room.collection("recommendations"))
    }
    override suspend fun removeRoom() {
        if (superseded) return
        db.runTransaction { tx ->
            val root = tx.get(room)
            val own = tx.get(member)
            if (root.exists() && (root.getString("status") != "closed" ||
                !roomCleanupStillMatches(task, true, root.getString("hostId"), own.exists(), own.getLong("joinedAt")))) {
                superseded = true
            } else if (root.exists()) tx.delete(room)
        }.await()
    }
    private fun ownVotes() = room.collection("recommendations").whereArrayContains("upvotes", task.uid)
    override suspend fun removeVotes() {
        scan(ownVotes()) { doc ->
            if (!superseded) db.runTransaction { tx ->
                val own = tx.get(member)
                val current = tx.get(doc.reference)
                val votes = current.get("upvotes") as? List<*>
                if (!roomCleanupStillMatches(task, false, null, own.exists(), own.getLong("joinedAt"))) {
                    superseded = true
                } else if (current.exists() && votes?.contains(task.uid) == true) {
                    tx.update(doc.reference, "upvotes", FieldValue.arrayRemove(task.uid))
                }
            }.await()
        }
    }
    override suspend fun verifyVotesRemoved() { if (!superseded) empty(ownVotes()) }
    override suspend fun removeMembers() {
        if (superseded) return
        if (task.host) {
            scan(room.collection("members")) { doc ->
                if (!superseded) db.runTransaction { tx ->
                    val root = tx.get(room)
                    if (root.exists()) superseded = true
                    else tx.delete(doc.reference)
                }.await()
            }
        } else db.runTransaction { tx ->
            val own = tx.get(member)
            if (!roomCleanupStillMatches(task, false, null, own.exists(), own.getLong("joinedAt"))) superseded = true
            else if (own.exists()) tx.delete(member)
        }.await()
    }
    override suspend fun verifyMembersRemoved() {
        if (superseded) return
        if (task.host) empty(room.collection("members"))
        else check(!member.get(Source.SERVER).await().exists()) { "Membership cleanup incomplete" }
    }
    private suspend fun removeCollection(query: com.google.firebase.firestore.Query) = scan(query) { it.reference.delete().await() }
    private suspend fun scan(query: com.google.firebase.firestore.Query, action: suspend (com.google.firebase.firestore.DocumentSnapshot) -> Unit) {
        var cursor: com.google.firebase.firestore.DocumentSnapshot? = null
        while (true) {
            var page = query.orderBy(FieldPath.documentId()).limit(200)
            cursor?.let { page = page.startAfter(it) }
            val docs = page.get(Source.SERVER).await().documents
            if (docs.isEmpty()) return
            for (doc in docs) action(doc)
            cursor = docs.last()
        }
    }
}
