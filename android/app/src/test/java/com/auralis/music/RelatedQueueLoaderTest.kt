package com.auralis.music

import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RelatedQueueLoaderTest {
    private val seed = Track(id = "seed", title = "Current Song", artist = "Radiohead")
    private fun song(id: String, artist: String = "Radiohead") = Track(id = id, title = "Song $id", artist = artist)

    @Test fun persistenceAndPlaylistOriginNeverDisableAutoplay() {
        for (persistent in listOf(true, false)) for (manual in listOf(true, false)) {
            val state = QueueState(listOf(seed), 0, isUserQueue = manual)
            assertTrue(AutoQueuePolicy.canExtend(PlayerSettings(persistentQueue = persistent), state, false))
            assertTrue(AutoQueuePolicy.needsRefill(state))
            assertFalse(AutoQueuePolicy.canExtend(PlayerSettings(autoLoadMore = false), state, false))
            assertFalse(AutoQueuePolicy.canExtend(PlayerSettings(), state, true))
        }
    }

    @Test fun refillStartsBeforeTheTailIsEmptyAndRepeatsForAppendedBatches() {
        val list = (0..14).map { song("$it") }
        assertFalse(AutoQueuePolicy.needsRefill(QueueState(list, 0)))
        assertTrue(AutoQueuePolicy.needsRefill(QueueState(list, 11)))
        assertTrue(AutoQueuePolicy.needsRefill(QueueState(list, 14)))
        val extended = list + (15..28).map { song("$it") }
        assertFalse(AutoQueuePolicy.needsRefill(QueueState(extended, 14)))
        assertTrue(AutoQueuePolicy.needsRefill(QueueState(extended, 27)))
    }

    @Test fun relatedRadioIsPreferredWithoutRandomHistoryOrArtistPadding() = runTest {
        val expected = listOf(song("related", "Related Artist"))
        val result = RelatedQueueLoader.load(seed, listOf(seed), { expected }, { error("Do not pad related results") })
        assertEquals(expected, result)
    }

    @Test fun unavailableRadioFallsBackOnlyToTheCreditedArtist() = runTest {
        val result = RelatedQueueLoader.load(seed, listOf(seed), { error("offline") }, {
            listOf(song("same"), song("unrelated", "Other Artist"))
        })
        assertEquals(listOf("same"), result.map { it.id })
    }

    @Test fun exhaustedRadioUsesItsRelatedSeedsToFindMoreUnplayedSongs() = runTest {
        val neighbor = song("neighbor", "Related Artist")
        val fresh = song("fresh", "Another Related Artist")
        val result = RelatedQueueLoader.load(seed, listOf(seed, neighbor), {
            if (it.id == seed.id) listOf(neighbor) else listOf(fresh)
        }, { emptyList() })
        assertEquals(listOf(fresh), result)
    }

    @Test fun noCandidatesReturnsEmptyAndDoesNotReusePlayedSongs() = runTest {
        assertTrue(RelatedQueueLoader.load(seed, listOf(seed), { listOf(seed) }, { emptyList() }).isEmpty())
        assertTrue(RelatedQueueLoader.fresh(seed, listOf(seed, song("played")), listOf(seed, song("played"))).isEmpty())
    }

    @Test fun batchIsCappedAndRemovesDuplicateIdsAndAlternateVersions() {
        val candidates = listOf(seed, song("a"), song("a"), song("other-id").copy(title = "Song a (Official Video)")) + (0..30).map { song("new$it") }
        val batch = RelatedQueueLoader.fresh(seed, candidates, listOf(seed))
        assertEquals(14, batch.size)
        assertEquals(14, batch.map { it.id }.distinct().size)
        assertFalse(batch.any { it.id == "seed" || it.id == "other-id" })
    }

    @Test fun songsTheRecentlyPlayedRadiosAgreeOnLeadTheBatch() = runTest {
        val anchor = song("anchor", "Thom Yorke")
        val drift = song("drift", "Pop Star")
        val match = song("match", "Portishead")
        val shared = song("shared", "Massive Attack")
        val result = RelatedQueueLoader.load(seed, listOf(anchor, seed), radio = {
            if (it.id == seed.id) listOf(drift, match) else listOf(match, shared)
        }, artistSongs = { emptyList() }, anchors = listOf(anchor))
        // The anchor's radio backs "match" (moved ahead of the drifting pick) and adds "shared".
        assertEquals(listOf("match", "drift", "shared"), result.map { it.id })
    }

    @Test fun aDifferentScriptSinksButStays() {
        val hindiSeed = Track(id = "s", title = "तुम ही हो", artist = "Arijit Singh")
        val korean = Track(id = "k", title = "봄날", artist = "BTS")
        val hindi = Track(id = "h", title = "चन्ना मेरेया", artist = "Arijit Singh")
        assertEquals(listOf("h", "k"), RelatedQueueLoader.rank(hindiSeed, listOf(korean, hindi), emptyList()).map { it.id })
        assertEquals("devanagari", RelatedQueueLoader.dominantScript(hindiSeed.title))
        assertEquals("latin", RelatedQueueLoader.dominantScript("Karma Police"))
    }

    @Test fun withoutAnchorsTheRadioOrderIsKept() {
        val list = (0..5).map { song("r$it", "Artist $it") }
        assertEquals(list, RelatedQueueLoader.rank(seed, list, emptyList()))
    }

    @Test(expected = CancellationException::class)
    fun cancellationNeverBecomesAnUnrelatedFallback() = runTest {
        RelatedQueueLoader.load(seed, listOf(seed), { throw CancellationException() }, { error("Cancelled") })
    }

    @Test fun shuffleIsADeviceWideModeThatOutlivesEveryList() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(seed, song("a")))
        manager.toggleShuffle()
        val shuffled = manager.setQueue(listOf(song("b"), song("c")))
        assertTrue(shuffled.isShuffled)
        assertEquals("b", shuffled.currentTrack?.id)
        assertTrue(manager.setQueue(listOf(song("single"))).isShuffled)
        // A synced/restored queue keeps its given order, but the switch stays on.
        val synced = manager.setQueue(listOf(song("d"), song("e"), song("f")), keepShuffle = false)
        assertEquals(listOf("d", "e", "f"), synced.queue.map { it.id })
        assertTrue(synced.isShuffled)
        assertFalse(manager.toggleShuffle().isShuffled)
        assertFalse(manager.setQueue(listOf(song("g"), song("h"))).isShuffled)
    }

    @Test fun shuffleWithNothingLoadedOnlyFlipsTheMode() {
        val manager = AudioQueueManager()
        assertTrue(manager.toggleShuffle().isShuffled)
        val (first, list) = song("x") to (0..9).map { song("s$it") }
        val started = manager.setQueue(listOf(first) + list, 0)
        assertTrue(started.isShuffled)
        assertEquals("x", started.currentTrack?.id)
        assertEquals((listOf(first) + list).map { it.id }.toSet(), started.queue.map { it.id }.toSet())
    }

    @Test fun togglingKeepsUserQueuedSongsNextAndRestoresOrderAroundTheCurrentSong() {
        val manager = AudioQueueManager()
        val songs = (0..9).map { song("s$it") }
        manager.setQueue(songs, 3)
        manager.playNext(song("next"))
        manager.addToQueue(song("later"))
        val on = manager.toggleShuffle()
        assertEquals(listOf("s3", "next", "later"), on.queue.take(3).map { it.id })
        assertEquals(0, on.currentIndex)
        val off = manager.toggleShuffle()
        assertEquals(listOf("s0", "s1", "s2", "s3", "next", "later", "s4", "s5", "s6", "s7", "s8", "s9"), off.queue.map { it.id })
        assertEquals("s3", off.currentTrack?.id)
    }

    @Test fun pickingAnotherSongWhileShuffledKeepsTheShuffledOrder() {
        val manager = AudioQueueManager()
        val songs = (0..11).map { song("s$it") }
        manager.toggleShuffle()
        val shuffled = manager.setQueue(songs, 0).queue.map { it.id }
        val picked = shuffled[7]
        // The same list again (as a playlist page passes it), another song tapped.
        val after = manager.setQueue(songs, songs.indexOfFirst { it.id == picked })
        assertEquals(picked, after.currentTrack?.id)
        assertEquals(shuffled.filter { it != picked }, after.queue.map { it.id }.filter { it != picked })
        assertEquals(shuffled[1], after.queue[after.currentIndex + 1].id)
        assertTrue(after.isShuffled)
        // The shuffled queue itself passed back (e.g. from the queue screen) doesn't reshuffle either.
        val again = manager.setQueue(after.queue, after.currentIndex + 2)
        assertEquals(after.queue.map { it.id }.filter { it != again.currentTrack?.id }, again.queue.map { it.id }.filter { it != again.currentTrack?.id })
        // A different list still starts its own shuffle.
        val other = (0..5).map { song("o$it") }
        assertEquals(other.map { it.id }.toSet(), manager.setQueue(other, 2).queue.map { it.id }.toSet())
        // Only the switch reshuffles: off restores the list order.
        assertEquals(other.map { it.id }, manager.toggleShuffle().queue.map { it.id })
    }

    @Test fun restoredShuffleCanStillGoBackToTheOriginalOrder() {
        val manager = AudioQueueManager()
        val songs = (0..5).map { song("s$it") }
        manager.setQueue(listOf(songs[2], songs[5], songs[0], songs[4], songs[1], songs[3]), 0, keepShuffle = false)
        manager.restoreShuffleFlag(true, songs.map { it.id })
        val off = manager.toggleShuffle()
        assertEquals(songs.map { it.id }, off.queue.map { it.id })
        assertEquals("s2", off.currentTrack?.id)
    }

    @Test fun disablingShuffleKeepsGeneratedSongsInTheQueue() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(seed, song("a")))
        manager.toggleShuffle()
        manager.appendTracks(listOf(song("related")))
        val unshuffled = manager.toggleShuffle()
        assertEquals(listOf("seed", "a", "related"), unshuffled.queue.map { it.id })
    }
}
