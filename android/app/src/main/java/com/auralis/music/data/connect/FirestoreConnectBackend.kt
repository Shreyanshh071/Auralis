package com.auralis.music.data.connect

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Connect on Firestore, scoped to one account: `users/{uid}/connect/{session,queue}` and
 * `users/{uid}/connectDevices/{id}`. Session writes are transactions (they fail rather than queue
 * up while offline, so a stale write is never replayed later), reads come from the server.
 */
class FirestoreConnectBackend(
    private val db: FirebaseFirestore,
    val uid: String,
    private val clock: ServerClock,
    private val localNow: () -> Long = System::currentTimeMillis
) : ConnectBackend {
    val sessionRef = db.document("users/$uid/connect/session")
    val queueRef = db.document("users/$uid/connect/queue")
    val devicesRef = db.collection("users/$uid/connectDevices")

    /** Local send / acknowledge times of our latest writes, matched to the server time when it arrives. */
    private var sessionWrite: Triple<Long, Long, Long>? = null // version, sent, ack
    private var heartbeatWrite: Triple<String, Long, Long>? = null // device id, sent, ack

    @Volatile private var queueCache: ConnectQueue? = null
    fun sawQueue(queue: ConnectQueue?) { queueCache = queue }

    override suspend fun fetch(): ConnectSnapshot {
        val s = sessionRef.get(Source.SERVER).await()
        val session = s.data?.let { ConnectCodec.decodeSession(fromFirestore(it)) }
        val queue = queueCache?.takeIf { it.rev == session?.queueRev }
            ?: queueRef.get(Source.SERVER).await().data
                ?.let { ConnectCodec.decodeQueue(fromFirestore(it)) }?.also { queueCache = it }
        return ConnectSnapshot(session, queue?.takeIf { it.rev == session?.queueRev })
    }

    override suspend fun update(block: (ConnectSnapshot) -> ConnectWrite?): ConnectSnapshot? {
        var result: ConnectSnapshot? = null
        val sent = localNow()
        db.runTransaction { tx ->
            val s = tx.get(sessionRef)
            val session = s.data?.let { ConnectCodec.decodeSession(fromFirestore(it)) }
            // Queue revisions are published atomically with the session. Matching immutable
            // server-confirmed data needs no second transaction read at each handoff step.
            val queue = queueCache?.takeIf { it.rev == session?.queueRev }
                ?: tx.get(queueRef).data?.let { ConnectCodec.decodeQueue(fromFirestore(it)) }
            val cur = ConnectSnapshot(session, queue?.takeIf { it.rev == session?.queueRev })
            val w = block(cur)
            result = null
            if (w != null) {
                tx.set(sessionRef, toFirestore(ConnectCodec.encodeSession(w.session)))
                w.queue?.let { tx.set(queueRef, toFirestore(ConnectCodec.encodeQueue(it))) }
                result = ConnectSnapshot(w.session, w.queue ?: cur.queue)
            }
            null
        }.await()
        result?.queue?.let { queueCache = it }
        result?.session?.let { sessionWrite = Triple(it.version, sent, localNow()) }
        return result
    }

    override suspend fun heartbeat(device: ConnectDevice) {
        val sent = localNow()
        devicesRef.document(device.id).set(toFirestore(ConnectCodec.encodeDevice(device))).await()
        heartbeatWrite = Triple(device.id, sent, localNow())
    }

    override suspend fun removeDevice(id: String) {
        devicesRef.document(id).delete().await()
    }

    /** A server snapshot of the session: samples the server clock if it is our own write. */
    fun sawSession(session: ConnectSession, deviceId: String) {
        val w = sessionWrite ?: return
        if (session.version == w.first && session.updatedBy == deviceId) {
            clock.sample(session.updatedAtMs, w.second, w.third)
            sessionWrite = null
        }
    }

    fun sawOwnDevice(device: ConnectDevice) {
        val w = heartbeatWrite ?: return
        if (device.id == w.first) {
            clock.sample(device.lastSeenMs, w.second, w.third)
            heartbeatWrite = null
        }
    }

    companion object {
        fun toFirestore(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) -> convertOut(v) }

        private fun convertOut(v: Any?): Any? = when (v) {
            ConnectServerTime -> FieldValue.serverTimestamp()
            is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to convertOut(x) }
            is List<*> -> v.map(::convertOut)
            else -> v
        }

        /** Firestore timestamps become epoch milliseconds, as the shared codec expects. */
        fun fromFirestore(map: Map<String, Any?>): Map<String, Any?> = map.mapValues { (_, v) -> convertIn(v) }

        private fun convertIn(v: Any?): Any? = when (v) {
            is Timestamp -> v.seconds * 1000L + v.nanoseconds / 1_000_000
            is Map<*, *> -> v.entries.associate { (k, x) -> k.toString() to convertIn(x) }
            is List<*> -> v.map(::convertIn)
            else -> v
        }
    }
}
