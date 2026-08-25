package com.auralis.music.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.data.sync.ListenTogetherManager
import com.auralis.music.data.sync.ListenTogetherSyncMath
import com.auralis.music.data.sync.NativeRoomState
import com.auralis.music.data.sync.RoomMember
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListenTogetherUiState(
    val activeRoom: NativeRoomState? = null,
    val members: List<RoomMember> = emptyList(),
    val isHost: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val myDisplayName: String = "Listener"
)

class ListenTogetherViewModel(
    private val manager: ListenTogetherManager = ListenTogetherManager()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListenTogetherUiState())
    val uiState: StateFlow<ListenTogetherUiState> = _uiState.asStateFlow()

    private var roomJob: Job? = null
    private var membersJob: Job? = null

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
        viewModelScope.launch {
            try {
                val (roomCode, _) = manager.createRoom(
                    hostDisplayName = _uiState.value.myDisplayName,
                    initialTrack = initialTrack,
                    queue = queue,
                    isPlaying = isPlaying,
                    playbackPositionMs = positionMs
                )
                lastSyncedTrackId = initialTrack?.id
                lastSyncedIsPlaying = isPlaying
                _uiState.update { it.copy(isHost = true, isConnecting = false) }
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
        viewModelScope.launch {
            try {
                val initialRoomState = manager.joinRoom(roomCode, _uiState.value.myDisplayName)
                lastSyncedTrackId = null
                lastSyncedIsPlaying = null
                _uiState.update {
                    it.copy(
                        activeRoom = initialRoomState,
                        isHost = false,
                        isConnecting = false
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
        lastSyncedTrackId = null
        lastSyncedIsPlaying = null
        _uiState.update { it.copy(activeRoom = null, members = emptyList(), isHost = false) }
    }

    private fun startObservingRoom(roomCode: String) {
        roomJob?.cancel()
        membersJob?.cancel()

        roomJob = viewModelScope.launch {
            manager.observeRoomState(roomCode).collect { state ->
                if (state == null || state.status == "closed") {
                    lastSyncedTrackId = null
                    lastSyncedIsPlaying = null
                    _uiState.update {
                        it.copy(
                            activeRoom = null,
                            members = emptyList(),
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
