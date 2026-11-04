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

    @Test(expected = CancellationException::class)
    fun cancellationNeverBecomesAnUnrelatedFallback() = runTest {
        RelatedQueueLoader.load(seed, listOf(seed), { throw CancellationException() }, { error("Cancelled") })
    }

    @Test fun permanentShuffleKeepsChoiceButNeverForcesItOn() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(seed, song("a")))
        manager.toggleShuffle()
        val shuffled = manager.setQueue(listOf(song("b"), song("c")), keepShuffle = true)
        assertTrue(shuffled.isShuffled)
        assertEquals("b", shuffled.currentTrack?.id)
        assertTrue(manager.setQueue(listOf(song("single")), keepShuffle = true).isShuffled)
        assertFalse(manager.setQueue(listOf(song("d"), song("e")), keepShuffle = false).isShuffled)
        assertFalse(manager.setQueue(listOf(seed), keepShuffle = true).isShuffled)
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
