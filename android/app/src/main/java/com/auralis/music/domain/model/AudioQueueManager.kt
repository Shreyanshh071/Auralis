package com.auralis.music.domain.model

data class QueueState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val isUserQueue: Boolean = false
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

    fun setQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        preserveOrderIfSame: Boolean = false,
        isUserQueue: Boolean = false
    ): QueueState {
        if (!preserveOrderIfSame || originalQueue.isEmpty() || originalQueue.map { it.id } != tracks.map { it.id }) {
            originalQueue = tracks.toList()
        }
        val index = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        state = QueueState(
            queue = tracks.toList(),
            currentIndex = if (tracks.isNotEmpty()) index else -1,
            isShuffled = if (preserveOrderIfSame) state.isShuffled else false,
            repeatMode = state.repeatMode,
            isUserQueue = isUserQueue
        )
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
        val existingIds = state.queue.map { it.id }.toSet()
        val uniqueNew = newTracks.filter { it.id !in existingIds }
        if (uniqueNew.isEmpty()) return state

        val updatedQueue = state.queue + uniqueNew
        state = state.copy(queue = updatedQueue)
        return state
    }

    fun isNearEnd(threshold: Int = 3): Boolean {
        if (state.queue.isEmpty()) return true
        return state.currentIndex >= (state.queue.size - threshold)
    }

    fun nextIndex(): Int? {
        val q = state.queue
        if (q.isEmpty()) return null

        return when (state.repeatMode) {
            RepeatMode.ONE -> state.currentIndex
            RepeatMode.ALL -> (state.currentIndex + 1) % q.size
            RepeatMode.OFF -> {
                if (state.currentIndex < q.size - 1) state.currentIndex + 1 else null
            }
        }
    }

    fun previousIndex(): Int? {
        val q = state.queue
        if (q.isEmpty()) return null

        return when (state.repeatMode) {
            RepeatMode.ONE -> state.currentIndex
            RepeatMode.ALL -> if (state.currentIndex - 1 < 0) q.size - 1 else state.currentIndex - 1
            RepeatMode.OFF -> {
                if (state.currentIndex > 0) state.currentIndex - 1 else null
            }
        }
    }

    fun advanceNext(): Track? {
        val next = nextIndex() ?: return null
        state = state.copy(currentIndex = next)
        return state.currentTrack
    }

    fun advancePrevious(): Track? {
        val prev = previousIndex() ?: return null
        state = state.copy(currentIndex = prev)
        return state.currentTrack
    }

    fun toggleShuffle(): QueueState {
        val curTrack = state.currentTrack
        val nextShuffled = !state.isShuffled

        val newQueue = if (nextShuffled) {
            if (curTrack != null) {
                val remaining = state.queue.filter { it.id != curTrack.id }.shuffled()
                listOf(curTrack) + remaining
            } else {
                state.queue.shuffled()
            }
        } else {
            if (originalQueue.isNotEmpty()) originalQueue else state.queue
        }

        val newIndex = if (curTrack != null) {
            newQueue.indexOfFirst { it.id == curTrack.id }.coerceAtLeast(0)
        } else {
            0
        }

        state = state.copy(
            queue = newQueue,
            currentIndex = newIndex,
            isShuffled = nextShuffled
        )
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

        state = state.copy(queue = q, currentIndex = newActiveIndex)
        return state
    }

    fun removeItem(removeIndex: Int): QueueState {
        val q = state.queue.toMutableList()
        if (removeIndex !in q.indices) return state

        q.removeAt(removeIndex)
        val newActiveIndex = QueueOperations.mapIndexAfterRemove(
            removeIndex = removeIndex,
            currentIndex = state.currentIndex,
            queueSizeAfterRemove = q.size
        )

        state = state.copy(queue = q, currentIndex = newActiveIndex)
        return state
    }

    fun addToQueue(track: Track): QueueState {
        val q = state.queue.toMutableList()
        q.add(track)
        val nextIndex = if (state.currentIndex == -1) 0 else state.currentIndex
        state = state.copy(queue = q, currentIndex = nextIndex)
        return state
    }

    fun playNext(track: Track): QueueState {
        val q = state.queue.toMutableList()
        val insertIndex = (state.currentIndex + 1).coerceIn(0, q.size)
        q.add(insertIndex, track)
        val nextIndex = if (state.currentIndex == -1) 0 else state.currentIndex
        state = state.copy(queue = q, currentIndex = nextIndex)
        return state
    }

    fun clearQueue(): QueueState {
        originalQueue = emptyList()
        state = QueueState(repeatMode = state.repeatMode)
        return state
    }
}
