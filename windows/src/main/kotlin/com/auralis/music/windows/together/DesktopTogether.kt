package com.auralis.music.windows.together

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.data.sync.*
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs

/** Android's room protocol with the desktop transport and playback boundary. */
class DesktopTogether internal constructor(private val playback: RoomPlayback?, private val api: FirestoreRest,
    private val scope: CoroutineScope, private val pollMs: Long) {
    constructor(playback: DesktopPlaybackController?, api: FirestoreRest, scope: CoroutineScope) :
        this(playback?.let(::VlcRoomPlayback), api, scope, 1000L)
    data class Recommendation(val id: String, val track: Track, val name: String, val status: String,
        val uid: String = "", val note: String = "", val upvotes: List<String> = emptyList(), val createdAt: Long = 0)
    data class SongRequest(val id: String, val name: String, val track: Track,
        val command: GuestCommand? = null, val recommendation: Recommendation? = null)
    data class State(val code: String = "", val host: Boolean = false, val busy: Boolean = false,
        val members: List<String> = emptyList(), val message: String? = null, val track: String = "",
        val recommendations: List<Recommendation> = emptyList(), val settings: RoomSettings = RoomSettings(),
        val memberDetails: List<RoomMember> = emptyList(), val uid: String = "", val hostName: String = "",
        val requests: List<SongRequest> = emptyList(), val searchResults: List<Track> = emptyList(),
        val searching: Boolean = false, val closingAtMs: Long? = null, val closingReason: String? = null,
        val waitingForGuests: Boolean = false, val poorConnection: Boolean = false,
        val activity: List<RoomActivity> = emptyList(),
        val currentTrack: Track? = null, val isPlaying: Boolean = false) {
        val guestControls get() = settings.guestsCanControlPlayback
        val guestSongs get() = settings.guestsMayPlaySongs
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    var searchSongs: suspend (String) -> List<Track> = { com.auralis.music.data.network.InnerTubeClient().searchForPlayback(it) }
    private val gate = Mutex()
    private var poll: Job? = null
    private var searchJob: Job? = null
    private var hostSettings = RoomSettings()
    private var name = "Listener"
    private var sequence = System.currentTimeMillis()
    private var lastHeartbeat = 0L
    private var lastRoster = 0L
    private var roster = emptyList<Pair<String, JSONObject>>()
    private val commandTracker = GuestCommandTracker()
    private val handledRecommendations = mutableSetOf<String>()
    private val handledRequests = mutableSetOf<String>()
    private var seekVersion = 0L
    private var lastGuestSeekVersion = -1L
    private var lastHostPosition = 0L
    private var lastHostSample = 0L
    private var lastBroadcast = ""
    private var lastBroadcastAt = 0L
    private var lastTrackChange = 0L
    @Volatile private var heldTrack: String? = null
    private var heldAt = 0L
    private var lastReady: String? = null
    private var lastCorrection = 0L
    private var seekLead = ListenTogetherSyncMath.SEEK_LEAD_MS
    private var seekPending = false
    private var aloneSince: Long? = null
    private var idleSince: Long? = null
    private var closingAt: Long? = null
    private var closingReason: String? = null
    private var stalledTrack: String? = null
    private var stall = PlaybackStallDetector()
    private var lastPoor = false
    private fun operation(block: suspend () -> Unit): Job = scope.launch(Dispatchers.IO) {
        gate.withLock { try { block() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(busy = false, message = readable(e)) } } }
    }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun create(displayName: String) = connect(displayName, null)
    fun join(code: String, displayName: String) {
        val normalized = code.trim().uppercase(java.util.Locale.ROOT)
        if (!normalized.matches(Regex("[A-Z0-9]{6}"))) { mutable.update { it.copy(message = "Enter the six-character room code") }; return }
        connect(displayName, normalized)
    }
    private fun connect(displayName: String, code: String?) {
        if (mutable.value.busy || mutable.value.code.isNotEmpty()) return
        if (playback == null) { mutable.update { it.copy(message = "Audio playback is unavailable") }; return }
        mutable.update { it.copy(busy = true, message = null) }
        operation {
            var created: String? = null
            try {
                api.authenticate()
                name = displayName.trim().take(80).ifBlank { if(code == null) "Host" else "Listener" }
                val roomCode = code ?: generateCode()
                var remote: JSONObject? = null
                if (code == null) {
                    mutable.update { it.copy(host = true, settings = hostSettings, hostName = name, uid = api.uid) }
                    api.write("rooms/$roomCode", hostFields(roomCode, true), "serverUpdatedAt", merge = false, create = true)
                    created = roomCode
                } else {
                    remote = api.get("rooms/$roomCode") ?: error("Room not found. Check the code.")
                    check(remote.optString("status") == "active") { "This room has ended" }
                    val host = api.get("rooms/$roomCode/members/${remote.getString("hostId")}")
                    check(host != null && !ListenTogetherSyncMath.isPresenceStale(timestamp(host,"serverSeen","lastSeen"),
                        System.currentTimeMillis()+api.offsetMs)) { "The host is no longer connected" }
                }
                val now = System.currentTimeMillis()
                api.write("rooms/$roomCode/members/${api.uid}", JSONObject().put("id",api.uid).put("name",name)
                    .put("isHost",code == null).put("joinedAt",now).put("lastSeen",now).put("avatarColorHex","#D4E157")
                    .put("expireAt",Instant.ofEpochMilli(now+86_400_000)),"serverSeen",merge=false)
                commandTracker.reset(); handledRecommendations.clear(); handledRequests.clear()
                lastHeartbeat=now; lastRoster=0; lastGuestSeekVersion=-1; lastReady=null; lastBroadcast=""; lastBroadcastAt=0
                lastHostSample=0; seekLead=200; lastCorrection=0; heldTrack=null; aloneSince=now; idleSince=now
                closingAt=null; closingReason=null; roster=emptyList(); stall=PlaybackStallDetector(); lastPoor=false
                mutable.value=State(code=roomCode,host=code==null,uid=api.uid,hostName=remote?.optString("hostName")?:name,
                    settings=remote?.let { settingsFrom(it.optJSONObject("settings")) }?:hostSettings)
                bindControls()
                if(remote!=null) follow(remote)
                mutable.update { it.copy(track=playback.queue.current?.title.orEmpty(),
                    currentTrack=playback.queue.current,isPlaying=playback.playing) }
                if(pollMs>0) startPolling()
            } catch(e:Exception) {
                unbindControls()
                if (code != null && mutable.value.code.isNotEmpty()) runCatching { api.delete("rooms/$code/members/${api.uid}") }
                created?.let { runCatching { deleteRoom(it) } }
                mutable.value=State(settings=hostSettings,message=readable(e))
                if(e is CancellationException) throw e
            }
        }
    }
    private fun bindControls() {
        val p=playback?:return
        p.controls={action,track,position ->
            val s=mutable.value
            val command=GuestCommand(action,position,track=track)
            when {
                s.code.isEmpty()->false
                s.host->{val locked=action in listOf("next","previous") && s.settings.guestsCanChangeSong &&
                    ListenTogetherSyncMath.trackChangeLockRemainingMs(lastTrackChange,System.currentTimeMillis())>0
                    if(locked) mutable.update { it.copy(message="Wait a moment before skipping again") }; locked}
                action=="recommend" && track!=null->{recommend(track);true}
                action=="edit_queue"->{mutable.update { it.copy(message="Use the individual song's queue menu") };true}
                s.settings.allows(command)->{sendCommand(command);true}
                else->{mutable.update { it.copy(message="The host has turned off this action") };true}
            }
        }
        p.queueEdit={from,to -> if(mutable.value.host) false else {
            if(mutable.value.settings.guestsCanAddSongs) sendCommand(GuestCommand(
                if(to==null) GuestCommand.REMOVE_QUEUE_ITEM else GuestCommand.MOVE_QUEUE_ITEM,
                fromIndex=from,toIndex=to?:-1,queueIds=p.queue.tracks.map { it.id }))
            else mutable.update { it.copy(message="The host has turned off queue editing") };true
        } }
        p.willLoad={track -> if(mutable.value.host) {
            lastTrackChange=System.currentTimeMillis();seekVersion++
            if(mutable.value.memberDetails.any { !it.isHost }) {
                heldTrack=track.id;heldAt=System.currentTimeMillis();p.hold()
                mutable.update { it.copy(waitingForGuests=true) }
            }
        } }
    }
    private fun unbindControls() {
        playback?.controls=null;playback?.queueEdit=null;playback?.willLoad=null
        playback?.releaseHold();heldTrack=null
    }
    private fun startPolling() {
        poll?.cancel()
        poll=scope.launch(Dispatchers.IO) {
            while(isActive && mutable.value.code.isNotBlank()) {
                try { pollOnce() } catch(e:CancellationException) { throw e }
                catch(e:Exception) { mutable.update { it.copy(message="Connection interrupted: ${readable(e)}. Retrying…") } }
                delay(pollMs)
            }
        }
    }
    internal suspend fun pollOnce()=gate.withLock {
        val s=mutable.value
        if(s.code.isEmpty()) return@withLock
        val now=System.currentTimeMillis();val path="rooms/${s.code}"
        val remote=if(!s.host) api.get(path) else null
        if(!s.host && (remote==null || remote.optString("status")!="active")) {
            runCatching { api.delete("$path/members/${api.uid}") }
            unbindControls();playback?.local { playback.pause(true) }
            mutable.value=State(settings=hostSettings,message="The host ended the room");return@withLock
        }
        if(now-lastHeartbeat>=ListenTogetherSyncMath.HEARTBEAT_INTERVAL_MS) {
            api.write("$path/members/${api.uid}",JSONObject().put("lastSeen",now)
                .put("expireAt",Instant.ofEpochMilli(now+86_400_000)),"serverSeen");lastHeartbeat=now
        }
        if(now-lastRoster>=if(s.host) 1000L else 2500L) {
            roster=api.list("$path/members")
            val live=roster.map { memberFrom(it.second) }.filterNot {
                ListenTogetherSyncMath.isPresenceStale(it.lastSeenServerMs,now+api.offsetMs) }
            val recommendations=api.list("$path/recommendations").mapNotNull { (p,raw) -> raw.optJSONObject("track")?.let {
                Recommendation(p.substringAfterLast('/'),parseTrack(it),raw.optString("recommendedByName"),raw.optString("status","pending"),
                    raw.optString("recommendedByUid"),raw.optString("note"),strings(raw.optJSONArray("upvotes")),raw.optLong("createdAt"))
            } }.sortedWith(compareByDescending<Recommendation> { it.upvotes.size }.thenBy { it.createdAt })
            mutable.update { it.copy(memberDetails=live,members=live.map { m->m.name },recommendations=recommendations) };lastRoster=now
        }
        if(s.host) {
            processCommands();processRecommendations();releaseStartIfReady(now)
            if(lifecycle(now)) return@withLock
            val fields=hostFields(s.code,false)
            val stable=JSONObject(fields.toString()).apply { remove("updatedAt");remove("playbackPosition");remove("expireAt") }.toString()
            if(stable!=lastBroadcast || now-lastBroadcastAt>=5000) {
                api.write(path,fields,"serverUpdatedAt");lastBroadcast=stable;lastBroadcastAt=now
            }
        } else if(remote!=null) {
            val host=roster.firstOrNull { it.second.optString("id")==remote.optString("hostId") }?.second
            if(host!=null && ListenTogetherSyncMath.isPresenceStale(timestamp(host,"serverSeen","lastSeen"),now+api.offsetMs)) {
                api.delete("$path/members/${api.uid}");unbindControls();playback?.local { playback.pause(true) }
                mutable.value=State(settings=hostSettings,message="The host disconnected");return@withLock
            }
            follow(remote)
        }
        updateReadiness()
        mutable.update { it.copy(track=playback?.queue?.current?.title.orEmpty(),
            currentTrack=playback?.queue?.current,isPlaying=playback?.playing==true,
            message=it.message?.takeUnless { m->m.startsWith("Connection interrupted:") }) }
    }
    private fun hostFields(code:String,initial:Boolean):JSONObject {
        val p=playback;val now=System.currentTimeMillis();val position=p?.position?:0L
        if(lastHostSample>0 && abs(position-lastHostPosition-if(p?.playing==true)
                ((now-lastHostSample)*p.rate).toLong() else 0)>1500) seekVersion++
        lastHostSample=now;lastHostPosition=position
        val q=p?.queue?:DesktopPlaybackController.Queue()
        val (window,index)=ListenTogetherSyncMath.queueWindow(q.tracks,q.index)
        return JSONObject().put("currentTrack",q.current?.let { trackJson(it)
                .put("videoId",RecordingMatches.getMatchedVideoId(it.id)?:it.id) }?:JSONObject.NULL)
            .put("queue",JSONArray().apply { window.forEach { put(trackJson(it)) } }).put("queueIndex",if(q.current==null) -1 else index)
            .put("isPlaying",p?.playing==true && heldTrack==null).put("playbackPosition",position).put("playbackRate",p?.rate?:1f)
            .put("updatedAt",now).put("seekVersion",seekVersion).put("expireAt",Instant.ofEpochMilli(now+86_400_000))
            .put("membersList",JSONArray().apply { mutable.value.memberDetails.forEach { put(JSONObject().put("id",it.id).put("name",it.name)
                .put("isHost",it.isHost).put("joinedAt",it.joinedAt).put("avatarColorHex",it.avatarColorHex)) } })
            .put("memberCount",mutable.value.memberDetails.size.coerceAtLeast(1))
            .put("activity",JSONArray().apply { mutable.value.activity.takeLast(20).forEach { put(JSONObject(it.toMap())) } })
            .put("closing",closingAt?.let { JSONObject().put("at",it).put("reason",closingReason) }?:JSONObject.NULL)
            .apply { if(initial) { put("id",code);put("code",code);put("hostId",api.uid);put("hostName",name)
                put("status","active");put("settings",JSONObject(hostSettings.toMap())) } }
    }
    private fun follow(room:JSONObject) {
        val p=playback?:return
        mutable.update { it.copy(settings=settingsFrom(room.optJSONObject("settings")),hostName=room.optString("hostName"),
            closingAtMs=room.optJSONObject("closing")?.optLong("at"),closingReason=room.optJSONObject("closing")?.optString("reason"),
            activity=(room.optJSONArray("activity")?:JSONArray()).let { a->(0 until a.length()).mapNotNull { i->
                val o=a.optJSONObject(i)?:return@mapNotNull null;RoomActivity(o.optString("id"),o.optString("message"),o.optString("actorId")) } }) }
        p.local {
            val raw=room.optJSONObject("currentTrack")?:run { p.pause(true);return@local }
            if(raw.optBoolean("closed")) { p.pause(true);return@local }
            val track=parseTrack(raw);if(track.id.isBlank()) return@local
            raw.optString("videoId").takeIf { com.auralis.music.desktop.playback.DesktopPlaybackMatch.isVideoId(it) }?.let {
                RecordingMatches.matchedVideoIds[track.id]=it }
            val a=room.optJSONArray("queue")?:JSONArray()
            val tracks=(0 until a.length()).map { parseTrack(a.getJSONObject(it)) }.toMutableList()
            val supplied=room.optInt("queueIndex",-1);val index=if(supplied in tracks.indices) supplied else 0
            if(supplied in tracks.indices) tracks[supplied]=track else tracks.add(0,track)
            if(p.queue.current?.id!=track.id) {
                p.hold();p.play(track,tracks);p.sync(tracks,index)
                stall.onTrackStart(System.nanoTime()/1_000_000);lastCorrection=0;lastReady=null;return@local
            }
            p.sync(tracks,index);if(!p.ready) return@local
            val playing=room.optBoolean("isPlaying");val speed=room.optDouble("playbackRate",1.0).coerceIn(.25,4.0).toFloat()
            val hostTime=ListenTogetherSyncMath.hostBroadcastLocalTime(timestamp(room,"serverUpdatedAt","updatedAt"),room.optLong("updatedAt"),api.offsetMs)
            val target=estimatedPosition(room.optLong("playbackPosition"),hostTime,playing,System.currentTimeMillis(),speed.toDouble())
                .coerceIn(0L,(p.length-1).coerceAtLeast(0))
            val seek=room.optLong("seekVersion");p.speed(speed)
            val now=System.currentTimeMillis();val drift=target-p.position
            if(seekPending && now-lastCorrection>=ListenTogetherSyncMath.SEEK_SETTLE_MS) {
                seekLead=ListenTogetherSyncMath.nextSeekLead(seekLead,drift);seekPending=false }
            val explicit=seek!=lastGuestSeekVersion
            if(explicit || now-lastCorrection>=ListenTogetherSyncMath.SEEK_SETTLE_MS && abs(drift)>ListenTogetherSyncMath.GUEST_MAX_DRIFT_MS) {
                p.seek((target+if(playing && !explicit) seekLead else 0L).coerceAtMost((p.length-1).coerceAtLeast(0)))
                lastCorrection=now;seekPending=playing;stall.onSeek(System.nanoTime()/1_000_000)
            }
            lastGuestSeekVersion=seek
            if(playing) p.releaseHold()
            p.pause(!playing)
        }
    }
    private fun processCommands() {
        val p=playback?:return;val s=mutable.value
        for((member,command) in commandTracker.newCommands(s.memberDetails,s.settings::allows)) {
            if(command.editsQueue) {
                val offset=command.queueEditOffset(p.queue.tracks,p.queue.index)
                if(offset==null) { announce("${member.name}'s queue changed. Try again.",member.id);continue }
                p.local { if(command.type==GuestCommand.REMOVE_QUEUE_ITEM) p.remove(command.fromIndex+offset)
                    else p.move(command.fromIndex+offset,command.toIndex+offset) }
                announce("${member.name} updated the queue",member.id);continue
            }
            val track=command.track?:when(command.type) {
                GuestCommand.NEXT->p.queue.tracks.getOrNull(p.queue.index+1)
                GuestCommand.PREVIOUS->p.queue.tracks.getOrNull((p.queue.index-1).coerceAtLeast(0))
                else->null
            }
            if(command.changesSong && s.settings.approvalApplies && track!=null) {
                val id="play_${member.id}_${command.seq}"
                if(handledRequests.add(id)) mutable.update { it.copy(requests=it.requests+SongRequest(id,member.name,track,command),
                    message="${member.name} requested ${track.title}. Open Listen Together to allow or decline.") }
                continue
            }
            if(command.changesSong && ListenTogetherSyncMath.trackChangeLockRemainingMs(lastTrackChange,System.currentTimeMillis())>0) continue
            p.local { applyCommand(command) }
            val action=when(command.type) {
                GuestCommand.PLAY->"pressed play";GuestCommand.PAUSE->"paused";GuestCommand.SEEK->"moved the song position"
                GuestCommand.NEXT->"skipped to the next song";GuestCommand.PREVIOUS->"went back a song"
                GuestCommand.PLAY_TRACK->"played ${track?.title.orEmpty()}";else->"changed playback"
            }
            announce("${member.name} $action",member.id)
        }
    }
    private fun applyCommand(c:GuestCommand) {
        val p=playback?:return
        when(c.type) {
            GuestCommand.PLAY->if(heldTrack==null) p.pause(false)
            GuestCommand.PAUSE->p.pause(true)
            GuestCommand.TOGGLE->if(heldTrack==null) p.pause(p.playing)
            GuestCommand.SEEK->p.seek(c.positionMs.coerceIn(0L,p.length.coerceAtLeast(0)))
            GuestCommand.NEXT->p.next()
            GuestCommand.PREVIOUS->p.previous()
            GuestCommand.PLAY_TRACK->c.track?.let { t->
                val index=p.queue.tracks.indexOfFirst { it.id==t.id };val tracks=p.queue.tracks
                p.play(t,if(index>=0) tracks else listOf(t));if(index>=0) p.sync(tracks,index)
            }
        }
    }
    private fun processRecommendations() {
        for(rec in mutable.value.recommendations) {
            if(rec.status!="pending" || rec.id in handledRecommendations) continue
            when(mutable.value.settings.decideGuestSong()) {
                GuestSongDecision.WAIT_FOR_HOST->if(handledRequests.add("add_${rec.id}")) mutable.update {
                    it.copy(requests=it.requests+SongRequest("add_${rec.id}",rec.name,rec.track,recommendation=rec),
                        message="${rec.name} requested ${rec.track.title}. Open Listen Together to allow or decline.") }
                GuestSongDecision.ADD->finishRecommendation(rec,"accepted")
                GuestSongDecision.DECLINE->finishRecommendation(rec,"declined")
            }
        }
    }
    private fun finishRecommendation(rec:Recommendation,status:String) {
        api.write("rooms/${mutable.value.code}/recommendations/${rec.id}",JSONObject().put("status",status))
        handledRecommendations+=rec.id
        if(status=="accepted") playback?.local { playback.add(rec.track) }
        if(status=="played") playback?.local { playback.play(rec.track,listOf(rec.track)) }
        mutable.update { it.copy(recommendations=it.recommendations.map { r->if(r.id==rec.id) r.copy(status=status) else r },
            requests=it.requests.filterNot { r->r.recommendation?.id==rec.id }) }
        if(status!="declined") announce("${rec.name} ${if(status=="played") "played" else "added"} ${rec.track.title}",rec.uid)
    }
    fun updateHostSettings(settings:RoomSettings) {
        if(mutable.value.code.isNotEmpty() && !mutable.value.host) return
        hostSettings=settings
        if(mutable.value.code.isEmpty()) { mutable.update { it.copy(settings=settings) };return }
        operation {
            api.write("rooms/${mutable.value.code}",JSONObject().put("settings",JSONObject(settings.toMap())))
            mutable.update { it.copy(settings=settings,requests=emptyList()) };handledRequests.clear()
        }
    }
    fun allowGuestControls(allow:Boolean)=updateHostSettings(mutable.value.settings.copy(guestsCanControlPlayback=allow))
    fun togglePlayback() {
        val p=playback?:return
        if(mutable.value.host && heldTrack==null) p.local { p.pause(p.playing) }
        else if(mutable.value.guestControls) sendCommand(GuestCommand(if(p.playing) GuestCommand.PAUSE else GuestCommand.PLAY))
    }
    private fun sendCommand(c:GuestCommand)=operation {
        if(mutable.value.code.isEmpty()) return@operation
        val fields=JSONObject().put("type",c.type).put("positionMs",c.positionMs).put("seq",++sequence)
            .put("track",c.track?.let(::trackJson)?:JSONObject.NULL).put("fromIndex",c.fromIndex).put("toIndex",c.toIndex)
            .put("queueIds",JSONArray(c.queueIds))
        api.write("rooms/${mutable.value.code}/members/${api.uid}",JSONObject().put("command",fields))
        if(c.changesSong && mutable.value.settings.requireApproval) mutable.update { it.copy(message="Sent to the host for approval") }
    }
    fun recommend(track:Track,note:String="") {
        if(mutable.value.host) { playback?.local { playback.add(track) };return }
        if(!mutable.value.settings.guestsMayAddSongs) { mutable.update { it.copy(message="The host has turned off adding songs") };return }
        operation {
            if(mutable.value.code.isEmpty()) return@operation
            val id=java.util.UUID.randomUUID().toString()
            api.write("rooms/${mutable.value.code}/recommendations/$id",JSONObject().put("id",id).put("track",trackJson(track))
                .put("recommendedByUid",api.uid).put("recommendedByName",name).put("note",note.trim().take(500))
                .put("upvotes",JSONArray().put(api.uid)).put("createdAt",System.currentTimeMillis()).put("status","pending"),merge=false)
            lastRoster=0
            mutable.update { it.copy(message=if(it.settings.requireApproval) "Sent to the host for approval" else "Added to the room's queue") }
        }
    }
    fun decide(rec:Recommendation,accept:Boolean) {
        if(mutable.value.host) operation { finishRecommendation(rec,if(accept) "accepted" else "declined") }
    }
    fun playNow(rec:Recommendation) { if(mutable.value.host) operation { finishRecommendation(rec,"played") } }
    fun decideRequest(request:SongRequest,accept:Boolean) {
        if(!mutable.value.host) return
        operation {
            if(request.recommendation!=null) finishRecommendation(request.recommendation,if(accept) "accepted" else "declined")
            else if(accept && request.command!=null) {
                playback?.local { applyCommand(request.command) };announce("Playing ${request.name}'s request")
            }
            mutable.update { it.copy(requests=it.requests.filterNot { r->r.id==request.id }) }
        }
    }
    fun upvote(rec:Recommendation)=operation {
        if(mutable.value.code.isEmpty()) return@operation
        val path="rooms/${mutable.value.code}/recommendations/${rec.id}"
        val fresh=api.get(path)?:return@operation
        api.transformArray(path,"upvotes",api.uid,api.uid in strings(fresh.optJSONArray("upvotes")));lastRoster=0
    }
    fun dismissRecommendation(rec:Recommendation) {
        if(!mutable.value.host && rec.uid!=api.uid) return
        operation { api.delete("rooms/${mutable.value.code}/recommendations/${rec.id}");lastRoster=0 }
    }
    fun searchRecommendations(query:String) {
        searchJob?.cancel()
        if(query.isBlank()) { mutable.update { it.copy(searchResults=emptyList(),searching=false) };return }
        mutable.update { it.copy(searching=true) }
        searchJob=scope.launch(Dispatchers.IO) {
            try { delay(300);val results=searchSongs(query.trim()).distinctBy { it.id }.take(10)
                ensureActive();mutable.update { it.copy(searchResults=results,searching=false) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { mutable.update { it.copy(searching=false,message="Couldn't search for songs: ${readable(e)}") } }
        }
    }
    private fun updateReadiness() {
        val p=playback?:return;val track=p.queue.current?.id?:return
        if(stalledTrack!=track) { stalledTrack=track;stall.onTrackStart(System.nanoTime()/1_000_000) }
        stall.onPlayerState(p.playing,!p.ready,System.nanoTime()/1_000_000);stall.tick(System.nanoTime()/1_000_000)
        val network=JSONObject()
        if(!mutable.value.host && p.ready && lastReady!=track) network.put("readyFor",track)
        if(stall.isPoor!=lastPoor) network.put("slow",stall.isPoor)
        if(network.length()>0) api.write("rooms/${mutable.value.code}/members/${api.uid}",JSONObject().put("network",network),
            mask=network.keys().asSequence().map { "network.$it" }.toList())
        if(network.has("readyFor")) lastReady=track
        if(network.has("slow")) lastPoor=stall.isPoor
        mutable.update { it.copy(poorConnection=stall.isPoor) }
    }
    private fun releaseStartIfReady(now:Long) {
        val track=heldTrack?:return
        val ready=mutable.value.memberDetails.filter { !it.isHost }.all { it.readyForTrackId==track }
        if(playback?.ready==true && (ready || now-heldAt>=5000) || now-heldAt>=15000) {
            heldTrack=null;playback?.releaseHold();mutable.update { it.copy(waitingForGuests=false) }
        }
    }
    private fun announce(message:String,actor:String=api.uid) {
        mutable.update { it.copy(activity=(it.activity+RoomActivity(java.util.UUID.randomUUID().toString(),message,actor)).takeLast(20)) }
    }
    private fun lifecycle(now:Long):Boolean {
        aloneSince=if(mutable.value.memberDetails.any { !it.isHost }) null else aloneSince?:now
        idleSince=if(playback?.playing==true || (playback?.queue?.current!=null && playback.ready==false) || heldTrack!=null) null else idleSince?:now
        val reason=ListenTogetherSyncMath.roomClosingReason(aloneSince,idleSince,now)
        if(reason==null) { closingAt=null;closingReason=null }
        else if(closingAt==null || closingReason!=reason) { closingAt=now+ListenTogetherSyncMath.ROOM_CLOSE_WARNING_MS;closingReason=reason }
        mutable.update { it.copy(closingAtMs=closingAt,closingReason=closingReason) }
        if(closingAt!=null && now>=closingAt!!) {
            deleteRoom(mutable.value.code);unbindControls();mutable.value=State(settings=hostSettings,message="The inactive room has ended");return true
        }
        return false
    }
    fun leave() { if(!mutable.value.busy) scope.launch(Dispatchers.IO) { disconnect() } }
    suspend fun disconnect() {
        poll?.cancelAndJoin();poll=null;searchJob?.cancel()
        gate.withLock {
            val s=mutable.value;if(s.code.isEmpty()) return@withLock
            mutable.update { it.copy(busy=true) }
            try {
                if(s.host) deleteRoom(s.code) else api.delete("rooms/${s.code}/members/${api.uid}")
                unbindControls();mutable.value=State(settings=hostSettings)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { mutable.value=s.copy(busy=false,message="Couldn't leave cleanly: ${readable(e)}. Try again.");if(pollMs>0) startPolling() }
        }
    }
    private fun deleteRoom(code:String) {
        val path="rooms/$code"
        api.write(path,JSONObject().put("status","closed"))
        api.list("$path/recommendations").forEach { api.delete(it.first) }
        api.delete(path);api.list("$path/members").forEach { api.delete(it.first) }
    }
    suspend fun close()=withContext(Dispatchers.IO) { disconnect();if(mutable.value.code.isEmpty()) runCatching { api.deleteAnonymousAccount() };Unit }
    companion object {
        fun generateCode():String=(1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        fun estimatedPosition(position:Long,stamp:Long,playing:Boolean,now:Long,speed:Double):Long=
            position+if(playing && stamp>0) ((now-stamp).coerceAtLeast(0)*speed).toLong() else 0
        fun trackJson(t:Track):JSONObject=JSONObject().put("id",t.id).put("title",t.title).put("artist",t.artist)
            .put("album",t.album?:JSONObject.NULL).put("thumbnail",t.thumbnail).put("duration",t.duration)
        fun parseTrack(o:JSONObject,current:Boolean=false):Track=Track(
            id=if(current) o.optString("videoId").takeIf { it.isNotBlank() && it!="null" }?:o.optString("id") else o.optString("id"),
            title=o.optString("title"),artist=o.optString("artist"),album=o.optString("album").takeUnless { it=="null" || it.isBlank() },
            thumbnail=o.optString("thumbnail"),duration=o.optLong("duration"))
        private fun readable(e:Exception):String=e.message?.take(200)?:"Could not reach the room service"
        internal fun settingsFrom(o:JSONObject?):RoomSettings=RoomSettings(o?.optBoolean("guestsCanAddSongs",true)?:true,
            o?.optBoolean("guestsCanControlPlayback",false)?:false,o?.optBoolean("guestsCanPlaySongs",false)?:false,o?.optBoolean("requireApproval",false)?:false)
        private fun strings(a:JSONArray?):List<String> = (0 until (a?.length()?:0)).map { a!!.optString(it) }
        private fun timestamp(o:JSONObject,key:String,fallback:String):Long?=
            (if(o.has(key)) o.optLong(key) else o.optLong(fallback)).takeIf { it>0 }
        private fun memberFrom(o:JSONObject):RoomMember {
            val c=o.optJSONObject("command")
            val command=c?.let { GuestCommand(it.optString("type"),it.optLong("positionMs"),it.optLong("seq"),
                it.optJSONObject("track")?.let { t->parseTrack(t) },it.optInt("fromIndex",-1),it.optInt("toIndex",-1),strings(it.optJSONArray("queueIds"))) }
            return RoomMember(o.optString("id"),o.optString("name","Listener"),o.optBoolean("isHost"),o.optLong("joinedAt"),
                o.optString("avatarColorHex","#7C4DFF"),timestamp(o,"serverSeen","lastSeen"),command,
                o.optJSONObject("network")?.optBoolean("slow")==true,o.optJSONObject("network")?.optString("readyFor"))
        }
    }
}
