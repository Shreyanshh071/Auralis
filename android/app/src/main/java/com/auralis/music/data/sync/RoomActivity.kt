package com.auralis.music.data.sync

/** A host-confirmed action, shared with every listener through the room document. */
data class RoomActivity(val id: String, val message: String, val actorId: String = "") {
    fun toMap(): Map<String, String> = mapOf("id" to id, "message" to message, "actorId" to actorId)

    fun shouldNotify(userId: String): Boolean = actorId.isBlank() || actorId != userId

    companion object {
        const val HISTORY_LIMIT = 20

        fun from(value: Any?): RoomActivity? {
            val map = value as? Map<*, *> ?: return null
            val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val message = (map["message"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return RoomActivity(id, message, map["actorId"] as? String ?: "")
        }
    }
}

/** The first snapshot is history; subsequent snapshots announce each new action once. */
class RoomActivityTracker {
    private var seen: Set<String>? = null

    fun newActivities(activities: List<RoomActivity>): List<RoomActivity> {
        val previous = seen
        seen = activities.map { it.id }.toSet()
        return if (previous == null) emptyList()
        else activities.distinctBy { it.id }.filter { it.id !in previous }
    }
}

/** Keeps attribution until an asynchronous song selection or seek reaches the engine. */
data class PendingRoomAction(
    val command: GuestCommand,
    val actorId: String,
    val message: String,
    val trackId: String?,
    val expiresAt: Long
) {
    fun matches(applied: GuestCommand): Boolean = when (command.type) {
        GuestCommand.PLAY_TRACK, GuestCommand.NEXT, GuestCommand.PREVIOUS ->
            (applied.type == GuestCommand.PLAY_TRACK && (trackId == null || trackId == applied.track?.id)) ||
                (command.type == GuestCommand.PREVIOUS && applied.type == GuestCommand.SEEK && applied.positionMs == 0L) ||
                (command.type == GuestCommand.NEXT && trackId == null && applied.type == GuestCommand.PAUSE)
        GuestCommand.SEEK -> applied.type == GuestCommand.SEEK && command.positionMs == applied.positionMs
        GuestCommand.PLAY -> applied.type == GuestCommand.PLAY ||
            (applied.type == GuestCommand.PLAY_TRACK && trackId == applied.track?.id)
        GuestCommand.TOGGLE -> applied.type == GuestCommand.PLAY || applied.type == GuestCommand.PAUSE ||
            (applied.type == GuestCommand.PLAY_TRACK && trackId == applied.track?.id)
        else -> command.type == applied.type
    }
}
