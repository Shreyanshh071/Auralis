package com.auralis.music.windows.together

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs

/** Desktop participant in the phone's existing rooms. Never writes another participant's data. */
class DesktopTogether(private val playback: DesktopPlaybackController?, private val api: FirestoreRest, private val scope: CoroutineScope) {
    data class Recommendation(val id: String, val track: Track, val name: String, val status: String)
    data class State(val code: String = "", val host: Boolean = false, val busy: Boolean = false,
        val members: List<String> = emptyList(), val message: String? = null, val track: String = "", val guestControls: Boolean = false,
        val recommendations: List<Recommendation> = emptyList(), val guestSongs: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var poll: Job? = null
    private var name = "Listener"
    private var sequence = System.currentTimeMillis()
    private val commands = mutableMapOf<String, Long>()
    private var lastHeartbeat = 0L
    private var lastRoster = 0L
    private var members = emptyList<Pair<String, JSONObject>>()
    private var seekVersion = 0L
    private var lastHostPosition = 0L
    private var lastHostSample = 0L
    private var lastGuestSeekVersion = -1L

    init {
        val controls: (String, Track?, Long) -> Boolean = { action, track, position ->
            val state = mutable.value
            if (state.code.isBlank() || state.host) false
            else {
                when {
                    action == "recommend" && track != null -> recommend(track)
                    action in listOf("play", "pause", "seek") && state.guestControls -> sendCommand(action, track, position)
                    action in listOf("next", "previous", "play_track") && state.guestSongs -> sendCommand(action, track, position)
                    else -> mutable.value = state.copy(message = "The host controls this action. Add a song to the queue to recommend it.")
                }
                true
            }
        }
        // Having a room service is not the same as being connected to a room.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.map { it.code.isNotBlank() }.distinctUntilChanged().collect { connected ->
                playback?.roomControl = if (connected) controls else null
            }
        }
    }

    fun create(displayName: String) = connect(displayName, null)
    fun join(code: String, displayName: String) {
        val normalized = code.trim().uppercase(java.util.Locale.ROOT)
        if (!normalized.matches(Regex("[A-Z0-9]{6}"))) { mutable.value = mutable.value.copy(message = "Enter the six-character room code"); return }
        connect(displayName, normalized)
    }

    private fun connect(displayName: String, code: String?) {
        if (mutable.value.busy || mutable.value.code.isNotEmpty()) return
        if (playback == null) { mutable.value = mutable.value.copy(message = "Audio playback is unavailable"); return }
        mutable.value = State(busy = true)
        scope.launch(Dispatchers.IO) {
            var created: String? = null
            try {
                api.authenticate()
                name = displayName.trim().take(80).ifBlank { if (code == null) "Host" else "Listener" }
                val roomCode = code ?: generateCode()
                if (code == null) {
                    api.write("rooms/$roomCode", hostFields(roomCode, true), "serverUpdatedAt", merge = false, create = true)
                    created = roomCode
                } else {
                    val room = api.get("rooms/$roomCode") ?: error("Room not found. Check the code.")
                    check(room.optString("status") == "active") { "This room has ended" }
                    val hostId = room.getString("hostId")
                    val host = api.get("rooms/$roomCode/members/$hostId")
                    check(host != null && System.currentTimeMillis() + api.offsetMs - host.optLong("serverSeen", host.optLong("lastSeen")) < 32 * 60_000) { "The host is no longer connected" }
                }
                val now = System.currentTimeMillis()
                api.write("rooms/$roomCode/members/${api.uid}", JSONObject().put("id", api.uid).put("name", name)
                    .put("isHost", code == null).put("joinedAt", now).put("lastSeen", now)
                    .put("avatarColorHex", "#D4E157").put("expireAt", Instant.ofEpochMilli(now + 86_400_000)), "serverSeen", merge = false)
                lastHeartbeat = now; lastRoster = 0; lastGuestSeekVersion = -1; commands.clear()
                mutable.value = State(code = roomCode, host = code == null)
                startPolling()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                created?.let { runCatching { api.delete("rooms/$it") } }
                mutable.value = State(message = readable(e))
            }
        }
    }

    private fun startPolling() {
        poll?.cancel()
        poll = scope.launch(Dispatchers.IO) {
            while (isActive && mutable.value.code.isNotBlank()) {
                try {
                    val state = mutable.value
                    val path = "rooms/${state.code}"
                    val now = System.currentTimeMillis()
                    // Check the parent room first: the host may have deleted our membership,
                    // which makes recommendation reads forbidden. Never recreate a dead room.
                    val guestRoom = if (!state.host) api.get(path) else null
                    if (!state.host && (guestRoom == null || guestRoom.optString("status") != "active")) {
                        runCatching { api.delete("$path/members/${api.uid}") }
                        playback?.fromRoom { playback.setPaused(true) }
                        mutable.value = State(message = "The host ended the room")
                        break
                    }
                    if (now - lastHeartbeat >= 20_000) {
                        api.write("$path/members/${api.uid}", JSONObject().put("lastSeen", now)
                            .put("expireAt", Instant.ofEpochMilli(now + 86_400_000)), "serverSeen")
                        lastHeartbeat = now
                    }
                    if (now - lastRoster >= 5000) {
                        members = api.list("$path/members")
                        val recommendations = api.list("$path/recommendations").mapNotNull { (path, raw) ->
                            raw.optJSONObject("track")?.let { Recommendation(path.substringAfterLast('/'), parseTrack(it), raw.optString("recommendedByName"), raw.optString("status")) }
                        }
                        mutable.value = mutable.value.copy(recommendations = recommendations)
                        lastRoster = now
                    }
                    if (state.host) {
                        processCommands()
                        api.write(path, hostFields(state.code, false), "serverUpdatedAt")
                    } else {
                        val room = guestRoom
                        if (room == null || room.optString("status") != "active") {
                            api.delete("$path/members/${api.uid}")
                            playback?.fromRoom { playback.setPaused(true) }
                            mutable.value = State(message = "The host ended the room")
                            break
                        }
                        val hostId = room.optString("hostId")
                        val host = members.firstOrNull { it.second.optString("id") == hostId }?.second
                        if (host != null && now + api.offsetMs - host.optLong("serverSeen", host.optLong("lastSeen")) > 32 * 60_000) error("The host disconnected")
                        follow(room)
                    }
                    mutable.value = mutable.value.copy(members = members.filter { (_, m) -> now + api.offsetMs - m.optLong("serverSeen", m.optLong("lastSeen")) < 32 * 60_000 }.map { it.second.optString("name", "Listener") },
                        track = playback?.queue?.value?.current?.title.orEmpty(), message = null)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.value = mutable.value.copy(message = "Connection interrupted: ${readable(e)}. Retrying…") }
                delay(2500)
            }
        }
    }

    private fun hostFields(code: String, initial: Boolean): JSONObject {
        val player = playback
        val q = player?.queue?.value
        val now = System.currentTimeMillis()
        val position = player?.clock?.rawPositionMs() ?: 0
        if (lastHostSample > 0 && abs(position - lastHostPosition - if (player?.clock?.isPlaying() == true) ((now - lastHostSample) * player.clock.speed()).toLong() else 0) > 1500) seekVersion++
        lastHostSample = now; lastHostPosition = position
        val offset = ((q?.index ?: 0) - 20).coerceAtLeast(0)
        val window = q?.tracks?.drop(offset)?.take(100).orEmpty()
        return JSONObject().put("currentTrack", q?.current?.let { trackJson(it).put("videoId", it.id) } ?: JSONObject.NULL)
            .put("queue", JSONArray().apply { window.forEach { put(trackJson(it)) } })
            .put("queueIndex", if (q?.current == null) -1 else q.index - offset)
            .put("isPlaying", player?.clock?.isPlaying() == true).put("playbackPosition", position)
            .put("playbackRate", player?.clock?.speed() ?: 1f).put("updatedAt", now).put("seekVersion", seekVersion)
            .put("expireAt", Instant.ofEpochMilli(now + 86_400_000)).apply {
                if (initial) {
                    put("id", code); put("code", code); put("hostId", api.uid); put("hostName", name); put("status", "active")
                    put("settings", JSONObject().put("guestsCanAddSongs", true).put("guestsCanControlPlayback", false).put("guestsCanPlaySongs", false).put("requireApproval", true))
                    put("membersList", JSONArray().put(JSONObject().put("id", api.uid).put("name", name).put("isHost", true).put("joinedAt", now).put("avatarColorHex", "#D4E157")))
                    put("memberCount", 1)
                }
            }
    }

    private fun follow(room: JSONObject) = playback?.fromRoom { followPlayback(room) }

    private fun followPlayback(room: JSONObject) {
        val player = playback ?: return
        val raw = room.optJSONObject("currentTrack") ?: run { player.setPaused(true); return }
        if (raw.optBoolean("closed")) { player.setPaused(true); return }
        val track = parseTrack(raw, current = true)
        if (track.id.isBlank()) return
        val current = player.queue.value.current
        val array = room.optJSONArray("queue") ?: JSONArray()
        val queue = List(array.length()) { parseTrack(array.getJSONObject(it)) }.toMutableList()
        val index = room.optInt("queueIndex", -1)
        if (index in queue.indices) queue[index] = track else queue.add(0, track)
        if (current?.id != track.id) {
            player.playFrom(track, queue)
            return
        }
        player.syncRoomQueue(queue, index.takeIf { it in queue.indices } ?: 0)
        if (player.resolving.value || player.state.value == VlcAudioPlayer.State.BUFFERING || player.lengthMs.value <= 0) return
        val stamp = room.optLong("serverUpdatedAt", room.optLong("updatedAt"))
        val target = estimatedPosition(room.optLong("playbackPosition"), stamp,
            room.optBoolean("isPlaying"), System.currentTimeMillis() + api.offsetMs, room.optDouble("playbackRate", 1.0))
            .coerceIn(0L, player.lengthMs.value.coerceAtLeast(1) - 1)
        val seek = room.optLong("seekVersion")
        player.setRate(room.optDouble("playbackRate", 1.0).toFloat())
        if (seek != lastGuestSeekVersion || abs(player.clock.rawPositionMs() - target) > 1500) player.seekTo(target)
        lastGuestSeekVersion = seek
        player.setPaused(!room.optBoolean("isPlaying"))
        mutable.value = mutable.value.copy(guestControls = room.optJSONObject("settings")?.optBoolean("guestsCanControlPlayback") == true,
            guestSongs = room.optJSONObject("settings")?.optBoolean("guestsCanPlaySongs") == true)
    }

    private fun processCommands() {
        val player = playback ?: return
        members.forEach { (path, member) ->
            val command = member.optJSONObject("command") ?: return@forEach
            val seq = command.optLong("seq")
            val previous = commands.put(path, seq)
            // Accept a new guest's first fresh command without replaying stale commands.
            if ((previous != null && seq <= previous) || (previous == null && seq < System.currentTimeMillis() - 30_000)) return@forEach
            val type = command.optString("type")
            if (type in listOf("play", "pause", "seek") && !mutable.value.guestControls) return@forEach
            if (type in listOf("next", "previous", "play_track") && !mutable.value.guestSongs) return@forEach
            when (type) {
                "play" -> player.setPaused(false)
                "pause" -> player.setPaused(true)
                "seek" -> player.seekTo(command.optLong("positionMs").coerceIn(0, player.lengthMs.value.coerceAtLeast(0)))
                "next" -> player.next()
                "previous" -> player.previous()
                "play_track" -> command.optJSONObject("track")?.let { player.playFrom(parseTrack(it), listOf(parseTrack(it))) }
            }
        }
    }

    fun allowGuestControls(allow: Boolean) {
        if (!mutable.value.host) return
        scope.launch(Dispatchers.IO) {
            try {
                api.write("rooms/${mutable.value.code}", JSONObject().put("settings", JSONObject().put("guestsCanAddSongs", true)
                    .put("guestsCanControlPlayback", allow).put("guestsCanPlaySongs", false).put("requireApproval", true)))
                mutable.value = mutable.value.copy(guestControls = allow)
            } catch (e: Exception) { mutable.value = mutable.value.copy(message = readable(e)) }
        }
    }

    fun togglePlayback() {
        if (mutable.value.host) { playback?.togglePlayPause(); return }
        if (!mutable.value.guestControls) return
        sendCommand(if (playback?.clock?.isPlaying() == true) "pause" else "play", null, 0)
    }

    private fun sendCommand(type: String, track: Track?, position: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                api.write("rooms/${mutable.value.code}/members/${api.uid}", JSONObject().put("command", JSONObject()
                    .put("type", type).put("positionMs", position).put("seq", ++sequence).put("track", track?.let(::trackJson) ?: JSONObject.NULL)))
            } catch (e: Exception) { mutable.value = mutable.value.copy(message = readable(e)) }
        }
    }

    private fun recommend(track: Track) {
        scope.launch(Dispatchers.IO) {
            try {
                val code = mutable.value.code
                val id = java.util.UUID.randomUUID().toString()
                api.write("rooms/$code/recommendations/$id", JSONObject().put("id", id).put("track", trackJson(track))
                    .put("recommendedByUid", api.uid).put("recommendedByName", name).put("note", "")
                    .put("upvotes", JSONArray().put(api.uid)).put("createdAt", System.currentTimeMillis()).put("status", "pending"), merge = false)
                mutable.value = mutable.value.copy(message = "Song recommended to the host")
            } catch (e: Exception) { mutable.value = mutable.value.copy(message = readable(e)) }
        }
    }

    fun decide(recommendation: Recommendation, accept: Boolean) {
        if (!mutable.value.host) return
        scope.launch(Dispatchers.IO) {
            try {
                api.write("rooms/${mutable.value.code}/recommendations/${recommendation.id}", JSONObject().put("status", if (accept) "accepted" else "declined"))
                if (accept) playback?.addToQueue(recommendation.track)
                mutable.value = mutable.value.copy(recommendations = mutable.value.recommendations.filterNot { it.id == recommendation.id })
            } catch (e: Exception) { mutable.value = mutable.value.copy(message = readable(e)) }
        }
    }

    fun leave() {
        if (mutable.value.busy) return
        scope.launch(Dispatchers.IO) { disconnect() }
    }

    suspend fun disconnect() {
        poll?.cancelAndJoin(); poll = null
        val state = mutable.value
        if (state.code.isBlank()) return
        mutable.value = state.copy(busy = true)
        try {
            val path = "rooms/${state.code}"
            if (state.host) {
                api.write(path, JSONObject().put("status", "closed"))
                api.list("$path/recommendations").forEach { api.delete(it.first) }
                api.delete(path)
                api.list("$path/members").forEach { api.delete(it.first) }
            } else api.delete("$path/members/${api.uid}")
            mutable.value = State()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = state.copy(busy = false, message = "Couldn't leave cleanly: ${readable(e)}. Try Leave again.") }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        disconnect()
        if (mutable.value.code.isEmpty()) runCatching { api.deleteAnonymousAccount() }
        Unit
    }

    companion object {
        fun generateCode(): String = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        fun estimatedPosition(position: Long, stamp: Long, playing: Boolean, now: Long, speed: Double): Long =
            position + if (playing && stamp > 0) ((now - stamp).coerceAtLeast(0) * speed).toLong() else 0
        fun trackJson(t: Track): JSONObject = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist)
            .put("album", t.album ?: JSONObject.NULL).put("thumbnail", t.thumbnail).put("duration", t.duration)
        fun parseTrack(o: JSONObject, current: Boolean = false): Track = Track(
            id = if (current) o.optString("videoId").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("id") else o.optString("id"),
            title = o.optString("title"), artist = o.optString("artist"), album = o.optString("album").takeUnless { it == "null" || it.isBlank() },
            thumbnail = o.optString("thumbnail"), duration = o.optLong("duration"))
        private fun readable(e: Exception): String = e.message?.take(200) ?: "Could not reach the room service"
    }
}
