package com.auralis.music.bot

import com.auralis.music.domain.model.Track

enum class LoopMode { OFF, TRACK, QUEUE }

/** A song in a server's queue; [requestedBy] is null for songs autoplay added. */
data class QueuedTrack(val track: Track, val requestedBy: String?)

/** One server's queue. Not thread-safe: GuildPlayer guards it. */
class TrackQueue {
    private val upcoming = ArrayDeque<QueuedTrack>()
    private val played = ArrayDeque<String>()

    var current: QueuedTrack? = null
        private set
    var loop = LoopMode.OFF

    val tracks: List<QueuedTrack> get() = upcoming.toList()
    /** Songs autoplay shouldn't repeat: what played recently, is playing, or is queued. */
    val recentIds: Set<String> get() = played.toSet() + upcoming.map { it.track.id } + listOfNotNull(current?.track?.id)

    fun add(items: List<QueuedTrack>, next: Boolean = false) {
        if (next) upcoming.addAll(0, items) else upcoming.addAll(items)
    }

    /** Moves to the next song; [skipped] overrides track loop, which would replay a failed song forever. */
    fun advance(skipped: Boolean = false): QueuedTrack? {
        val finished = current
        if (finished != null && loop == LoopMode.TRACK && !skipped) return finished
        if (finished != null) {
            played.addLast(finished.track.id)
            while (played.size > MAX_REMEMBERED) played.removeFirst()
            if (loop == LoopMode.QUEUE) upcoming.addLast(finished)
        }
        current = upcoming.removeFirstOrNull()
        return current
    }

    /** Removes the song at a 1-based queue position. */
    fun remove(position: Int): QueuedTrack? =
        if (position in 1..upcoming.size) upcoming.removeAt(position - 1) else null

    /** Moves a song between 1-based queue positions. */
    fun move(from: Int, to: Int): QueuedTrack? {
        if (from !in 1..upcoming.size || to !in 1..upcoming.size) return null
        return upcoming.removeAt(from - 1).also { upcoming.add(to - 1, it) }
    }

    fun shuffle() {
        val shuffled = upcoming.shuffled()
        upcoming.clear()
        upcoming.addAll(shuffled)
    }

    fun clear() {
        upcoming.clear()
        current = null
    }

    private companion object {
        const val MAX_REMEMBERED = 50
    }
}
