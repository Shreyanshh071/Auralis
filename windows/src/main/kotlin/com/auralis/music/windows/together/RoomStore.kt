package com.auralis.music.windows.together

import com.auralis.music.data.together.TogetherRoomSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Where a Listen Together room lives: Firestore (rooms made by older apps, or with the room server
 * off) or the Cloudflare room server. [DesktopTogether] keeps every room rule and talks only to this.
 * Calls block, like the Firestore REST client always has; DesktopTogether makes them off the UI thread.
 */
internal interface RoomStore {
    val offsetMs: Long
    fun getRoom(code: String): JSONObject?
    fun getMember(code: String, id: String): JSONObject?
    fun listMembers(code: String): List<Pair<String, JSONObject>>
    fun listRecs(code: String): List<Pair<String, JSONObject>>
    fun createRoom(code: String, room: JSONObject, member: JSONObject)
    fun joinRoom(code: String, member: JSONObject)
    fun heartbeat(code: String, now: Long)
    /** The host's update. [stamp] asks for the server's time in serverUpdatedAt (playback broadcasts). */
    fun writeRoom(code: String, fields: JSONObject, stamp: Boolean)
    fun writeSelf(code: String, fields: JSONObject)
    fun writeNetwork(code: String, network: JSONObject)
    fun removeSelf(code: String)
    /** The host closes the room for everyone. */
    fun deleteRoom(code: String)
    fun addRec(code: String, id: String, rec: JSONObject)
    fun setRecStatus(code: String, id: String, status: String)
    fun toggleUpvote(code: String, id: String)
    fun deleteRec(code: String, id: String)
}

/** Today's Firestore rooms, unchanged. */
internal class FirestoreRoomStore(private val api: FirestoreRest) : RoomStore {
    override val offsetMs get() = api.offsetMs
    private fun expiry(now: Long = System.currentTimeMillis()) = Instant.ofEpochMilli(now + 86_400_000)
    override fun getRoom(code: String) = api.get("rooms/$code")
    override fun getMember(code: String, id: String) = api.get("rooms/$code/members/$id")
    override fun listMembers(code: String) = api.list("rooms/$code/members")
    override fun listRecs(code: String) = api.list("rooms/$code/recommendations")
    override fun createRoom(code: String, room: JSONObject, member: JSONObject) {
        api.write("rooms/$code", room.put("expireAt", expiry()), "serverUpdatedAt", merge = false, create = true)
        joinRoom(code, member)
    }
    override fun joinRoom(code: String, member: JSONObject) {
        api.write("rooms/$code/members/${api.uid}", member.put("expireAt", expiry()), "serverSeen", merge = false)
    }
    override fun heartbeat(code: String, now: Long) {
        api.write("rooms/$code/members/${api.uid}", JSONObject().put("lastSeen", now).put("expireAt", expiry(now)), "serverSeen")
    }
    override fun writeRoom(code: String, fields: JSONObject, stamp: Boolean) {
        if (stamp) api.write("rooms/$code", fields.put("expireAt", expiry()), "serverUpdatedAt") else api.write("rooms/$code", fields)
    }
    override fun writeSelf(code: String, fields: JSONObject) { api.write("rooms/$code/members/${api.uid}", fields) }
    override fun writeNetwork(code: String, network: JSONObject) {
        api.write("rooms/$code/members/${api.uid}", JSONObject().put("network", network), mask = network.keys().asSequence().map { "network.$it" }.toList())
    }
    override fun removeSelf(code: String) { api.delete("rooms/$code/members/${api.uid}") }
    override fun deleteRoom(code: String) {
        val path = "rooms/$code"
        api.write(path, JSONObject().put("status", "closed"))
        api.list("$path/recommendations").forEach { api.delete(it.first) }
        api.delete(path); api.list("$path/members").forEach { api.delete(it.first) }
    }
    override fun addRec(code: String, id: String, rec: JSONObject) { api.write("rooms/$code/recommendations/$id", rec, merge = false) }
    override fun setRecStatus(code: String, id: String, status: String) { api.write("rooms/$code/recommendations/$id", JSONObject().put("status", status)) }
    override fun toggleUpvote(code: String, id: String) {
        val path = "rooms/$code/recommendations/$id"
        val fresh = api.get(path) ?: return
        val upvotes = fresh.optJSONArray("upvotes") ?: JSONArray()
        api.transformArray(path, "upvotes", api.uid, (0 until upvotes.length()).any { upvotes.optString(it) == api.uid })
    }
    override fun deleteRec(code: String, id: String) { api.delete("rooms/$code/recommendations/$id") }
}

/**
 * The Cloudflare room server. Reads come from what the server last pushed, so the once-a-second room
 * loop costs no network at all; only real changes are sent.
 */
internal class CloudRoomStore(baseUrl: String, private val scope: CoroutineScope,
    private val http: okhttp3.OkHttpClient = okhttp3.OkHttpClient(),
    /** An ID token (anonymous is fine), refreshed when asked. */
    private val token: (refresh: Boolean) -> String?) : RoomStore {
    private val url = "${baseUrl.trimEnd('/')}/v1/together"
    private val sockets = java.util.concurrent.ConcurrentHashMap<String, TogetherRoomSocket>()
    @Volatile override var offsetMs = 0L; private set

    private fun open(code: String) = sockets.getOrPut(code) {
        TogetherRoomSocket(url, code, scope, http, log = { com.auralis.music.util.Log.d("ListenTogether", it) }) { refresh -> token(refresh) }.also { it.start() }
    }
    private fun socket(code: String) = sockets[code] ?: throw java.io.IOException("Not in room $code")
    private fun close(code: String) { sockets.remove(code)?.stop() }

    /** True when [code] is a room on the server; otherwise it is closed again and Firestore is tried. */
    fun has(code: String): Boolean {
        val socket = open(code)
        val there = runBlocking { socket.awaitLoaded(8_000L) } && socket.state.value.room != null
        if (!there) close(code)
        return there
    }

    private fun json(map: Map<String, Any?>) = TogetherRoomSocket.jsonOf(map)
    private fun map(json: JSONObject): Map<String, Any?> = TogetherRoomSocket.mapOf(JSONObject(json.toString())).filterKeys { it != "expireAt" }
    private fun track(ack: TogetherRoomSocket.Ack) { offsetMs = ack.serverNow - (ack.sentAt + (ack.ackAt - ack.sentAt) / 2) }

    override fun getRoom(code: String) = sockets[code]?.state?.value?.room?.let(::json)
    override fun getMember(code: String, id: String) = sockets[code]?.state?.value?.members?.firstOrNull { it["id"] == id }?.let(::json)
    override fun listMembers(code: String) = sockets[code]?.state?.value?.members.orEmpty().map { "rooms/$code/members/${it["id"]}" to json(it) }
    override fun listRecs(code: String) = sockets[code]?.state?.value?.recs.orEmpty().map { "rooms/$code/recommendations/${it["id"]}" to json(it) }

    override fun createRoom(code: String, room: JSONObject, member: JSONObject) {
        val socket = open(code)
        try {
            if (!runBlocking { socket.awaitLoaded(8_000L) }) throw java.io.IOException("Couldn't reach the Listen Together server")
            track(runBlocking { socket.create(map(room), map(member)) })
        } catch (e: Exception) {
            close(code)
            throw if (e is TogetherRoomSocket.Refused && e.code == "exists") IllegalStateException("That room code is taken. Try again.") else e
        }
    }
    override fun joinRoom(code: String, member: JSONObject) {
        try { track(runBlocking { open(code).join(map(member)) }) } catch (e: TogetherRoomSocket.Refused) {
            close(code)
            throw IllegalStateException(if (e.code == "full") "This room is full" else "This room has ended")
        }
    }
    override fun heartbeat(code: String, now: Long) { track(runBlocking { socket(code).updateMember(mapOf("lastSeen" to now)) }) }
    override fun writeRoom(code: String, fields: JSONObject, stamp: Boolean) { runBlocking { socket(code).updateRoom(map(fields)) } }
    override fun writeSelf(code: String, fields: JSONObject) { runBlocking { socket(code).updateMember(map(fields)) } }
    override fun writeNetwork(code: String, network: JSONObject) { runBlocking { socket(code).updateMember(mapOf("network" to map(network))) } }
    override fun removeSelf(code: String) {
        try { sockets[code]?.let { runBlocking { it.leave() } } } finally { close(code) }
    }
    override fun deleteRoom(code: String) = removeSelf(code)
    override fun addRec(code: String, id: String, rec: JSONObject) {
        val r = map(rec)
        runBlocking { socket(code).recommend(mapOf("track" to r["track"], "recommendedByName" to r["recommendedByName"], "note" to r["note"])) }
    }
    override fun setRecStatus(code: String, id: String, status: String) { runBlocking { socket(code).setRecStatus(id, status) } }
    override fun toggleUpvote(code: String, id: String) { runBlocking { socket(code).upvote(id) } }
    override fun deleteRec(code: String, id: String) { runBlocking { socket(code).deleteRec(id) } }
}
