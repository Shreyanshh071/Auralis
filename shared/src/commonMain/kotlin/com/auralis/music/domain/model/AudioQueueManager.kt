package com.auralis.music.domain.model

data class QueueState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isUserQueue: Boolean = false,
    val listenedIndices: Set<Int> = emptySet()
) {
    val currentTrack: Track?
        get() = if (currentIndex in queue.indices) queue[currentIndex] else null

    val hasNext: Boolean
        get() = when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> queue.isNotEmpty()
            RepeatMode.OFF -> currentIndex < queue.size - 1
        }

    val hasPrevious: Boolean
        get() = when (repeatMode) {
            RepeatMode.ONE, RepeatMode.ALL -> queue.isNotEmpty()
            RepeatMode.OFF -> currentIndex > 0
        }
}

class AudioQueueManager(initialState: QueueState = QueueState()) {

    var state: QueueState = initialState
        private set

    private var originalQueue: List<Track> = emptyList()

    // Separate logical structures for high-priority user queues
    private val playNextQueue = ArrayDeque<Track>()
    private val addToQueueList = ArrayDeque<Track>()

    /** Queue history records occurrences that actually played, not positions skipped over. */
    fun markCurrentListened(): Boolean {
        if (state.currentTrack == null || state.currentIndex in state.listenedIndices) return false
        state = state.copy(listenedIndices = state.listenedIndices + state.currentIndex)
        return true
    }

    fun restoreListenedIndices(indices: Set<Int>) {
        state = state.copy(listenedIndices = indices.filter { it in state.queue.indices }.toSet())
    }

    /**
     * Plays the song at [index] in place. Songs jumped over stay where they are, behind it: Up next
     * continues from the chosen song (tap the 4th of 6 and 5, 6 follow). They aren't history either,
     * since History lists only songs that actually played.
     */
    fun selectIndex(index: Int): QueueState {
        if (index !in state.queue.indices) return state
        state = state.copy(currentIndex = index)
        syncUserQueuesWithUpcoming()
        return state
    }

    /** Replace metadata without changing playback order, index, shuffle, or user queue priority. */
    fun applyVerifiedMetadata(track: Track): Boolean {
        if (state.queue.none { it.id == track.id }) return false
        fun corrected(item: Track): Track = if (item.id == track.id) {
            item.copy(album = track.album, thumbnail = track.thumbnail)
        } else item
        state = state.copy(queue = state.queue.map(::corrected))
        originalQueue = originalQueue.map(::corrected)
        val next = playNextQueue.map(::corrected)
        playNextQueue.clear()
        playNextQueue.addAll(next)
        val later = addToQueueList.map(::corrected)
        addToQueueList.clear()
        addToQueueList.addAll(later)
        return true
    }

    private fun syncUserQueuesWithUpcoming() {
        if (state.currentIndex < 0 || state.currentIndex >= state.queue.size - 1) {
            playNextQueue.clear()
            addToQueueList.clear()
            return
        }
        val upcomingIds = state.queue.subList(state.currentIndex + 1, state.queue.size).map { it.id }.toSet()
        playNextQueue.retainAll { it.id in upcomingIds }
        addToQueueList.retainAll { it.id in upcomingIds }
    }

    fun setQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        preserveOrderIfSame: Boolean = false,
        isUserQueue: Boolean = false,
        /** False keeps [tracks] in the order given (a synced or restored queue); the mode stays as is. */
        keepShuffle: Boolean = true
    ): QueueState {
        // Picking another song from the list that's already playing shuffled keeps the shuffled
        // order: only switching shuffle on or off reshuffles.
        if (keepShuffle && !preserveOrderIfSame && isPlayingShuffled(tracks)) {
            return playWithinShuffle(tracks[startIndex.coerceIn(0, tracks.size - 1)])
        }
        if (!preserveOrderIfSame || originalQueue.isEmpty() || originalQueue.map { it.id } != tracks.map { it.id }) {
            originalQueue = tracks.toList()
            playNextQueue.clear()
            addToQueueList.clear()
        }
        val index = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))

        // Shuffle is a device-wide mode, like Spotify: when it is on and a new list starts
        // (playlist, album, search result...), it stays on — the tapped song plays first and the
        // rest of the new list is shuffled behind it. originalQueue (set above) still holds the
        // in-order list, so turning shuffle off restores it.
        if (!preserveOrderIfSame && keepShuffle && state.isShuffled && tracks.size > 1) {
            val start = tracks[index]
            val rest = tracks.filterIndexed { i, _ -> i != index }.shuffled()
            state = QueueState(
                queue = listOf(start) + rest,
                currentIndex = 0,
                isShuffled = true,
                repeatMode = state.repeatMode,
                isUserQueue = isUserQueue
            )
            syncUserQueuesWithUpcoming()
            return state
        }

        state = QueueState(
            queue = tracks.toList(),
            currentIndex = if (tracks.isNotEmpty()) index else -1,
            isShuffled = state.isShuffled,
            repeatMode = state.repeatMode,
            isUserQueue = isUserQueue
        )
        syncUserQueuesWithUpcoming()
        return state
    }

    /**
     * Whether [tracks] is the list already playing shuffled: the list as it was started (autoplay
     * may have added songs after it) or the shuffled queue itself.
     */
    fun isPlayingShuffled(tracks: List<Track>): Boolean {
        if (!state.isShuffled || tracks.size <= 1 || state.currentTrack == null) return false
        val ids = tracks.map { it.id }
        val fromOriginal = originalQueue.size >= tracks.size && originalQueue.take(tracks.size).map { it.id } == ids
        return fromOriginal || state.queue.map { it.id } == ids
    }

    /**
     * Plays [track] without reshuffling: it moves up to play now, and everything still to come
     * keeps its shuffled order behind it. The song that was playing stays in History.
     */
    private fun playWithinShuffle(track: Track): QueueState {
        val cur = state.currentIndex
        val q = state.queue
        val target = (cur + 1 until q.size).firstOrNull { q[it].id == track.id }
            ?: q.indices.firstOrNull { q[it].id == track.id }
            ?: return state
        if (target == cur) return state
        val order = q.indices.filter { it != target }.toMutableList()
        val insertAt = order.indexOf(cur) + 1
        order.add(insertAt, target)
        state = state.copy(
            queue = order.map { q[it] },
            currentIndex = insertAt,
            listenedIndices = order.indices.filter { order[it] in state.listenedIndices }.toSet()
        )
        syncUserQueuesWithUpcoming()
        return state
    }

    fun playTrack(
        track: Track,
        currentQueue: List<Track> = emptyList(),
        isUserQueue: Boolean = false
    ): QueueState {
        val targetQueue = if (currentQueue.isNotEmpty()) currentQueue else state.queue
        val existingIndex = targetQueue.indexOfFirst { it.id == track.id }
        if (existingIndex != -1) {
            return setQueue(targetQueue, existingIndex, isUserQueue = isUserQueue)
        }
        val newQueue = listOf(track) + targetQueue.filter { it.id != track.id }
        return setQueue(newQueue, 0, isUserQueue = isUserQueue)
    }

    fun appendTracks(newTracks: List<Track>): QueueState {
        if (newTracks.isEmpty()) return state
        val existingIds = state.queue.map { it.id }.toMutableSet()
        val existingBaseTitles = state.queue.mapNotNull {
            val base = com.auralis.music.domain.recommendations.TrackDeduplicator.extractBaseSongTitle(it.title)
            if (base.isNotBlank()) base else null
        }.toMutableSet()

        val uniqueNew = mutableListOf<Track>()
        for (track in newTracks) {
            if (track.id in existingIds) continue
            val base = com.auralis.music.domain.recommendations.TrackDeduplicator.extractBaseSongTitle(track.title)
            if (base.isNotBlank() && base in existingBaseTitles) continue
            uniqueNew.add(track)
            existingIds.add(track.id)
            if (base.isNotBlank()) existingBaseTitles.add(base)
        }

        if (uniqueNew.isEmpty()) return state

        val updatedQueue = state.queue + uniqueNew
        val originalIds = originalQueue.mapTo(mutableSetOf()) { it.id }
        originalQueue = originalQueue + uniqueNew.filter { it.id !in originalIds }
        state = state.copy(queue = updatedQueue)
        return state
    }

    fun isNearEnd(threshold: Int = 3): Boolean {
        if (state.queue.isEmpty()) return true
        return state.currentIndex >= (state.queue.size - threshold)
    }

    /**
     * The track to preload for a gapless handover. Repeat-one has none: a preloaded neighbour
     * plays on when the song ends, while the queue stays on the repeating track.
     */
    fun gaplessNextTrack(): Track? {
        if (state.repeatMode == RepeatMode.ONE) return null
        val next = nextIndex() ?: return null
        return if (next == state.currentIndex) null else state.queue.getOrNull(next)
    }

    fun nextIndex(isUserSkip: Boolean = false): Int? {
        val q = state.queue
        if (q.isEmpty()) return null

        if (isUserSkip) {
            // Explicit user skip: advance past current track regardless of RepeatMode.ONE
            return if (q.size > 1) {
                (state.currentIndex + 1) % q.size
            } else {
                null
            }
        }

        return when (state.repeatMode) {
            RepeatMode.ONE -> state.currentIndex
            RepeatMode.ALL -> (state.currentIndex + 1) % q.size
            RepeatMode.OFF -> {
                if (state.currentIndex < q.size - 1) state.currentIndex + 1 else null
            }
        }
    }

    fun previousIndex(isUserSkip: Boolean = false): Int? {
        val q = state.queue
        if (q.isEmpty()) return null

        if (isUserSkip) {
            // Explicit user skip: go to previous track regardless of RepeatMode.ONE
            return if (q.size > 1) {
                if (state.currentIndex - 1 < 0) q.size - 1 else state.currentIndex - 1
            } else {
                null
            }
        }

        return when (state.repeatMode) {
            RepeatMode.ONE -> state.currentIndex
            RepeatMode.ALL -> if (state.currentIndex - 1 < 0) q.size - 1 else state.currentIndex - 1
            RepeatMode.OFF -> {
                if (state.currentIndex > 0) state.currentIndex - 1 else null
            }
        }
    }

    fun advanceNext(isUserSkip: Boolean = false): Track? {
        val next = nextIndex(isUserSkip) ?: return null
        val nextTrack = state.queue.getOrNull(next)
        if (nextTrack != null) {
            if (playNextQueue.isNotEmpty() && playNextQueue.first().id == nextTrack.id) {
                playNextQueue.removeFirst()
            } else if (addToQueueList.isNotEmpty() && addToQueueList.first().id == nextTrack.id) {
                addToQueueList.removeFirst()
            }
        }
        state = state.copy(currentIndex = next)
        syncUserQueuesWithUpcoming()
        return state.currentTrack
    }

    fun advancePrevious(isUserSkip: Boolean = false): Track? {
        val prev = previousIndex(isUserSkip) ?: return null
        state = state.copy(currentIndex = prev)
        syncUserQueuesWithUpcoming()
        return state.currentTrack
    }

    /**
     * Spotify-style shuffle switch. On: the playing song stays put, songs the user queued (Play
     * next / Add to queue) stay right behind it in their order, everything else is shuffled.
     * Off: the list goes back to its original order around the playing song, the user's queued
     * songs still right behind it. With nothing loaded it only flips the mode.
     */
    fun toggleShuffle(): QueueState = setShuffle(!state.isShuffled)

    fun setShuffle(on: Boolean): QueueState {
        if (on == state.isShuffled) return state
        val cur = state.currentIndex
        if (cur !in state.queue.indices) {
            state = state.copy(isShuffled = on)
            return state
        }
        val userCount = (playNextQueue.size + addToQueueList.size)
            .coerceAtMost(state.queue.size - cur - 1)
        val userIdx = (cur + 1..cur + userCount).toList()
        val rest = state.queue.indices.filter { it != cur && it !in userIdx }

        val order: List<Int>
        val newIndex: Int
        if (on) {
            order = listOf(cur) + userIdx + rest.shuffled()
            newIndex = 0
        } else {
            val remaining = rest.toMutableList()
            val curId = state.queue[cur].id
            val curOriginal = originalQueue.indexOfFirst { it.id == curId }
            val before = mutableListOf<Int>()
            val after = mutableListOf<Int>()
            originalQueue.forEachIndexed { oi, item ->
                if (oi == curOriginal) return@forEachIndexed
                val qi = remaining.firstOrNull { state.queue[it].id == item.id } ?: return@forEachIndexed
                remaining.remove(qi)
                if (curOriginal >= 0 && oi < curOriginal) before += qi else after += qi
            }
            // Whatever the original list never had (queued songs already played) stays as history.
            order = before + remaining + listOf(cur) + userIdx + after
            newIndex = before.size + remaining.size
        }
        val newQueue = order.map { state.queue[it] }
        state = state.copy(
            queue = newQueue,
            currentIndex = newIndex,
            isShuffled = on,
            listenedIndices = order.indices.filter { order[it] in state.listenedIndices }.toSet()
        )
        return state
    }

    /** The list in the order it was started (what turning shuffle off goes back to). */
    val originalOrder: List<Track> get() = originalQueue

    /**
     * Restores the shuffle *mode* after an app restart. The persisted queue is already in its
     * shuffled order, so this only sets the flag (no reshuffle); [originalOrderIds] brings back
     * the order turning shuffle off returns to.
     */
    fun restoreShuffleFlag(isShuffled: Boolean, originalOrderIds: List<String> = emptyList()): QueueState {
        state = state.copy(isShuffled = isShuffled)
        if (originalOrderIds.isNotEmpty()) {
            val pool = state.queue.toMutableList()
            val restored = originalOrderIds.mapNotNull { id -> pool.firstOrNull { it.id == id }?.also { pool.remove(it) } }
            if (restored.isNotEmpty()) originalQueue = restored + pool
        }
        return state
    }

    fun setRepeatMode(mode: RepeatMode): QueueState {
        state = state.copy(repeatMode = mode)
        return state
    }

    fun moveItem(fromIndex: Int, toIndex: Int): QueueState {
        val q = state.queue.toMutableList()
        if (fromIndex !in q.indices || toIndex !in q.indices || fromIndex == toIndex) return state

        val item = q.removeAt(fromIndex)
        q.add(toIndex, item)

        val newActiveIndex = QueueOperations.mapIndexAfterMove(
            fromIndex = fromIndex,
            toIndex = toIndex,
            currentIndex = state.currentIndex
        )

        state = state.copy(queue = q, currentIndex = newActiveIndex,
            listenedIndices = state.listenedIndices.map {
                QueueOperations.mapIndexAfterMove(fromIndex, toIndex, it)
            }.toSet())
        syncUserQueuesWithUpcoming()
        return state
    }

    fun removeItem(removeIndex: Int): QueueState {
        val q = state.queue.toMutableList()
        if (removeIndex !in q.indices) return state

        val removed = q.removeAt(removeIndex)
        val pnIdx = playNextQueue.indexOfFirst { it.id == removed.id }
        if (pnIdx != -1) {
            playNextQueue.removeAt(pnIdx)
        } else {
            val atqIdx = addToQueueList.indexOfFirst { it.id == removed.id }
            if (atqIdx != -1) {
                addToQueueList.removeAt(atqIdx)
            }
        }

        val newActiveIndex = QueueOperations.mapIndexAfterRemove(
            removeIndex = removeIndex,
            currentIndex = state.currentIndex,
            queueSizeAfterRemove = q.size
        )

        state = state.copy(queue = q, currentIndex = newActiveIndex,
            listenedIndices = state.listenedIndices.filter { it != removeIndex }
                .map { if (it > removeIndex) it - 1 else it }.toSet())
        syncUserQueuesWithUpcoming()
        return state
    }

    fun addToQueue(track: Track): QueueState {
        return addToQueue(listOf(track))
    }

    fun addToQueue(tracks: List<Track>): QueueState {
        if (tracks.isEmpty()) return state
        val q = state.queue.toMutableList()
        for (track in tracks) {
            val insertIndex = (state.currentIndex + 1 + playNextQueue.size + addToQueueList.size).coerceIn(0, q.size)
            q.add(insertIndex, track)
            state = state.copy(listenedIndices = state.listenedIndices.map { if (it >= insertIndex) it + 1 else it }.toSet())
            addToQueueList.addLast(track)
        }
        val nextIndex = if (state.currentIndex == -1) 0 else state.currentIndex
        state = state.copy(queue = q, currentIndex = nextIndex, isUserQueue = true)
        return state
    }

    fun playNext(track: Track): QueueState {
        return playNext(listOf(track))
    }

    fun playNext(tracks: List<Track>): QueueState {
        if (tracks.isEmpty()) return state
        val q = state.queue.toMutableList()
        val insertIndex = (state.currentIndex + 1).coerceIn(0, q.size)
        q.addAll(insertIndex, tracks)
        state = state.copy(listenedIndices = state.listenedIndices.map { if (it >= insertIndex) it + tracks.size else it }.toSet())
        for (track in tracks.reversed()) {
            playNextQueue.addFirst(track)
        }
        val nextIndex = if (state.currentIndex == -1) 0 else state.currentIndex
        state = state.copy(queue = q, currentIndex = nextIndex, isUserQueue = true)
        return state
    }

    fun clearQueue(): QueueState {
        originalQueue = emptyList()
        playNextQueue.clear()
        addToQueueList.clear()
        state = QueueState(repeatMode = state.repeatMode)
        return state
    }
}
