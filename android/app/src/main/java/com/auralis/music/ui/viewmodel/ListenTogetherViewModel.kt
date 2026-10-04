package com.auralis.music.ui.viewmodel

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.data.sync.ListenTogetherManager
import com.auralis.music.data.sync.ListenTogetherSyncMath
import com.auralis.music.data.sync.NativeRoomState
import com.auralis.music.data.sync.RoomMember
import com.auralis.music.data.sync.RoomRecommendation
import com.auralis.music.data.sync.GuestCommand
import com.auralis.music.data.sync.RoomSettings
import com.auralis.music.data.sync.decideGuestSong
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.google.firebase.auth.FirebaseAuth
import com.auralis.music.data.service.AuralisAudioPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PillNotification(
    val id: String = java.util.UUID.randomUUID().toString(),
    val message: String,
    val type: PillType = PillType.INFO,
    val timestamp: Long = System.currentTimeMillis()
)

enum class PillType {
    INFO,
    MEMBER_JOINED,
    MEMBER_LEFT,
    HOST_DISCONNECTED
}

/** A guest's song the host has to allow or decline (shown as a popup wherever the host is). */
data class HostSongRequest(
    val id: String,
    val memberName: String,
    val track: Track,
    /** True: play it now for everyone. False: add it to the queue ([recommendation] is set). */
    val playNow: Boolean,
    /** A skip instead of a pick: +1 next song, -1 previous song ([track] is the song it lands on). */
    val skip: Int = 0,
    val recommendation: RoomRecommendation? = null,
    val receivedAtMs: Long = System.currentTimeMillis()
)

data class ListenTogetherUiState(
    val activeRoom: NativeRoomState? = null,
    val members: List<RoomMember> = emptyList(),
    val recommendations: List<RoomRecommendation> = emptyList(),
    val currentUserId: String = "",
    val isHost: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val myDisplayName: String = "",
    val isSearchingRecommendations: Boolean = false,
    val recommendationSearchResults: List<Track> = emptyList(),
    val pillNotification: PillNotification? = null,
    /** The host's choices: what a new room starts with, and the live room's rules while hosting. */
    val hostSettings: RoomSettings = RoomSettings(),
    /** This phone's connection is too slow for smooth playback: offer a lower streaming quality. */
    val showSlowConnectionPrompt: Boolean = false,
    /** Host only: guests' songs waiting for Allow / Decline, oldest first. */
    val songRequests: List<HostSongRequest> = emptyList()
)

private const val OPTIMISTIC_WINDOW_MS = 2_500L
/** How long a guest's skip waits for the host to switch before this phone goes back to the room's song. */
private const val PENDING_SKIP_TIMEOUT_MS = 6_000L

/** The longest the host holds a new song at 0:00 waiting for guests to load it. */
private const val SYNC_START_TIMEOUT_MS = 5_000L

/** On resume, a guest this far behind the host jumps forward at once (bigger gaps: drift check). */
private const val RESUME_CATCH_UP_MIN_MS = 150L
private const val RESUME_CATCH_UP_MAX_MS = 3_000L

/** The host always waits for its own copy, but never longer than this in total. */
private const val SYNC_START_HOST_LIMIT_MS = 15_000L

/** Only mention the wait if it's noticeable. */
private const val SYNC_START_NOTICE_MS = 1_500L

/** A paused guest within this of the host's pause position is left alone. */
private const val PAUSE_SNAP_TOLERANCE_MS = 150L

class ListenTogetherViewModel(
    private val manager: ListenTogetherManager = ListenTogetherManager(),
    private val searchRepository: SearchRepository? = null,
    private val syncManager: GoogleAccountSyncManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListenTogetherUiState())
    val uiState: StateFlow<ListenTogetherUiState> = _uiState.asStateFlow()

    private var roomJob: Job? = null
    private var membersJob: Job? = null
    private var recommendationsJob: Job? = null
    private var searchJob: Job? = null
    private var pillDismissJob: Job? = null

    private var heartbeatJob: Job? = null

    private var previousMembers: List<RoomMember> = emptyList()

    /** Latest roster from the members subcollection, before stale members are filtered out. */
    private var rosterSnapshot: List<RoomMember>? = null

    /** Recent (server clock - local clock) measurements; the most precise one is used. */
    private val clockOffsetSamples = ArrayDeque<com.auralis.music.data.sync.ClockOffsetSample>()

    private var lastSyncedTrackId: String? = null
    private var lastSyncedHostTrackId: String? = null
    /** When the room's song last changed, on this device's clock; drives the skip lock. */
    private var lastTrackChangeAtMs: Long = 0L
    private var guestDriftJob: Job? = null
    private var audioWatchdogJob: Job? = null
    private var lastSyncedIsPlaying: Boolean? = null
    private var lastSeekTimestampMs: Long = 0L
    private var lastAppliedSeekVersion: Long = 0L
    private var hostSeekVersion: Long = 0L

    private val clockOffsetMs: Long?
        get() = ListenTogetherSyncMath.bestClockOffset(clockOffsetSamples.toList())?.offsetMs

    private fun recordClockOffset(sample: com.auralis.music.data.sync.ClockOffsetSample?) {
        sample ?: return
        clockOffsetSamples.addLast(sample)
        while (clockOffsetSamples.size > 5) clockOffsetSamples.removeFirst()
        Log.d("ListenTogether", "[Clock] offset=${sample.offsetMs}ms ±${sample.uncertaintyMs}ms, using=${clockOffsetMs}ms")
    }

    private fun serverNowMs(): Long = System.currentTimeMillis() + (clockOffsetMs ?: 0L)

    /** The roster minus members whose heartbeat has gone stale. You always count yourself. */
    private fun liveMembers(roster: List<RoomMember>): List<RoomMember> {
        val myUid = _uiState.value.currentUserId
        val now = serverNowMs()
        return roster
            .filter { it.id == myUid || clockOffsetMs == null || !ListenTogetherSyncMath.isPresenceStale(it.lastSeenServerMs, now) }
            .sortedWith(compareByDescending<RoomMember> { it.isHost }.thenBy { it.joinedAt })
    }

    /** When this phone joined its current room; its own entry can take a moment to show up. */
    private var joinedRoomAtMs = 0L

    private fun applyRoster(roster: List<RoomMember>) {
        // A guest whose own entry is gone from a live roster is no longer in the room (it left from
        // somewhere else, e.g. the app was swiped away): stop showing the room.
        val state = _uiState.value
        val myUid = state.currentUserId
        if (!state.isHost && state.activeRoom != null && myUid.isNotBlank() && roster.any { it.isHost } &&
            roster.none { it.id == myUid } && System.currentTimeMillis() - joinedRoomAtMs > 10_000L
        ) {
            Log.w("ListenTogether", "[Presence] This phone is no longer in the room -> leaving it here too")
            leaveRoom()
            showPill(str(R.string.you_left_the_room))
            return
        }
        rosterSnapshot = roster
        processGuestCommands(roster)
        releaseHeldSongIfEveryoneReady(liveMembers(roster))
        announceSlowConnections(roster)
        val live = liveMembers(roster)
        handleMembersDelta(live)
        _uiState.update { it.copy(members = live) }
    }

    /** Room messages use the app's compact blurred pill, the same one downloads show. */
    fun showPill(message: String, @Suppress("UNUSED_PARAMETER") type: PillType = PillType.INFO) {
        com.auralis.music.ui.components.AppPillManager.showPill(message, durationMs = 3_500L)
    }

    fun dismissPill() {
        pillDismissJob?.cancel()
        _uiState.update { it.copy(pillNotification = null) }
    }

    private fun handleMembersDelta(newMembers: List<RoomMember>) {
        val myUid = _uiState.value.currentUserId
        if (previousMembers.isNotEmpty() && newMembers.isNotEmpty()) {
            // 1. Detect member who left
            val leftMembers = previousMembers.filter { old ->
                old.id != myUid && newMembers.none { it.id == old.id }
            }
            for (left in leftMembers) {
                if (left.isHost) {
                    showPill(str(R.string.host_x_has_disconnected, left.name), PillType.HOST_DISCONNECTED)
                } else {
                    showPill(str(R.string.x_has_left_the_room, left.name), PillType.MEMBER_LEFT)
                }
            }

            // 2. Detect member who joined
            val joinedMembers = newMembers.filter { newM ->
                newM.id != myUid && previousMembers.none { it.id == newM.id }
            }
            for (joined in joinedMembers) {
                showPill(str(R.string.x_joined_the_room, joined.name), PillType.MEMBER_JOINED)
            }
        }
        previousMembers = newMembers
    }

    var onSyncTrackChange: ((track: Track, queue: List<Track>, queueIndex: Int, initialPositionMs: Long) -> Unit)? = null
    var onSyncResume: (() -> Unit)? = null
    var onSyncPause: (() -> Unit)? = null
    var onSyncSeek: ((positionMs: Long) -> Unit)? = null
    var onSyncQueue: ((queue: List<Track>, currentIndex: Int) -> Unit)? = null
    /** Closes this guest's player (the host closed theirs and this guest can't control playback). */
    var onSyncClosePlayer: (() -> Unit)? = null
    private var lastSeenPlayerClosed = false
    /** The room queue (track ids) this guest last took on, so a host's queue edit is picked up once. */
    private var lastAppliedQueueIds: List<String>? = null
    var onGetLocalPosition: (() -> Long)? = null
    var onGetLocalIsPlaying: (() -> Boolean)? = null
    var onGetLocalIsBuffering: (() -> Boolean)? = null
    var onGetLocalTrackId: (() -> String?)? = null

    var onHostPlayTrack: ((track: Track) -> Unit)? = null
    var onHostAddToQueue: ((track: Track) -> Unit)? = null
    /** Carries out a guest's playback request on the host's player. */
    var onHostGuestCommand: ((GuestCommand) -> Unit)? = null

    private val guestCommands = com.auralis.music.data.sync.GuestCommandTracker()

    /** Watches this phone's playback while in a room; null outside rooms. */
    @Volatile private var stallDetector: com.auralis.music.data.sync.PlaybackStallDetector? = null
    private var connectionMonitorJob: Job? = null
    private var lastSlowPromptAtMs: Long = Long.MIN_VALUE / 2
    /** Each member's last known slow-connection flag, so the room hears about a change once. */
    private val knownSlowConnection = mutableMapOf<String, Boolean>()

    private fun elapsedNow(): Long = android.os.SystemClock.elapsedRealtime()

    /**
     * While in a room, measures whether this phone's playback keeps stopping to load. When it
     * really does, tells the room (everyone sees a short notice) and offers this user a lower
     * streaming quality; when it recovers, clears the flag.
     */
    fun bindConnectionMonitor(player: AuralisAudioPlayer) {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = viewModelScope.launch {
            uiState.map { it.activeRoom?.code }.distinctUntilChanged().collectLatest { roomCode ->
                if (roomCode == null) {
                    stallDetector = null
                    return@collectLatest
                }
                val detector = com.auralis.music.data.sync.PlaybackStallDetector()
                stallDetector = detector
                coroutineScope {
                    launch {
                        player.currentTrack.map { it?.id }.distinctUntilChanged().drop(1).collect {
                            detector.onTrackStart(elapsedNow())
                        }
                    }
                    launch {
                        player.userSeekEvents.collect { detector.onSeek(elapsedNow()) }
                    }
                    launch {
                        combine(player.isPlaying, player.isBuffering) { playing, buffering -> playing to buffering }
                            .distinctUntilChanged()
                            .collect { (playing, buffering) ->
                                if (detector.onPlayerState(playing, buffering, elapsedNow())) onConnectionVerdict(roomCode, detector.isPoor)
                            }
                    }
                    launch {
                        while (true) {
                            delay(1_000L)
                            if (detector.tick(elapsedNow())) onConnectionVerdict(roomCode, detector.isPoor)
                        }
                    }
                }
            }
        }
    }

    private fun onConnectionVerdict(roomCode: String, slow: Boolean) {
        Log.d("ListenTogether", "[Connection] Playback on this phone is ${if (slow) "stalling on a slow connection" else "smooth again"}")
        viewModelScope.launch {
            try {
                manager.setSlowConnection(roomCode, slow)
            } catch (e: Exception) {
                Log.w("ListenTogether", "Couldn't share connection state: ${e.message}")
            }
        }
        if (slow && elapsedNow() - lastSlowPromptAtMs > 10 * 60_000L) {
            lastSlowPromptAtMs = elapsedNow()
            _uiState.update { it.copy(showSlowConnectionPrompt = true) }
        }
    }

    fun dismissSlowConnectionPrompt() {
        _uiState.update { it.copy(showSlowConnectionPrompt = false) }
    }

    private fun announceSlowConnections(roster: List<RoomMember>) {
        val myUid = _uiState.value.currentUserId
        for (member in roster) {
            if (member.id == myUid) continue
            val before = knownSlowConnection.put(member.id, member.hasSlowConnection)
            if (before == false && member.hasSlowConnection) {
                showPill(
                    if (member.isHost) str(R.string.x_s_internet_is_slow_right_now_so_the_mu, member.name)
                    else str(R.string.x_s_internet_is_slow_right_now_so_they_m, member.name)
                )
            }
        }
    }
    /** Guest songs the host has already added or declined, so each is handled once. */
    private val handledRecommendationIds = mutableSetOf<String>()
    private val announcedRequestIds = mutableSetOf<String>()

    /** Until this time, this guest's own tap (play/pause/seek) wins over room updates still on the way. */
    private var optimisticPlayUntilMs = 0L
    /** Until this time, this guest's own seek wins over room positions still on the way. */
    private var optimisticSeekUntilMs = 0L
    /** Either of the above, for the drift checks that touch both. */
    private val optimisticUntilMs: Long get() = maxOf(optimisticPlayUntilMs, optimisticSeekUntilMs)
    private var optimisticResyncJob: Job? = null

    /**
     * When a guest's own tap stops shielding them, catch up with the room at once instead of at
     * the host's next update (up to 5 s later while playing).
     */
    private fun resyncAfterOptimisticWindow() {
        optimisticResyncJob?.cancel()
        optimisticResyncJob = viewModelScope.launch {
            delay(OPTIMISTIC_WINDOW_MS + 50L)
            val state = _uiState.value
            if (!state.isHost) state.activeRoom?.let { syncGuestWithRoomState(it, isInitialJoin = false) }
        }
    }
    /** How far ahead a catch-up jump lands, learned from how long this phone takes to restart after one. */
    private var seekLeadMs = ListenTogetherSyncMath.SEEK_LEAD_MS
    private var awaitingSeekResult = false

    fun updateHostSettings(settings: RoomSettings) {
        _uiState.update { it.copy(hostSettings = settings) }
        val state = _uiState.value
        val roomCode = state.activeRoom?.code ?: return
        if (!state.isHost) return
        viewModelScope.launch {
            try {
                manager.updateRoomSettings(roomCode, settings)
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed updating room settings: ${e.message}", e)
                showPill(str(R.string.couldn_t_update_room_settings))
            }
        }
        // Turning approval off lets the songs already waiting in; turning songs off declines them.
        handleGuestSongs(state.recommendations)
    }

    /** The room's rules: the host's own choices while hosting, the room document's for a guest. */
    private fun roomRules(): RoomSettings {
        val state = _uiState.value
        return if (state.isHost) state.hostSettings else state.activeRoom?.settings ?: RoomSettings()
    }

    private fun processGuestCommands(roster: List<RoomMember>) {
        if (!_uiState.value.isHost) return
        for ((member, command) in guestCommands.newCommands(roster) { roomRules().allows(it) }) {
            val requestedTrack = command.track
            if (command.type == GuestCommand.PLAY_TRACK && requestedTrack != null && roomRules().approvalApplies) {
                addSongRequest(HostSongRequest(id = "play_${member.id}_${command.seq}", memberName = member.name, track = requestedTrack, playNow = true))
                continue
            }
            if ((command.type == GuestCommand.NEXT || command.type == GuestCommand.PREVIOUS) && roomRules().approvalApplies) {
                val step = if (command.type == GuestCommand.NEXT) 1 else -1
                val queue = hostPlayer?.queueState?.value
                val landsOn = queue?.queue?.getOrNull(queue.currentIndex + step)
                    ?: Track(id = "", title = if (step > 0) "The next song" else "The previous song")
                addSongRequest(HostSongRequest(id = "skip_${member.id}_${command.seq}", memberName = member.name, track = landsOn, playNow = true, skip = step))
                continue
            }
            // The new song starts for everyone by itself once loaded; a "play" now would start it early.
            if (command.type == GuestCommand.PLAY && heldTrackId != null) continue
            if ((command.type == GuestCommand.PLAY || command.type == GuestCommand.TOGGLE) && reopenClosedSessionIfNeeded()) {
                showPill(str(R.string.x_pressed_play, member.name))
                continue
            }
            if (command.changesSong && trackChangeLockRemainingMs() > 0L) {
                Log.d("ListenTogether", "[Host] Ignored ${member.name}'s ${command.type}: the song changed under 3s ago")
                continue
            }
            val what = when (command.type) {
                GuestCommand.TOGGLE -> "played/paused"
                GuestCommand.PLAY -> str(R.string.pressed_play)
                GuestCommand.PAUSE -> "paused"
                GuestCommand.NEXT -> str(R.string.skipped_to_the_next_song)
                GuestCommand.PREVIOUS -> str(R.string.went_back_a_song)
                GuestCommand.SEEK -> str(R.string.moved_the_song_position)
                GuestCommand.PLAY_TRACK -> command.track?.let { "played \u201c${it.title}\u201d" } ?: continue
                else -> continue
            }
            Log.d("ListenTogether", "[Host] ${member.name} requested ${command.type} (seq=${command.seq})")
            onHostGuestCommand?.invoke(command)
            showPill("${member.name} $what")
        }
    }

    private fun handleGuestSongs(recommendations: List<RoomRecommendation>) {
        val state = _uiState.value
        val roomCode = state.activeRoom?.code ?: return
        if (!state.isHost) return
        val rules = state.hostSettings
        for (rec in recommendations) {
            if (rec.status != "pending" || rec.id in handledRecommendationIds) continue
            when (rules.decideGuestSong()) {
                com.auralis.music.data.sync.GuestSongDecision.DECLINE -> {
                    handledRecommendationIds += rec.id
                    viewModelScope.launch { manager.updateRecommendationStatus(roomCode, rec.id, "declined") }
                }
                com.auralis.music.data.sync.GuestSongDecision.ADD -> {
                    handledRecommendationIds += rec.id
                    viewModelScope.launch { manager.updateRecommendationStatus(roomCode, rec.id, "accepted") }
                    onHostAddToQueue?.invoke(rec.track)
                    showPill(str(R.string.x_added_x, rec.recommendedByName, rec.track.title))
                }
                // Waits in the host's "Song requests" list; the host hears about it once.
                com.auralis.music.data.sync.GuestSongDecision.WAIT_FOR_HOST -> {
                    if (announcedRequestIds.add(rec.id)) {
                        addSongRequest(HostSongRequest(id = "add_${rec.id}", memberName = rec.recommendedByName, track = rec.track, playNow = false, recommendation = rec))
                    }
                }
            }
        }
    }

    private fun addSongRequest(request: HostSongRequest) {
        _uiState.update { state ->
            if (state.songRequests.any { it.id == request.id }) state
            else state.copy(songRequests = state.songRequests + request)
        }
    }

    private fun removeSongRequest(id: String) {
        _uiState.update { state -> state.copy(songRequests = state.songRequests.filterNot { it.id == id }) }
    }

    /** Host tapped Allow on a guest's song. */
    fun allowSongRequest(request: HostSongRequest) {
        removeSongRequest(request.id)
        if (request.skip != 0) {
            // The popup named the song the skip lands on: play exactly that one. Pressing "next"
            // again moved one song too far when the song had already changed in the meantime.
            val target = request.track.takeIf { it.id.isNotBlank() }
            val player = hostPlayer
            when {
                target != null && player?.currentTrack?.value?.id == target.id -> {
                    player.seekTo(0L)
                    showPill(str(R.string.restarted_x_for_x, target.title, request.memberName))
                }
                target != null -> {
                    onHostPlayTrack?.invoke(target)
                    showPill(str(R.string.playing_x_for_x, target.title, request.memberName))
                }
                else -> {
                    onHostGuestCommand?.invoke(GuestCommand(if (request.skip > 0) GuestCommand.NEXT else GuestCommand.PREVIOUS))
                    showPill(if (request.skip > 0) str(R.string.skipped_for_x, request.memberName) else str(R.string.went_back_a_song_for_x, request.memberName))
                }
            }
        } else if (request.playNow) {
            onHostPlayTrack?.invoke(request.track)
            showPill(str(R.string.playing_x_u2019s_pick_u201c_x_u201d, request.memberName, request.track.title))
        } else {
            request.recommendation?.let { addRecommendationToQueue(it) }
        }
    }

    /** Host tapped Decline on a guest's song. */
    fun declineSongRequest(request: HostSongRequest) {
        removeSongRequest(request.id)
        if (!request.playNow) request.recommendation?.let { declineRecommendation(it) }
    }

    fun declineRecommendation(recommendation: RoomRecommendation) {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        if (!_uiState.value.isHost) return
        handledRecommendationIds += recommendation.id
        removeSongRequest("add_${recommendation.id}")
        viewModelScope.launch { manager.updateRecommendationStatus(roomCode, recommendation.id, "declined") }
    }

    private var guestControlJob: Job? = null

    // ── Room auto-close ────────────────────────────────────────────────────────────────────
    // A room used to stay open forever: nothing closed it when everyone left or nothing played.
    // The host's app now closes it after ROOM_EMPTY_CLOSE_MS alone or ROOM_IDLE_CLOSE_MS with
    // nothing playing, after a one-minute warning everyone sees. Activity cancels the warning.

    private var roomJanitorJob: Job? = null
    private var lastClosingSeenAtMs: Long? = null

    private fun startRoomJanitor(roomCode: String) {
        roomJanitorJob?.cancel()
        roomJanitorJob = viewModelScope.launch {
            var aloneSinceMs: Long? = null
            var idleSinceMs: Long? = null
            var closingAtMs: Long? = null
            var closingReason: String? = null
            while (true) {
                delay(5_000L)
                val state = _uiState.value
                if (!state.isHost || state.activeRoom?.code != roomCode) break
                val now = System.currentTimeMillis()
                val hasGuests = state.members.any { !it.isHost }
                aloneSinceMs = if (hasGuests) null else aloneSinceMs ?: now
                val player = hostPlayer
                val musicOn = player?.isPlaying?.value == true || player?.isBuffering?.value == true || heldTrackId != null
                idleSinceMs = if (musicOn) null else idleSinceMs ?: now
                val reason = ListenTogetherSyncMath.roomClosingReason(aloneSinceMs, idleSinceMs, now)
                val deadline = closingAtMs
                when {
                    reason != null && deadline == null -> {
                        closingAtMs = now + ListenTogetherSyncMath.ROOM_CLOSE_WARNING_MS
                        closingReason = reason
                        showPill(
                            if (reason == com.auralis.music.data.sync.ROOM_CLOSING_EMPTY) str(R.string.everyone_left_u2014_closing_the_room_in)
                            else str(R.string.nothing_s_played_for_30_minutes_u2014_cl)
                        )
                        runCatching { manager.setRoomClosing(roomCode, closingAtMs, reason) }
                    }
                    reason == null && deadline != null -> {
                        closingAtMs = null
                        closingReason = null
                        showPill(str(R.string.the_room_is_staying_open))
                        runCatching { manager.setRoomClosing(roomCode, null, null) }
                    }
                    deadline != null && now >= deadline -> {
                        Log.d("ListenTogether", "[Host] Closing room $roomCode ($closingReason)")
                        leaveRoom()
                        showPill(
                            if (closingReason == com.auralis.music.data.sync.ROOM_CLOSING_EMPTY) str(R.string.closed_the_room_because_everyone_left)
                            else str(R.string.closed_the_room_because_nothing_was_play)
                        )
                        break
                    }
                }
            }
        }
    }

    /** Guest: show the host's closing warning once when it starts. */
    private fun announceRoomClosing(state: NativeRoomState) {
        val closingAt = state.closingAtMs
        if (closingAt == lastClosingSeenAtMs) return
        lastClosingSeenAtMs = closingAt
        if (closingAt == null || state.closingReason != com.auralis.music.data.sync.ROOM_CLOSING_IDLE) return
        val canPlay = state.settings.guestsCanControlPlayback || state.settings.guestsCanPlaySongs
        showPill(
            if (canPlay) str(R.string.nothing_s_played_for_30_minutes_u2014_th)
            else str(R.string.nothing_s_played_for_30_minutes_u2014_th_2)
        )
    }

    // ── Synchronized start ─────────────────────────────────────────────────────────────────
    // A new song used to start on the host at once while guests were still loading it; they
    // then jumped ahead to catch up and missed its first seconds. Now the host holds the song
    // at 0:00 until every guest has it loaded (at most SYNC_START_TIMEOUT_MS) and everyone
    // starts from the very beginning together.

    private var hostPlayer: AuralisAudioPlayer? = null
    private var heldTrackId: String? = null
    private var holdJob: Job? = null
    private var lastReportedReadyFor: String? = null

    private fun startSynchronizedStart(player: AuralisAudioPlayer, trackId: String) {
        holdJob?.cancel()
        heldTrackId = null
        val guests = _uiState.value.members.filter { !it.isHost }
        if (!_uiState.value.isHost || guests.isEmpty()) return
        if (guests.all { it.readyForTrackId == trackId }) return
        heldTrackId = trackId
        stopWaitingForGuests = false
        player.holdStartForRoom()
        Log.d("ListenTogether", "[Host] Holding the new song at 0:00 until this phone and ${guests.size} guest(s) have it loaded")
        holdJob = viewModelScope.launch {
            delay(SYNC_START_NOTICE_MS)
            if (heldTrackId == trackId) showPill(str(R.string.waiting_for_everyone_to_load_the_songu20))
            delay(SYNC_START_TIMEOUT_MS - SYNC_START_NOTICE_MS)
            // Stop waiting for slow guests, but still wait for this phone's own copy.
            stopWaitingForGuests = true
            tryReleaseHeldSong(reason = "guest timeout")
            delay(SYNC_START_HOST_LIMIT_MS - SYNC_START_TIMEOUT_MS)
            releaseSynchronizedStart(trackId, reason = "host still loading after ${SYNC_START_HOST_LIMIT_MS / 1000}s")
        }
    }

    /** After the guest timeout, only the host's own readiness is still awaited. */
    private var stopWaitingForGuests = false

    /**
     * Starts the held song once everyone who matters has it loaded: every guest still in the room
     * (until the guest timeout) and, always, the host itself. Starting while the host was still
     * loading its own copy told the room "playing" early: guests ran ahead, then jumped back.
     */
    private fun tryReleaseHeldSong(reason: String) {
        val trackId = heldTrackId ?: return
        if (!_uiState.value.isHost) return
        if (hostPlayer?.isReadyToStart() != true) return
        val guestsReady = stopWaitingForGuests ||
            liveMembers(rosterSnapshot ?: _uiState.value.members).filter { !it.isHost }.all { it.readyForTrackId == trackId }
        if (guestsReady) releaseSynchronizedStart(trackId, reason)
    }

    private fun releaseSynchronizedStart(trackId: String, reason: String) {
        if (heldTrackId != trackId) return
        heldTrackId = null
        holdJob?.cancel()
        Log.d("ListenTogether", "[Host] Starting the song for everyone ($reason)")
        hostPlayer?.releaseHeldStart()
    }

    /** Host: a guest reported in — maybe everyone is ready now. */
    private fun releaseHeldSongIfEveryoneReady(@Suppress("UNUSED_PARAMETER") roster: List<RoomMember>) {
        tryReleaseHeldSong(reason = "everyone ready")
    }

    /**
     * Guest: once this phone has the room's song loaded (not still fetching), tell the host, so a
     * held song can start for everyone.
     */
    private var guestReadinessJob: Job? = null

    /**
     * Guest sync drives the player directly (not through a screen's view model), so a guest keeps
     * following the room in the background after the app is swiped away with music still playing.
     */
    fun bindGuestSync(player: AuralisAudioPlayer) {
        onSyncTrackChange = { track, queue, queueIndex, startPosMs ->
            player.syncPlayTrack(track, queue, queueIndex, initialPositionMs = startPosMs)
        }
        onSyncResume = { player.syncResume() }
        onSyncPause = { player.syncPause() }
        onSyncSeek = { pos -> player.syncSeek(pos) }
        onSyncQueue = { queue, index -> player.syncQueue(queue, index) }
        onSyncClosePlayer = {
            player.stop()
            player.clearQueue()
            player.clearCurrentTrack()
        }
        onGetLocalPosition = { player.playbackPositionMs.value }
        onGetLocalIsPlaying = { player.isPlaying.value }
        onGetLocalIsBuffering = { player.isBuffering.value }
        onGetLocalTrackId = { player.currentTrack.value?.id }
        // Joined before the player was connected (fresh start, nothing played yet): the room's
        // song arrived with nowhere to go. Start it now, as on joining.
        val state = _uiState.value
        val room = state.activeRoom
        if (room != null && !state.isHost && player.currentTrack.value?.id.let { it != room.currentTrack?.id && it != room.currentVideoId }) {
            Log.d("ListenTogether", "[Guest Sync] Player connected -> starting the room's song")
            lastSyncedHostTrackId = null
            syncGuestWithRoomState(room, isInitialJoin = true)
        }
    }

    fun bindGuestReadiness(player: AuralisAudioPlayer) {
        guestReadinessJob?.cancel()
        guestReadinessJob = viewModelScope.launch {
            combine(player.currentTrack, player.isBuffering, uiState) { track, buffering, state -> Triple(track?.id, buffering, state) }
                .collect { (localId, buffering, state) ->
                    val room = state.activeRoom ?: return@collect
                    if (state.isHost || buffering || localId == null) return@collect
                    val host = room.currentTrack ?: return@collect
                    // A song this guest skipped to is loading here before the host has switched:
                    // report it ready right away, so the host doesn't hold the start for it.
                    val readyId = when (localId) {
                        host.id, room.currentVideoId -> host.id
                        pendingSkipTrackId -> localId
                        else -> return@collect
                    }
                    if (lastReportedReadyFor == readyId) return@collect
                    lastReportedReadyFor = readyId
                    try {
                        manager.setReadyFor(room.code, readyId)
                    } catch (e: Exception) {
                        lastReportedReadyFor = null
                        Log.w("ListenTogether", "Couldn't report ready: ${e.message}")
                    }
                }
        }
    }

    /**
     * A guest's play/pause/seek happens on their own phone right away instead of after the
     * host has carried it out (two network trips). The host's result then confirms it; if the
     * host didn't allow it, the next room update puts this phone back in line.
     */
    private fun applyGuestTapLocally(command: GuestCommand) {
        val now = System.currentTimeMillis()
        when (command.type) {
            GuestCommand.TOGGLE -> {
                optimisticPlayUntilMs = now + OPTIMISTIC_WINDOW_MS
                resyncAfterOptimisticWindow()
                val wasPlaying = onGetLocalIsPlaying?.invoke() == true
                lastSyncedIsPlaying = !wasPlaying
                if (wasPlaying) onSyncPause?.invoke() else onSyncResume?.invoke()
            }
            GuestCommand.PLAY, GuestCommand.PAUSE -> {
                optimisticPlayUntilMs = now + OPTIMISTIC_WINDOW_MS
                resyncAfterOptimisticWindow()
                val play = command.type == GuestCommand.PLAY
                lastSyncedIsPlaying = play
                if (play) onSyncResume?.invoke() else onSyncPause?.invoke()
            }
            GuestCommand.NEXT, GuestCommand.PREVIOUS, GuestCommand.PLAY_TRACK -> preloadGuestSongChange(command)
            GuestCommand.SEEK -> {
                // Only the position is shielded: the host's "playing now" must still get through.
                // A seek right after picking a song used to hold the guest paused for seconds.
                optimisticSeekUntilMs = now + OPTIMISTIC_WINDOW_MS
                resyncAfterOptimisticWindow()
                lastSeekTimestampMs = now
                stallDetector?.onSeek(elapsedNow())
                onSyncSeek?.invoke(command.positionMs)
            }
        }
    }

    /** The song this guest skipped to and is already loading, until the host's room switches to it. */
    @Volatile private var pendingSkipTrackId: String? = null
    private var pendingSkipFromTrackId: String? = null
    private var pendingSkipJob: Job? = null

    /**
     * A guest's skip or song pick used to wait for the host to switch first (two network trips),
     * then start loading here: noticeably slower than on the host. When the room lets guests
     * change the song directly, this phone starts loading the new song at once, held at 0:00,
     * and tells the host it's ready as soon as it is. If the host doesn't switch to it (a skip
     * lock, a rule change, a different next song), the room's own song comes back.
     */
    private fun preloadGuestSongChange(command: GuestCommand) {
        val room = _uiState.value.activeRoom ?: return
        val rules = room.settings
        if (!rules.guestsCanPlaySongs || rules.approvalApplies) return
        if (trackChangeLockRemainingMs() > 0L) return
        val current = room.currentTrack ?: return
        val index = ListenTogetherSyncMath.resolveQueueIndex(room.queue, room.queueIndex, current.id)
        val (target, targetIndex) = when (command.type) {
            GuestCommand.NEXT -> room.queue.getOrNull(index + 1) to index + 1
            // The host restarts the song instead when it's more than 3 s in.
            GuestCommand.PREVIOUS -> if (estimatedHostPosition(room) > 2_500L) null to -1 else room.queue.getOrNull(index - 1) to index - 1
            else -> command.track to room.queue.indexOfFirst { it.id == command.track?.id }
        }
        if (target == null || target.id.isBlank() || target.id == current.id) return
        val queue = if (targetIndex in room.queue.indices) room.queue else listOf(target)
        pendingSkipTrackId = target.id
        pendingSkipFromTrackId = current.id
        lastSyncedHostTrackId = target.id
        lastSyncedIsPlaying = false
        lastSeekTimestampMs = System.currentTimeMillis()
        Log.d("ListenTogether", "[Guest Sync] Loading ${target.title} ahead of the host (${command.type})")
        onSyncTrackChange?.invoke(target, queue, targetIndex.coerceAtLeast(0), 0L)
        onSyncPause?.invoke()
        pendingSkipJob?.cancel()
        pendingSkipJob = viewModelScope.launch {
            delay(PENDING_SKIP_TIMEOUT_MS)
            if (pendingSkipTrackId != target.id) return@launch
            Log.d("ListenTogether", "[Guest Sync] Host didn't switch to ${target.title} -> back to the room's song")
            pendingSkipTrackId = null
            pendingSkipFromTrackId = null
            lastSyncedHostTrackId = null
            _uiState.value.activeRoom?.let { syncGuestWithRoomState(it, isInitialJoin = false) }
        }
    }

    /** A guest picked a song (queue, search, library...): the host plays it for everyone. */
    fun requestPlayTrack(track: Track) {
        val state = _uiState.value
        val room = state.activeRoom ?: return
        if (state.isHost || !room.settings.guestsMayPlaySongs) return
        viewModelScope.launch {
            try {
                manager.sendGuestCommand(room.code, GuestCommand(GuestCommand.PLAY_TRACK, 0L, System.currentTimeMillis(), track))
                showPill(
                    if (room.settings.approvalApplies) str(R.string.asked_the_host_to_play_u201c_x_u201d, track.title)
                    else str(R.string.playing_u201c_x_u201d_for_everyone, track.title)
                )
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed asking host to play ${track.title}: ${e.message}", e)
                showPill(str(R.string.couldn_t_reach_the_host_u2014_try_again))
            }
        }
    }

    /** While a guest in a room that allows it, this user's own play/pause/skip/seek taps go to the host. */
    fun bindGuestControls(player: AuralisAudioPlayer) {
        guestControlJob?.cancel()
        guestControlJob = viewModelScope.launch {
            uiState
                .map { state -> state.activeRoom?.code?.takeIf { !state.isHost } }
                .distinctUntilChanged()
                .collect { roomCode ->
                    player.guestControlForwarder = roomCode?.let { code ->
                        { command: GuestCommand ->
                            // Read the rules at tap time: the host can flip either switch mid-room.
                            val rules = _uiState.value.activeRoom?.settings
                            if (rules?.allows(command) == true) {
                                if (rules.approvalApplies && (command.type == GuestCommand.NEXT || command.type == GuestCommand.PREVIOUS)) {
                                    showPill(str(R.string.asked_the_host_to_x, if (command.type == GuestCommand.NEXT) "skip to the next song" else "go back a song"))
                                }
                                applyGuestTapLocally(command)
                                viewModelScope.launch {
                                    try {
                                        manager.sendGuestCommand(code, command)
                                    } catch (e: Exception) {
                                        Log.e("ListenTogether", "Failed sending ${command.type} to host: ${e.message}", e)
                                    }
                                }
                            } else {
                                showPill(
                                    if (command.changesSong) str(R.string.only_the_host_can_change_what_s_playing)
                                    else str(R.string.only_the_host_controls_playback)
                                )
                            }
                            Unit
                        }
                    }
                    player.guestSongSuggester = roomCode?.let { { track: Track -> recommendSong(track) } }
                }
        }
    }

    init {
        val initialName = resolveAuthenticatedName()
        if (initialName.isNotBlank()) {
            _uiState.update { it.copy(myDisplayName = initialName) }
        }

        viewModelScope.launch {
            try {
                val uid = manager.ensureAuthenticated()
                val refreshedName = resolveAuthenticatedName()
                _uiState.update {
                    it.copy(
                        currentUserId = uid,
                        myDisplayName = if (it.myDisplayName.isBlank() || isGenericListener(it.myDisplayName)) refreshedName else it.myDisplayName
                    )
                }
            } catch (_: Exception) {}
        }

        if (syncManager != null) {
            viewModelScope.launch {
                syncManager.userProfile.collect { profile ->
                    val profileName = profile.displayName.takeIf { it.isNotBlank() && !isGenericListener(it) }
                        ?: profile.email.substringBefore("@").takeIf { it.isNotBlank() && !it.contains("not connected", ignoreCase = true) }
                    if (!profileName.isNullOrBlank()) {
                        _uiState.update { current ->
                            if (current.myDisplayName.isBlank() || isGenericListener(current.myDisplayName)) {
                                current.copy(myDisplayName = profileName)
                            } else current
                        }
                    }
                }
            }
        }
    }

    private fun resolveAuthenticatedName(): String {
        val profileName = syncManager?.userProfile?.value?.displayName?.takeIf { it.isNotBlank() && !isGenericListener(it) }
        if (!profileName.isNullOrBlank()) return profileName

        return try {
            val user = FirebaseAuth.getInstance().currentUser
            val name = user?.displayName?.takeIf { it.isNotBlank() && !isGenericListener(it) }
                ?: user?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            name ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun getEffectiveDisplayName(): String {
        val userGiven = _uiState.value.myDisplayName.trim()
        if (userGiven.isNotBlank() && !isGenericListener(userGiven)) {
            return userGiven
        }
        val resolved = resolveAuthenticatedName()
        if (resolved.isNotBlank()) {
            if (_uiState.value.myDisplayName != resolved) {
                _uiState.update { it.copy(myDisplayName = resolved) }
            }
            return resolved
        }
        return ""
    }

    private fun isGenericListener(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower == "listener" || lower == "guest listener" || lower == "auralis listener"
    }

    fun setDisplayName(name: String) {
        _uiState.update { it.copy(myDisplayName = name) }
    }

    fun createRoom(
        initialTrack: Track?,
        queue: List<Track> = emptyList(),
        queueIndex: Int = 0,
        isPlaying: Boolean = false,
        positionMs: Long = 0L
    ) {
        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
        val effectiveName = getEffectiveDisplayName()
        viewModelScope.launch {
            try {
                val (roomCode, uid) = manager.createRoom(
                    hostDisplayName = effectiveName,
                    initialTrack = initialTrack,
                    queue = queue,
                    queueIndex = queueIndex,
                    isPlaying = isPlaying,
                    playbackPositionMs = positionMs,
                    settings = _uiState.value.hostSettings
                )
                guestCommands.reset()
                knownSlowConnection.clear()
                handledRecommendationIds.clear()
                announcedRequestIds.clear()
                clockOffsetSamples.clear()
                recordClockOffset(manager.lastJoinClockOffset)
                hostSeekVersion = 0L
                lastSyncedTrackId = initialTrack?.id
                lastSyncedIsPlaying = isPlaying
                val hostMember = RoomMember(
                    id = uid,
                    name = effectiveName,
                    isHost = true,
                    joinedAt = System.currentTimeMillis(),
                    avatarColorHex = "#D4E157"
                )
                previousMembers = listOf(hostMember)
                _uiState.update {
                    it.copy(
                        isHost = true,
                        isConnecting = false,
                        currentUserId = uid,
                        members = listOf(hostMember),
                        myDisplayName = if (it.myDisplayName.isBlank()) effectiveName else it.myDisplayName
                    )
                }
                startObservingRoom(roomCode)
                startRoomJanitor(roomCode)
            } catch (e: Exception) {
                Log.e("ListenTogether", "[Create Room Failed]: ${e.message}", e)
                _uiState.update { it.copy(isConnecting = false, errorMessage = e.localizedMessage ?: str(R.string.failed_to_create_room)) }
            }
        }
    }

    fun joinRoom(roomCode: String) {
        if (roomCode.isBlank()) return
        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
        val effectiveName = getEffectiveDisplayName()
        viewModelScope.launch {
            try {
                val initialRoomState = manager.joinRoom(roomCode, effectiveName)
                val uid = manager.ensureAuthenticated()
                clockOffsetSamples.clear()
                recordClockOffset(manager.lastJoinClockOffset)
                lastSyncedTrackId = null
                lastSyncedIsPlaying = null
                knownSlowConnection.clear()
                lastReportedReadyFor = null
                lastAppliedSeekVersion = initialRoomState.seekVersion
                // The first roster snapshot becomes the baseline, so existing members don't
                // all show up as "joined".
                previousMembers = emptyList()
                rosterSnapshot = null
                _uiState.update {
                    it.copy(
                        activeRoom = initialRoomState,
                        members = initialRoomState.membersList,
                        isHost = false,
                        isConnecting = false,
                        currentUserId = uid,
                        myDisplayName = if (it.myDisplayName.isBlank()) effectiveName else it.myDisplayName
                    )
                }
                joinedRoomAtMs = System.currentTimeMillis()
                // Perform immediate initial playback sync on join
                syncGuestWithRoomState(initialRoomState, isInitialJoin = true)
                startObservingRoom(roomCode)
            } catch (e: Exception) {
                Log.e("ListenTogether", "[Join Room Failed]: ${e.message}", e)
                _uiState.update { it.copy(isConnecting = false, errorMessage = e.localizedMessage ?: str(R.string.failed_to_join_room)) }
            }
        }
    }

    fun broadcastHostPlayback(
        currentTrack: Track?,
        isPlaying: Boolean,
        playbackPositionMs: Long,
        queue: List<Track> = emptyList(),
        queueIndex: Int = -1,
        isSeek: Boolean = false
    ) {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        if (!_uiState.value.isHost) return
        val seekVersion = if (isSeek) ++hostSeekVersion else null

        viewModelScope.launch {
            try {
                manager.updateHostPlayback(roomCode, currentTrack, isPlaying, playbackPositionMs, queue, queueIndex, seekVersion)
            } catch (e: Exception) {
                Log.e("ListenTogether", "[Broadcast Host Error]: ${e.message}", e)
            }
        }
    }

    private var hostPlayerJob: Job? = null

    /** What the host's player had when the host closed it, so a guest's "play" can reopen it. */
    private data class ClosedSession(val track: Track, val queue: List<Track>, val index: Int, val positionMs: Long)
    private var hostClosedSession: ClosedSession? = null
    /** Host: reopens a closed song for everyone (the app's player plays it at this spot). */
    var onHostReopenSession: ((track: Track, queue: List<Track>, index: Int, positionMs: Long) -> Unit)? = null

    /**
     * Host: the player is about to close. The room's song pauses; guests who can't control playback
     * get their player closed too, guests who can keep it paused and may start it again (which
     * reopens it here).
     */
    fun onHostClosingPlayer() {
        val state = _uiState.value
        if (!state.isHost || state.activeRoom == null) return
        val player = hostPlayer ?: return
        val track = player.currentTrack.value ?: return
        val queue = player.queueState.value
        val position = player.playbackPositionMs.value
        hostClosedSession = ClosedSession(track, queue.queue, queue.currentIndex, position)
        holdJob?.cancel()
        heldTrackId = null
        val roomCode = state.activeRoom.code
        viewModelScope.launch {
            try {
                manager.updateHostPlayback(roomCode, track, false, position, queue.queue, queue.currentIndex, playerClosed = true)
            } catch (e: Exception) {
                Log.e("ListenTogether", "[Host] Couldn't tell the room the player closed: ${e.message}")
            }
        }
        Log.d("ListenTogether", "[Host] Closed the player on ${track.title} at ${position}ms")
    }

    /** Host: a guest pressed play while the host's player was closed: bring the song back for everyone. */
    private fun reopenClosedSessionIfNeeded(): Boolean {
        val session = hostClosedSession ?: return false
        if (hostPlayer?.currentTrack?.value != null) {
            hostClosedSession = null
            return false
        }
        hostClosedSession = null
        Log.d("ListenTogether", "[Host] A guest pressed play -> reopening ${session.track.title} at ${session.positionMs}ms")
        onHostReopenSession?.invoke(session.track, session.queue, session.index, session.positionMs)
        return true
    }

    /**
     * Broadcasts the host's playback straight from the player's state, so a song change, pause
     * or seek reaches the room immediately however it happened (in app, notification, lock screen,
     * headset buttons, a song ending) and whether or not the app is on screen.
     */
    fun bindHostPlayer(player: AuralisAudioPlayer) {
        hostPlayer = player
        hostPlayerJob?.cancel()
        hostPlayerJob = viewModelScope.launch {
            uiState
                .map { state -> state.activeRoom?.code.takeIf { state.isHost } }
                .distinctUntilChanged()
                .collectLatest { hostedRoomCode ->
                    if (hostedRoomCode == null) return@collectLatest
                    coroutineScope {
                        launch {
                            player.currentTrack.map { it?.id }.distinctUntilChanged().drop(1).collect { trackId ->
                                if (trackId != null) hostClosedSession = null
                                lastTrackChangeAtMs = System.currentTimeMillis()
                                if (trackId != null) startSynchronizedStart(player, trackId)
                            }
                        }
                        lastBroadcastTrackId = player.currentTrack.value?.id
                        launch {
                            combine(player.currentTrack, player.isPlaying, player.isBuffering, player.queueState, player.playIntentChanges) { track, _, _, queue, _ ->
                                Triple(track?.id, player.intendsToPlay(), queue.currentIndex to queue.queue.map { it.id }.hashCode())
                            }
                                .distinctUntilChanged()
                                .collectLatest { (_, playing, _) ->
                                    broadcastFromPlayer(player)
                                    // Keep listeners' drift estimate fresh while playing.
                                    while (playing) {
                                        delay(5000L)
                                        broadcastFromPlayer(player)
                                    }
                                }
                        }
                        launch {
                            // A rebuffer just ended: send the real position so guests re-anchor to it.
                            player.isBuffering.drop(1).collect { buffering ->
                                if (!buffering) tryReleaseHeldSong(reason = "host loaded, everyone ready")
                                if (!buffering && player.intendsToPlay()) broadcastFromPlayer(player)
                            }
                        }
                        launch {
                            player.userSeekEvents.collectLatest { seekMs ->
                                // A drag can emit several seeks in a row; only broadcast where it settles.
                                delay(150L)
                                broadcastFromPlayer(player, seekPositionMs = seekMs)
                            }
                        }
                    }
                }
        }
    }

    /** The song the host last told the room about; a different one starts at 0:00. */
    private var lastBroadcastTrackId: String? = null

    private fun broadcastFromPlayer(player: AuralisAudioPlayer, seekPositionMs: Long? = null) {
        // The player is closed: the room already has "closed, paused". A last update from the
        // moment of closing still said "playing" and landed after it, reopening guests' players.
        if (hostClosedSession != null) return
        val track = player.currentTrack.value ?: return
        val queue = player.queueState.value
        // At the moment a song changes, the player can still report the previous song's position
        // (it reached guests as "start the new song at 2:10"). A new song starts at 0:00.
        val isNewSong = track.id != lastBroadcastTrackId
        lastBroadcastTrackId = track.id
        broadcastHostPlayback(
            currentTrack = track,
            isPlaying = player.intendsToPlay(),
            playbackPositionMs = seekPositionMs ?: if (isNewSong) 0L else player.playbackPositionMs.value,
            queue = queue.queue,
            queueIndex = queue.currentIndex,
            isSeek = seekPositionMs != null
        )
    }

    fun leaveRoom() {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        val isHost = _uiState.value.isHost
        roomJanitorJob?.cancel()
        hostClosedSession = null
        viewModelScope.launch {
            try {
                manager.leaveRoom(roomCode, isHost)
            } catch (_: Exception) {}
        }
        roomJob?.cancel()
        membersJob?.cancel()
        recommendationsJob?.cancel()
        heartbeatJob?.cancel()
        guestDriftJob?.cancel()
        audioWatchdogJob?.cancel()
        searchJob?.cancel()
        lastSyncedTrackId = null
        lastSyncedIsPlaying = null
        previousMembers = emptyList()
        rosterSnapshot = null
        _uiState.update {
            it.copy(
                activeRoom = null,
                members = emptyList(),
                recommendations = emptyList(),
                songRequests = emptyList(),
                recommendationSearchResults = emptyList(),
                isSearchingRecommendations = false,
                isHost = false
            )
        }
    }

    fun searchRecommendations(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(recommendationSearchResults = emptyList(), isSearchingRecommendations = false) }
            return
        }
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearchingRecommendations = true) }
            try {
                val results = searchRepository?.searchSongs(trimmed) ?: emptyList()
                _uiState.update { it.copy(recommendationSearchResults = results, isSearchingRecommendations = false) }
            } catch (e: Exception) {
                Log.e("ListenTogether", "Search songs error: ${e.message}", e)
                _uiState.update { it.copy(recommendationSearchResults = emptyList(), isSearchingRecommendations = false) }
            }
        }
    }

    fun clearRecommendationSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(recommendationSearchResults = emptyList(), isSearchingRecommendations = false) }
    }

    fun recommendSong(track: Track, note: String = "") {
        val state = _uiState.value
        val roomCode = state.activeRoom?.code ?: return
        if (state.isHost) {
            onHostAddToQueue?.invoke(track)
            return
        }
        val rules = roomRules()
        if (!rules.guestsMayAddSongs) {
            showPill(str(R.string.the_host_has_turned_off_adding_songs))
            return
        }
        viewModelScope.launch {
            try {
                manager.recommendSong(
                    roomCode = roomCode,
                    track = track,
                    note = note,
                    recommenderName = getEffectiveDisplayName()
                )
                showPill(
                    if (rules.requireApproval) str(R.string.sent_x_to_the_host_for_approval, track.title)
                    else str(R.string.added_x_to_the_room_s_queue, track.title)
                )
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed recommending song: ${e.message}", e)
                showPill(str(R.string.couldn_t_add_x, track.title))
            }
        }
    }

    fun upvoteRecommendation(recommendationId: String) {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        viewModelScope.launch {
            try {
                manager.upvoteRecommendation(roomCode, recommendationId)
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed upvoting recommendation: ${e.message}", e)
            }
        }
    }

    fun dismissRecommendation(recommendationId: String) {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        viewModelScope.launch {
            try {
                manager.deleteRecommendation(roomCode, recommendationId)
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed dismissing recommendation: ${e.message}", e)
            }
        }
    }

    fun playRecommendationNow(recommendation: RoomRecommendation) {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        if (!_uiState.value.isHost) return
        viewModelScope.launch {
            try {
                manager.updateRecommendationStatus(roomCode, recommendation.id, "played")
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed setting recommendation status played: ${e.message}", e)
            }
            onHostPlayTrack?.invoke(recommendation.track)
        }
    }

    fun addRecommendationToQueue(recommendation: RoomRecommendation) {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        if (!_uiState.value.isHost) return
        handledRecommendationIds += recommendation.id
        removeSongRequest("add_${recommendation.id}")
        viewModelScope.launch {
            try {
                manager.updateRecommendationStatus(roomCode, recommendation.id, "accepted")
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed setting recommendation status accepted: ${e.message}", e)
            }
            onHostAddToQueue?.invoke(recommendation.track)
        }
    }

    private fun startObservingRoom(roomCode: String) {
        roomJob?.cancel()
        membersJob?.cancel()
        recommendationsJob?.cancel()
        heartbeatJob?.cancel()

        roomJob = viewModelScope.launch {
            manager.observeRoomState(roomCode).collect { state ->
                if (state == null || state.status == "closed") {
                    val wasGuest = !_uiState.value.isHost && _uiState.value.activeRoom != null
                    heartbeatJob?.cancel()
                    lastSyncedTrackId = null
                    lastSyncedIsPlaying = null
                    previousMembers = emptyList()
                    rosterSnapshot = null
                    _uiState.update {
                        it.copy(
                            activeRoom = null,
                            members = emptyList(),
                            recommendations = emptyList(),
                            songRequests = emptyList(),
                            isHost = false,
                            errorMessage = if (state?.status == "closed") str(R.string.room_was_closed_by_host) else null
                        )
                    }
                    if (wasGuest) {
                        showPill(str(R.string.host_has_disconnected), PillType.HOST_DISCONNECTED)
                    }
                } else {
                    _uiState.update { current ->
                        // The members subcollection is the roster. The room document's
                        // membersList only ever holds the host, so it is just a placeholder
                        // until the first roster snapshot arrives.
                        current.copy(
                            activeRoom = state,
                            members = if (rosterSnapshot == null && current.members.isEmpty()) state.membersList else current.members
                        )
                    }

                    // If Guest, perform playback synchronization with drift correction
                    if (!_uiState.value.isHost) {
                        syncGuestWithRoomState(state, isInitialJoin = false)
                        announceRoomClosing(state)
                    }
                }
            }
        }

        membersJob = viewModelScope.launch {
            manager.observeRoomMembers(roomCode).collect { roster ->
                applyRoster(roster)
            }
        }

        recommendationsJob = viewModelScope.launch {
            manager.observeRecommendations(roomCode).collect { recs ->
                _uiState.update { it.copy(recommendations = recs) }
                handleGuestSongs(recs)
            }
        }

        guestDriftJob?.cancel()
        guestDriftJob = viewModelScope.launch {
            while (true) {
                delay(ListenTogetherSyncMath.GUEST_DRIFT_CHECK_MS)
                correctGuestDrift()
            }
        }

        heartbeatJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(ListenTogetherSyncMath.HEARTBEAT_INTERVAL_MS)
                recordClockOffset(manager.heartbeat(roomCode))
                // Time passing can make members stale without any new snapshot.
                rosterSnapshot?.let { applyRoster(it) }
                if (!_uiState.value.isHost && isHostGone()) {
                    Log.w("ListenTogether", "[Presence] Host stopped checking in -> leaving room $roomCode")
                    leaveRoom()
                    _uiState.update { it.copy(errorMessage = str(R.string.host_has_disconnected)) }
                    showPill(str(R.string.host_has_disconnected), PillType.HOST_DISCONNECTED)
                    break
                }
            }
        }
    }

    /** True once the roster shows the host gone or silent for longer than the presence timeout. */
    private fun isHostGone(): Boolean {
        val roster = rosterSnapshot ?: return false
        if (roster.isEmpty() || clockOffsetMs == null) return false
        val host = roster.firstOrNull { it.isHost } ?: return true
        return ListenTogetherSyncMath.isPresenceStale(host.lastSeenServerMs, serverNowMs())
    }

    private fun estimatedHostPosition(state: NativeRoomState): Long =
        ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = state.playbackPosition,
            broadcastTimestampMs = ListenTogetherSyncMath.hostBroadcastLocalTime(
                serverUpdatedAtMs = state.serverUpdatedAt,
                hostWallClockUpdatedAtMs = state.updatedAt,
                localClockOffsetMs = clockOffsetMs
            ),
            isPlaying = state.isPlaying,
            playbackRate = state.playbackRate
        )

    private fun isPlayingHostAudio(state: NativeRoomState, hostTrack: Track): Boolean =
        ListenTogetherSyncMath.isPlayingHostAudio(
            localTrackId = onGetLocalTrackId?.invoke(),
            hostTrackId = hostTrack.id,
            hostVideoId = state.currentVideoId,
            localResolvedId = com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(hostTrack.id)
        )

    private fun syncGuestWithRoomState(state: NativeRoomState, isInitialJoin: Boolean) {
        val hostTrack = state.currentTrack ?: return
        if (onSyncTrackChange == null) {
            // Not taken as handled: bindGuestSync replays the room once the player is connected.
            Log.w("ListenTogether", "[Guest Sync] Player isn't connected to the room yet")
            return
        }
        val estimatedHostPos = estimatedHostPosition(state)
        val localIsPlaying = onGetLocalIsPlaying?.invoke()

        pendingSkipTrackId?.let { pending ->
            when (hostTrack.id) {
                // The host hasn't switched yet: keep loading the new song, don't go back to the old one.
                pendingSkipFromTrackId -> if (!isInitialJoin) return
                // The host switched to it: already loading (maybe loaded) here, just follow along.
                pending -> {
                    pendingSkipJob?.cancel()
                    pendingSkipTrackId = null
                    pendingSkipFromTrackId = null
                    lastTrackChangeAtMs = System.currentTimeMillis()
                    lastAppliedSeekVersion = state.seekVersion
                }
                // The host went somewhere else: the normal song change below takes over.
                else -> {
                    pendingSkipJob?.cancel()
                    pendingSkipTrackId = null
                    pendingSkipFromTrackId = null
                }
            }
        }

        // A real song change is judged only by the song's own id, never re-forced later by which
        // exact video ends up playing: re-triggering on that risked switching back and forth if
        // the host's exact video wouldn't load here (see armAudioWatchdog for that fallback).
        // The host closed their player: the song is paused for the room. Guests who can't control
        // playback have theirs closed too; guests who can keep it paused, ready to play again.
        if (state.playerClosed) {
            if (!lastSeenPlayerClosed) {
                lastSeenPlayerClosed = true
                lastSyncedIsPlaying = false
                if (state.settings.guestsCanControlPlayback) {
                    Log.d("ListenTogether", "[Guest Sync] Host closed the player -> pausing here")
                    onSyncPause?.invoke()
                    snapToHostPausePosition(state)
                } else {
                    Log.d("ListenTogether", "[Guest Sync] Host closed the player -> closing it here too")
                    onSyncClosePlayer?.invoke()
                }
            }
            if (hostTrack.id == lastSyncedHostTrackId) return
        } else {
            lastSeenPlayerClosed = false
        }

        // This listener closed the mini player (their song is cleared). It stays closed while the
        // host plays on, but when the host presses play or moves the song, it comes back.
        val closedHere = !isInitialJoin && pendingSkipTrackId == null && onGetLocalTrackId?.invoke() == null &&
            hostTrack.id == lastSyncedHostTrackId && state.isPlaying &&
            (lastSyncedIsPlaying != true || state.seekVersion != lastAppliedSeekVersion)
        if (closedHere) Log.d("ListenTogether", "[Guest Sync] Host played while the player was closed here -> reopening ${hostTrack.title}")

        if (isInitialJoin || closedHere || hostTrack.id != lastSyncedHostTrackId) {
            if (!isInitialJoin && !closedHere) lastTrackChangeAtMs = System.currentTimeMillis()
            lastSyncedHostTrackId = hostTrack.id
            lastSyncedIsPlaying = state.isPlaying
            lastAppliedSeekVersion = state.seekVersion
            lastSeekTimestampMs = System.currentTimeMillis()
            val queueIndex = ListenTogetherSyncMath.resolveQueueIndex(state.queue, state.queueIndex, hostTrack.id)
            // Play the host's exact video when it's already known, so guests hear the same
            // recording and skip a second lookup; otherwise play the song like any other pick,
            // same as before this room existed.
            val playable = state.currentVideoId?.let { hostTrack.copy(id = it) } ?: hostTrack
            val queue = state.queue.toMutableList().also { q ->
                if (queueIndex in q.indices && q[queueIndex].id == hostTrack.id) q[queueIndex] = playable
            }
            lastAppliedQueueIds = state.queue.map { it.id }
            Log.d("ListenTogether", "[Guest Sync] Track change -> ${hostTrack.title} (${playable.id}) at ${estimatedHostPos}ms, queueIndex=$queueIndex, isPlaying=${state.isPlaying}")
            onSyncTrackChange?.invoke(playable, queue, queueIndex, estimatedHostPos)
            if (!state.isPlaying) {
                onSyncPause?.invoke()
            }
            armAudioWatchdog(playable, hostTrack)
            return
        }

        // The host's queue changed (a guest's "play next" / "add to queue", a reorder...) with the
        // same song playing: guests used to see it only after the next song change.
        val roomQueueIds = state.queue.map { it.id }
        if (pendingSkipTrackId == null && state.queue.isNotEmpty() && roomQueueIds != lastAppliedQueueIds) {
            lastAppliedQueueIds = roomQueueIds
            val queueIndex = ListenTogetherSyncMath.resolveQueueIndex(state.queue, state.queueIndex, hostTrack.id)
            Log.d("ListenTogether", "[Guest Sync] Host queue changed -> ${state.queue.size} songs, current at $queueIndex")
            onSyncQueue?.invoke(state.queue, queueIndex)
        }

        // This guest just played/paused or seeked themselves: that part wins until the host's
        // answer lands; the other part still follows the room.
        val nowMs = System.currentTimeMillis()
        val ownPlayTap = nowMs < optimisticPlayUntilMs
        val ownSeek = nowMs < optimisticSeekUntilMs
        if (ownSeek) lastAppliedSeekVersion = state.seekVersion

        // Play / Pause state sync. Resuming is safe while this phone is still loading (the player
        // starts by itself when ready), so a play right after a seek is never swallowed.
        if (ownPlayTap) {
            // Keep this guest's own play/pause.
        } else if (state.isPlaying != localIsPlaying || state.isPlaying != lastSyncedIsPlaying) {
            lastSyncedIsPlaying = state.isPlaying
            if (state.isPlaying) {
                Log.d("ListenTogether", "[Guest Sync] Host playing -> resuming guest playback")
                onSyncResume?.invoke()
                lastSeekTimestampMs = System.currentTimeMillis()
                // The "playing" signal arrives a few hundred ms after the host started. A loaded,
                // paused song can jump that small gap instantly, so the start isn't an echo.
                val local = onGetLocalPosition?.invoke()
                if (!ownSeek && local != null && onGetLocalIsBuffering?.invoke() != true) {
                    val gap = estimatedHostPos - local
                    if (gap in RESUME_CATCH_UP_MIN_MS..RESUME_CATCH_UP_MAX_MS) {
                        stallDetector?.onSeek(elapsedNow())
                        onSyncSeek?.invoke(estimatedHostPos)
                    }
                }
            } else {
                Log.d("ListenTogether", "[Guest Sync] Host paused -> pausing guest playback")
                onSyncPause?.invoke()
                if (!ownSeek) snapToHostPausePosition(state)
            }
        } else if (!state.isPlaying && localIsPlaying == true) {
            Log.d("ListenTogether", "[Guest Sync] Host is paused but guest is active -> force pausing guest")
            onSyncPause?.invoke()
            if (!ownSeek) snapToHostPausePosition(state)
        }

        // The host seeked: follow immediately instead of waiting for the drift check.
        if (state.seekVersion != lastAppliedSeekVersion) {
            lastAppliedSeekVersion = state.seekVersion
            lastSeekTimestampMs = System.currentTimeMillis()
            Log.d("ListenTogether", "[Guest Sync] Host seek v${state.seekVersion} -> seekTo $estimatedHostPos")
            issueGuestSeek(estimatedHostPos, hostIsPlaying = state.isPlaying)
        }
    }

    /**
     * Runs every second for a guest: once its audio is actually playing (loaded, not buffering)
     * and settled, it jumps to the host's position if it's more than [ListenTogetherSyncMath.GUEST_MAX_DRIFT_MS]
     * off. This is also what corrects a song that started late because it took a while to load.
     */
    private fun correctGuestDrift() {
        val state = _uiState.value
        if (state.isHost) return
        val room = state.activeRoom ?: return
        val hostTrack = room.currentTrack ?: return
        if (!room.isPlaying) {
            alignPausedGuest(room, hostTrack)
            return
        }
        if (!isPlayingHostAudio(room, hostTrack)) return
        if (onGetLocalIsPlaying?.invoke() != true || onGetLocalIsBuffering?.invoke() == true) return
        val now = System.currentTimeMillis()
        if (now < optimisticUntilMs) return
        if (now - lastSeekTimestampMs < ListenTogetherSyncMath.SEEK_SETTLE_MS) return
        val expected = estimatedHostPosition(room)
        val local = onGetLocalPosition?.invoke() ?: return
        if (awaitingSeekResult) {
            // First steady reading after a jump: learn how long this phone takes to restart.
            awaitingSeekResult = false
            seekLeadMs = ListenTogetherSyncMath.nextSeekLead(seekLeadMs, lagMs = expected - local)
        }
        if (!ListenTogetherSyncMath.shouldResync(local, expected, ListenTogetherSyncMath.GUEST_MAX_DRIFT_MS)) return
        Log.d("ListenTogether", "[Guest Sync] Drift ${local - expected}ms (local=${local}ms, host=${expected}ms) -> seekTo ${expected + seekLeadMs} (lead ${seekLeadMs}ms)")
        issueGuestSeek(expected, hostIsPlaying = true)
    }

    /**
     * Jumps to the host's position. While the host plays, it lands [seekLeadMs] ahead to cover the
     * moment this phone takes to restart; while paused it lands exactly, since nothing moves on.
     */
    private fun alignPausedGuest(room: NativeRoomState, hostTrack: Track) {
        if (!isPlayingHostAudio(room, hostTrack)) return
        if (onGetLocalIsPlaying?.invoke() == true || onGetLocalIsBuffering?.invoke() == true) return
        val now = System.currentTimeMillis()
        if (now < optimisticUntilMs || now - lastSeekTimestampMs < ListenTogetherSyncMath.SEEK_SETTLE_MS) return
        val local = onGetLocalPosition?.invoke() ?: return
        if (kotlin.math.abs(local - room.playbackPosition) <= PAUSE_SNAP_TOLERANCE_MS) return
        Log.d("ListenTogether", "[Guest Sync] Paused at ${local}ms, host paused at ${room.playbackPosition}ms -> lining up")
        issueGuestSeek(room.playbackPosition, hostIsPlaying = false)
    }

    private fun issueGuestSeek(hostPositionMs: Long, hostIsPlaying: Boolean) {
        lastSeekTimestampMs = System.currentTimeMillis()
        awaitingSeekResult = hostIsPlaying
        stallDetector?.onSeek(elapsedNow())
        onSyncSeek?.invoke(if (hostIsPlaying) hostPositionMs + seekLeadMs else hostPositionMs)
    }

    /**
     * The pause reaches this phone a moment after the host paused, so it has played a little
     * further. Move back to exactly where the host stopped (e.g. 0:20, not 0:21).
     */
    private fun snapToHostPausePosition(state: NativeRoomState) {
        val local = onGetLocalPosition?.invoke() ?: return
        if (kotlin.math.abs(local - state.playbackPosition) <= PAUSE_SNAP_TOLERANCE_MS) return
        Log.d("ListenTogether", "[Guest Sync] Host paused at ${state.playbackPosition}ms, this phone at ${local}ms -> moving to match")
        issueGuestSeek(state.playbackPosition, hostIsPlaying = false)
    }

    /**
     * A guest that never actually starts playing the song it was told to (the host's exact video
     * failed to load — unavailable, region-locked, or a one-off network hiccup) would otherwise sit
     * silently forever, while the room still shows that song as playing. If nothing has started
     * within 8 s, fall back to the guest's own lookup for the same song rather than staying stuck.
     */
    private fun armAudioWatchdog(attempted: Track, hostTrack: Track) {
        audioWatchdogJob?.cancel()
        // Armed even while the room is paused: new songs now start held at 0:00 (synchronized start).
        if (attempted.id == hostTrack.id) return
        audioWatchdogJob = viewModelScope.launch {
            delay(8_000L)
            val state = _uiState.value
            if (state.isHost || state.activeRoom == null) return@launch
            // Superseded by a newer song, or it's already playing — nothing to rescue.
            if (lastSyncedHostTrackId != hostTrack.id) return@launch
            if (onGetLocalTrackId?.invoke() != attempted.id) return@launch
            if (onGetLocalIsPlaying?.invoke() == true) return@launch
            // Still held at the start (or paused by the host): not playing is expected, not a failure.
            if (state.activeRoom.isPlaying != true) return@launch
            Log.w("ListenTogether", "[Guest Sync] '${hostTrack.title}' (${attempted.id}) hasn't started after 8s -> falling back to this phone's own copy")
            showPill(str(R.string.couldn_t_load_that_exact_song_here_playi))
            onSyncTrackChange?.invoke(hostTrack, state.activeRoom.queue, state.activeRoom.queueIndex, estimatedHostPosition(state.activeRoom))
        }
    }

    /** Time left before next/previous is allowed again; 0 when there's no lock in this room. */
    fun trackChangeLockRemainingMs(): Long {
        val state = _uiState.value
        val room = state.activeRoom ?: return 0L
        val rules = if (state.isHost) state.hostSettings else room.settings
        if (!rules.guestsCanChangeSong) return 0L
        return ListenTogetherSyncMath.trackChangeLockRemainingMs(lastTrackChangeAtMs, System.currentTimeMillis())
    }
}
