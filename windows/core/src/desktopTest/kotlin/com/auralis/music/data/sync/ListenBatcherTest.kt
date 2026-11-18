package com.auralis.music.data.sync

import kotlin.test.*

class ListenBatcherTest {
    private class Memory : LibraryCloudStore {
        val data = mutableMapOf<String, String>()
        override fun read(uid: String, name: String) = data["$uid:$name"]
        override fun write(uid: String, name: String, value: String?) {
            if (value == null) data.remove("$uid:$name") else data["$uid:$name"] = value
        }
    }
    @Test fun playingSongCanReachHistoryBeforeItFinishesAndRetriesAreIdempotent() {
        val batcher = ListenBatcher(Memory())
        val now = 2_000_000_000L
        val playing = ListenEvent("song", now - 10_000, 5_000)
        assertEquals(emptyList(), batcher.ready("u", listOf(playing), now))
        assertEquals(listOf(playing), batcher.ready("u", listOf(playing), now, includePlaying = true))
        batcher.markSent("u", listOf(playing), now)
        assertEquals(emptyList(), batcher.ready("u", listOf(playing), now, includePlaying = true))
        val longer = playing.copy(listenedMs = 30_000)
        assertEquals(listOf(longer), batcher.ready("u", listOf(longer), now + 30_000, includePlaying = true))
    }
    @Test fun historicalListensAreNotResentAfterEachSyncOrRestart() {
        val store = Memory()
        val now = 2_000_000_000L
        val old = ListenEvent("old", now - 30 * 86_400_000L, 60_000)
        val batcher = ListenBatcher(store)
        assertEquals(listOf(old), batcher.ready("u", listOf(old), now))
        batcher.markSent("u", listOf(old), now)
        assertEquals(emptyList(), ListenBatcher(store).ready("u", listOf(old), now + 86_400_000L))
        assertEquals(listOf(old.copy(listenedMs = 70_000)), batcher.ready("u", listOf(old.copy(listenedMs = 70_000)), now))
    }
}
