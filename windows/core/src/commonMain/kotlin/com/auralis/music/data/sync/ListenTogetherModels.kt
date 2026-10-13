package com.auralis.music.data.sync

import com.auralis.music.domain.model.Track

data class RoomMember(
    val id: String = "",
    val name: String = "",
    val isHost: Boolean = false,
    val joinedAt: Long = System.currentTimeMillis(),
    val avatarColorHex: String = "#7C4DFF",
    /** Server time of the member's last heartbeat; null for records written by older app versions. */
    val lastSeenServerMs: Long? = null,
    /** The latest playback request this guest sent the host, if any. */
    val command: GuestCommand? = null,
    /** This member's playback keeps stalling on a slow connection (see PlaybackStallDetector). */
    val hasSlowConnection: Boolean = false,
    /** The room song this member has loaded and is ready to start. */
    val readyForTrackId: String? = null
)

/**
 * A guest's playback request. Guests can't write the room (only the host can), so it travels on
 * the guest's own member record and the host's app carries it out if the room allows it.
 */
data class GuestCommand(
    val type: String,          // PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, SEEK, PLAY_TRACK
    val positionMs: Long = 0L,
    val seq: Long = 0L,         // increases with every request from the same guest
    /** The song a guest picked (queue, search, library...) for PLAY_TRACK. */
    val track: Track? = null,
    val fromIndex: Int = -1,
    val toIndex: Int = -1,
    /** Exact ordering seen by the guest; rejects stale edits, including duplicate song IDs. */
    val queueIds: List<String> = emptyList()
) {
    companion object {
        /**
         * Play/pause name the state the guest wants, not a flip: when the host and a guest both
         * press pause at once, a flip would restart the host's already-paused song.
         */
        const val PLAY = "play"
        const val PAUSE = "pause"
        /** Older app versions only. */
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val SEEK = "seek"
        const val PLAY_TRACK = "play_track"
        const val MOVE_QUEUE_ITEM = "move_queue_item"
        const val REMOVE_QUEUE_ITEM = "remove_queue_item"
    }

    /** Requests that change the song; these share the 3-second skip lock. */
    val changesSong: Boolean get() = type == NEXT || type == PREVIOUS || type == PLAY_TRACK
    val editsQueue: Boolean get() = type == MOVE_QUEUE_ITEM || type == REMOVE_QUEUE_ITEM

    fun isValidQueueEdit(queue: List<Track>): Boolean = editsQueue &&
        queueIds == queue.map { it.id } && fromIndex in queue.indices &&
        (type == REMOVE_QUEUE_ITEM || (toIndex in queue.indices && fromIndex != toIndex))

    /** Guests see a 100-song window, so their indices aren't always the host's indices. */
    fun queueEditOffset(queue: List<Track>, currentIndex: Int): Int? {
        val (window, windowIndex) = ListenTogetherSyncMath.queueWindow(queue, currentIndex)
        if (!isValidQueueEdit(window)) return null
        return currentIndex.coerceIn(0, queue.lastIndex) - windowIndex
    }
}

/** What the host lets guests do. Stored on the room so every member sees the same rules. */
data class RoomSettings(
    val guestsCanAddSongs: Boolean = true,
    /** Play, pause and seek. Next/previous change the song, so they need [guestsCanPlaySongs]. */
    val guestsCanControlPlayback: Boolean = false,
    /** Pick any song (home, search, playlists, the queue) or skip next/previous, for everyone. */
    val guestsCanPlaySongs: Boolean = false,
    /**
     * Guests may ask for any song (to add or to play now) and the host allows or declines each
     * one — whether or not the add/play switches are on. With it off, those switches decide alone.
     */
    val requireApproval: Boolean = false
) {
    /** Every guest song (added or played) goes to the host as a request. */
    val approvalApplies: Boolean get() = requireApproval

    /** A guest may pick a song to play: directly, or as a request when approval is on. */
    val guestsMayPlaySongs: Boolean get() = guestsCanPlaySongs || requireApproval

    /** A guest may add a song to the queue: directly, or as a request when approval is on. */
    val guestsMayAddSongs: Boolean get() = guestsCanAddSongs || requireApproval

    /** Two people can change the song at once, so the 3-second skip lock applies. */
    val guestsCanChangeSong: Boolean get() = guestsMayPlaySongs

    /** Whether the room lets a guest do [command] (a song change may still wait for the host). */
    fun allows(command: GuestCommand): Boolean = when {
        command.editsQueue -> guestsCanAddSongs
        command.changesSong -> guestsMayPlaySongs
        else -> guestsCanControlPlayback
    }

    fun toMap(): Map<String, Any> = mapOf(
        "guestsCanAddSongs" to guestsCanAddSongs,
        "guestsCanControlPlayback" to guestsCanControlPlayback,
        "guestsCanPlaySongs" to guestsCanPlaySongs,
        "requireApproval" to requireApproval
    )

    companion object {
        fun from(raw: Any?): RoomSettings {
            val m = raw as? Map<*, *> ?: return RoomSettings()
            return RoomSettings(
                guestsCanAddSongs = m["guestsCanAddSongs"] as? Boolean ?: true,
                guestsCanControlPlayback = m["guestsCanControlPlayback"] as? Boolean ?: false,
                guestsCanPlaySongs = m["guestsCanPlaySongs"] as? Boolean ?: false,
                requireApproval = m["requireApproval"] as? Boolean ?: false
            )
        }
    }
}

data class RoomRecommendation(
    val id: String = "",
    val track: Track = Track(),
    val recommendedByUid: String = "",
    val recommendedByName: String = "",
    val note: String = "",
    val upvotes: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "pending" // "pending", "accepted", "played", "declined"
)

data class NativeRoomState(
    val id: String = "",
    val code: String = "",
    val hostId: String = "",
    val hostName: String = "",
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = 0,
    val isPlaying: Boolean = false,
    val playbackPosition: Long = 0,
    val playbackRate: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis(),
    /** Server time of the host's last broadcast; null until the server has stamped it. */
    val serverUpdatedAt: Long? = null,
    /** Bumped by the host on every seek so listeners jump immediately. */
    val seekVersion: Long = 0,
    val status: String = "active",
    val membersList: List<RoomMember> = emptyList(),
    val settings: RoomSettings = RoomSettings(),
    /** The exact video the host is playing for [currentTrack]; null until the host has resolved it. */
    val currentVideoId: String? = null,
    /** Host's clock time the room will close, while a closing warning is showing; null otherwise. */
    val closingAtMs: Long? = null,
    /** Why it's closing: [ROOM_CLOSING_EMPTY] or [ROOM_CLOSING_IDLE]. */
    val closingReason: String? = null,
    /** The host closed their player on [currentTrack] (it's paused there, ready to reopen). */
    val playerClosed: Boolean = false,
    val activity: List<RoomActivity> = emptyList()
)

const val ROOM_CLOSING_EMPTY = "empty"
const val ROOM_CLOSING_IDLE = "idle"


enum class GuestSongDecision { ADD, DECLINE, WAIT_FOR_HOST }

fun RoomSettings.decideGuestSong(): GuestSongDecision = when {
    requireApproval -> GuestSongDecision.WAIT_FOR_HOST
    guestsCanAddSongs -> GuestSongDecision.ADD
    else -> GuestSongDecision.DECLINE
}

/**
 * Picks out guests' new playback requests from roster snapshots. A guest first seen already
 * holding a request (the host's app restarted, say) only sets a baseline; it is never replayed.
 */
class GuestCommandTracker {
    private val lastSeq = mutableMapOf<String, Long>()

    fun reset() = lastSeq.clear()

    fun newCommands(roster: List<RoomMember>, allowed: Boolean): List<Pair<RoomMember, GuestCommand>> =
        newCommands(roster) { allowed }

    /** As above, deciding per request whether the room allows it. */
    fun newCommands(roster: List<RoomMember>, allowed: (GuestCommand) -> Boolean): List<Pair<RoomMember, GuestCommand>> {
        val fresh = mutableListOf<Pair<RoomMember, GuestCommand>>()
        for (member in roster) {
            if (member.isHost) continue
            val command = member.command
            if (command == null) {
                lastSeq.putIfAbsent(member.id, 0L)
                continue
            }
            val previous = lastSeq[member.id]
            lastSeq[member.id] = maxOf(previous ?: 0L, command.seq)
            if (previous != null && command.seq > previous && allowed(command)) fresh += member to command
        }
        return fresh
    }
}
