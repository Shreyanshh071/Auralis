package com.auralis.music.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.data.sync.ListenTogetherManager
import com.auralis.music.data.sync.ListenTogetherSyncMath
import com.auralis.music.data.sync.NativeRoomState
import com.auralis.music.data.sync.RoomMember
import com.auralis.music.data.sync.RoomRecommendation
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
    val pillNotification: PillNotification? = null
)

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

    private fun applyRoster(roster: List<RoomMember>) {
        rosterSnapshot = roster
        val live = liveMembers(roster)
        handleMembersDelta(live)
        _uiState.update { it.copy(members = live) }
    }

    fun showPill(message: String, type: PillType = PillType.INFO) {
        pillDismissJob?.cancel()
        _uiState.update { it.copy(pillNotification = PillNotification(message = message, type = type)) }
        pillDismissJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3500L)
            _uiState.update { it.copy(pillNotification = null) }
        }
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
                    showPill("Host (${left.name}) has disconnected", PillType.HOST_DISCONNECTED)
                } else {
                    showPill("${left.name} has left the room", PillType.MEMBER_LEFT)
                }
            }

            // 2. Detect member who joined
            val joinedMembers = newMembers.filter { newM ->
                newM.id != myUid && previousMembers.none { it.id == newM.id }
            }
            for (joined in joinedMembers) {
                showPill("${joined.name} joined the room", PillType.MEMBER_JOINED)
            }
        }
        previousMembers = newMembers
    }

    var onSyncTrackChange: ((track: Track, queue: List<Track>, queueIndex: Int, initialPositionMs: Long) -> Unit)? = null
    var onSyncResume: (() -> Unit)? = null
    var onSyncPause: (() -> Unit)? = null
    var onSyncSeek: ((positionMs: Long) -> Unit)? = null
    var onGetLocalPosition: (() -> Long)? = null
    var onGetLocalIsPlaying: (() -> Boolean)? = null
    var onGetLocalTrackId: (() -> String?)? = null

    var onHostPlayTrack: ((track: Track) -> Unit)? = null
    var onHostAddToQueue: ((track: Track) -> Unit)? = null

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
                    playbackPositionMs = positionMs
                )
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
            } catch (e: Exception) {
                Log.e("ListenTogether", "[Create Room Failed]: ${e.message}", e)
                _uiState.update { it.copy(isConnecting = false, errorMessage = e.localizedMessage ?: "Failed to create room") }
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
                // Perform immediate initial playback sync on join
                syncGuestWithRoomState(initialRoomState, isInitialJoin = true)
                startObservingRoom(roomCode)
            } catch (e: Exception) {
                Log.e("ListenTogether", "[Join Room Failed]: ${e.message}", e)
                _uiState.update { it.copy(isConnecting = false, errorMessage = e.localizedMessage ?: "Failed to join room") }
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

    /**
     * Broadcasts the host's playback straight from the player's state, so a song change, pause
     * or seek reaches the room immediately however it happened (in app, notification, lock screen,
     * headset buttons, a song ending) and whether or not the app is on screen.
     */
    fun bindHostPlayer(player: AuralisAudioPlayer) {
        hostPlayerJob?.cancel()
        hostPlayerJob = viewModelScope.launch {
            uiState
                .map { state -> state.activeRoom?.code.takeIf { state.isHost } }
                .distinctUntilChanged()
                .collectLatest { hostedRoomCode ->
                    if (hostedRoomCode == null) return@collectLatest
                    coroutineScope {
                        launch {
                            combine(player.currentTrack, player.isPlaying, player.queueState) { track, playing, queue ->
                                Triple(track?.id, playing, queue.currentIndex to queue.queue.size)
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

    private fun broadcastFromPlayer(player: AuralisAudioPlayer, seekPositionMs: Long? = null) {
        val track = player.currentTrack.value ?: return
        val queue = player.queueState.value
        broadcastHostPlayback(
            currentTrack = track,
            isPlaying = player.isPlaying.value,
            playbackPositionMs = seekPositionMs ?: player.playbackPositionMs.value,
            queue = queue.queue,
            queueIndex = queue.currentIndex,
            isSeek = seekPositionMs != null
        )
    }

    fun leaveRoom() {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        val isHost = _uiState.value.isHost
        viewModelScope.launch {
            try {
                manager.leaveRoom(roomCode, isHost)
            } catch (_: Exception) {}
        }
        roomJob?.cancel()
        membersJob?.cancel()
        recommendationsJob?.cancel()
        heartbeatJob?.cancel()
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
        val roomCode = _uiState.value.activeRoom?.code ?: return
        viewModelScope.launch {
            try {
                manager.recommendSong(
                    roomCode = roomCode,
                    track = track,
                    note = note,
                    recommenderName = getEffectiveDisplayName()
                )
            } catch (e: Exception) {
                Log.e("ListenTogether", "Failed recommending song: ${e.message}", e)
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
                            isHost = false,
                            errorMessage = if (state?.status == "closed") "Room was closed by host" else null
                        )
                    }
                    if (wasGuest) {
                        showPill("Host has disconnected", PillType.HOST_DISCONNECTED)
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
                    _uiState.update { it.copy(errorMessage = "Host has disconnected") }
                    showPill("Host has disconnected", PillType.HOST_DISCONNECTED)
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

    private fun syncGuestWithRoomState(state: NativeRoomState, isInitialJoin: Boolean) {
        val hostTrack = state.currentTrack ?: return
        val estimatedHostPos = ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = state.playbackPosition,
            broadcastTimestampMs = ListenTogetherSyncMath.hostBroadcastLocalTime(
                serverUpdatedAtMs = state.serverUpdatedAt,
                hostWallClockUpdatedAtMs = state.updatedAt,
                localClockOffsetMs = clockOffsetMs
            ),
            isPlaying = state.isPlaying,
            playbackRate = state.playbackRate
        )

        val localTrackId = onGetLocalTrackId?.invoke()
        val localIsPlaying = onGetLocalIsPlaying?.invoke()
        val localPos = onGetLocalPosition?.invoke() ?: 0L

        val trackChanged = isInitialJoin || hostTrack.id != lastSyncedTrackId || hostTrack.id != localTrackId

        if (trackChanged) {
            lastSyncedTrackId = hostTrack.id
            lastSyncedIsPlaying = state.isPlaying
            lastAppliedSeekVersion = state.seekVersion
            lastSeekTimestampMs = System.currentTimeMillis()
            val queueIndex = ListenTogetherSyncMath.resolveQueueIndex(state.queue, state.queueIndex, hostTrack.id)
            Log.d("ListenTogether", "[Guest Sync] Track change -> ${hostTrack.title} (${hostTrack.id}) at ${estimatedHostPos}ms, queueIndex=$queueIndex, isPlaying=${state.isPlaying}")
            onSyncTrackChange?.invoke(hostTrack, state.queue, queueIndex, estimatedHostPos)
            if (!state.isPlaying) {
                onSyncPause?.invoke()
            }
            return
        }

        // Play / Pause state sync
        if (state.isPlaying != localIsPlaying || state.isPlaying != lastSyncedIsPlaying) {
            lastSyncedIsPlaying = state.isPlaying
            if (state.isPlaying) {
                Log.d("ListenTogether", "[Guest Sync] Host playing -> resuming guest playback")
                onSyncResume?.invoke()
            } else {
                Log.d("ListenTogether", "[Guest Sync] Host paused -> pausing guest playback")
                onSyncPause?.invoke()
            }
        } else if (!state.isPlaying && localIsPlaying == true) {
            Log.d("ListenTogether", "[Guest Sync] Host is paused but guest is active -> force pausing guest")
            onSyncPause?.invoke()
        }

        // The host seeked: follow immediately instead of waiting for the drift check.
        if (state.seekVersion != lastAppliedSeekVersion) {
            lastAppliedSeekVersion = state.seekVersion
            lastSeekTimestampMs = System.currentTimeMillis()
            Log.d("ListenTogether", "[Guest Sync] Host seek v${state.seekVersion} -> seekTo $estimatedHostPos")
            onSyncSeek?.invoke(estimatedHostPos)
            return
        }

        // Drift check and seek resync (only when playing, debounced to 5s to prevent audio stutter)
        if (state.isPlaying) {
            val now = System.currentTimeMillis()
            if (now - lastSeekTimestampMs > 5000L &&
                ListenTogetherSyncMath.shouldResync(clientPositionMs = localPos, estimatedHostPositionMs = estimatedHostPos)
            ) {
                lastSeekTimestampMs = now
                Log.d("ListenTogether", "[Guest Sync] Drift detected (local=${localPos}ms, host=${estimatedHostPos}ms) -> seekTo $estimatedHostPos")
                onSyncSeek?.invoke(estimatedHostPos)
            }
        }
    }
}
