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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val recommendationSearchResults: List<Track> = emptyList()
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

    private var lastSyncedTrackId: String? = null
    private var lastSyncedIsPlaying: Boolean? = null
    private var lastSeekTimestampMs: Long = 0L

    var onSyncTrackChange: ((track: Track, queue: List<Track>, initialPositionMs: Long) -> Unit)? = null
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
                    isPlaying = isPlaying,
                    playbackPositionMs = positionMs
                )
                lastSyncedTrackId = initialTrack?.id
                lastSyncedIsPlaying = isPlaying
                _uiState.update { it.copy(isHost = true, isConnecting = false, currentUserId = uid, myDisplayName = if (it.myDisplayName.isBlank()) effectiveName else it.myDisplayName) }
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
                lastSyncedTrackId = null
                lastSyncedIsPlaying = null
                _uiState.update {
                    it.copy(
                        activeRoom = initialRoomState,
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
        queue: List<Track> = emptyList()
    ) {
        val roomCode = _uiState.value.activeRoom?.code ?: return
        if (!_uiState.value.isHost) return

        viewModelScope.launch {
            try {
                manager.updateHostPlayback(roomCode, currentTrack, isPlaying, playbackPositionMs, queue)
            } catch (e: Exception) {
                Log.e("ListenTogether", "[Broadcast Host Error]: ${e.message}", e)
            }
        }
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
        searchJob?.cancel()
        lastSyncedTrackId = null
        lastSyncedIsPlaying = null
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

        roomJob = viewModelScope.launch {
            manager.observeRoomState(roomCode).collect { state ->
                if (state == null || state.status == "closed") {
                    lastSyncedTrackId = null
                    lastSyncedIsPlaying = null
                    _uiState.update {
                        it.copy(
                            activeRoom = null,
                            members = emptyList(),
                            recommendations = emptyList(),
                            isHost = false,
                            errorMessage = if (state?.status == "closed") "Room was closed by host" else null
                        )
                    }
                } else {
                    _uiState.update { it.copy(activeRoom = state) }

                    // If Guest, perform playback synchronization with drift correction
                    if (!_uiState.value.isHost) {
                        syncGuestWithRoomState(state, isInitialJoin = false)
                    }
                }
            }
        }

        membersJob = viewModelScope.launch {
            manager.observeRoomMembers(roomCode).collect { membersList ->
                _uiState.update { it.copy(members = membersList) }
            }
        }

        recommendationsJob = viewModelScope.launch {
            manager.observeRecommendations(roomCode).collect { recs ->
                _uiState.update { it.copy(recommendations = recs) }
            }
        }
    }

    private fun syncGuestWithRoomState(state: NativeRoomState, isInitialJoin: Boolean) {
        val hostTrack = state.currentTrack ?: return
        val estimatedHostPos = ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = state.playbackPosition,
            broadcastTimestampMs = state.updatedAt,
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
            lastSeekTimestampMs = System.currentTimeMillis()
            Log.d("ListenTogether", "[Guest Sync] Track change -> ${hostTrack.title} (${hostTrack.id}) at ${estimatedHostPos}ms, isPlaying=${state.isPlaying}")
            onSyncTrackChange?.invoke(hostTrack, state.queue, estimatedHostPos)
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
