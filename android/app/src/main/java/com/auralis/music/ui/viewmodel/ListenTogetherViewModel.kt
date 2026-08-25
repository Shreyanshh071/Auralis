package com.auralis.music.ui.viewmodel

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

    var onSyncTrackChange: ((Track, List<Track>) -> Unit)? = null
    var onSyncPlayPause: ((Boolean) -> Unit)? = null
    var onSyncSeek: ((Long) -> Unit)? = null

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
                _uiState.update { it.copy(isHost = true, isConnecting = false) }
                startObservingRoom(roomCode)
            } catch (e: Exception) {
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
                _uiState.update {
                    it.copy(
                        activeRoom = initialRoomState,
                        isHost = false,
                        isConnecting = false
                    )
                }
                startObservingRoom(roomCode)
            } catch (e: Exception) {
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
            } catch (_: Exception) {}
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
        _uiState.update { it.copy(activeRoom = null, members = emptyList(), isHost = false) }
    }

    private fun startObservingRoom(roomCode: String) {
        roomJob?.cancel()
        membersJob?.cancel()

        roomJob = viewModelScope.launch {
            manager.observeRoomState(roomCode).collect { state ->
                if (state == null || state.status == "closed") {
                    _uiState.update { it.copy(activeRoom = null, members = emptyList(), isHost = false, errorMessage = if (state?.status == "closed") "Room was closed by host" else null) }
                } else {
                    val prevRoom = _uiState.value.activeRoom
                    _uiState.update { it.copy(activeRoom = state) }

                    // If Guest, perform playback synchronization with drift correction
                    if (!_uiState.value.isHost) {
                        handleGuestSync(prevRoom, state)
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

    private fun handleGuestSync(prev: NativeRoomState?, curr: NativeRoomState) {
        val currTrack = curr.currentTrack ?: return
        if (prev?.currentTrack?.id != currTrack.id) {
            onSyncTrackChange?.invoke(currTrack, curr.queue)
        }

        if (prev?.isPlaying != curr.isPlaying) {
            onSyncPlayPause?.invoke(curr.isPlaying)
        }

        val estimatedHostPos = ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = curr.playbackPosition,
            broadcastTimestampMs = curr.updatedAt,
            isPlaying = curr.isPlaying,
            playbackRate = curr.playbackRate
        )

        onSyncSeek?.invoke(estimatedHostPos)
    }
}
