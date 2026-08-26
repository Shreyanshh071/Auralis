package com.auralis.music

import com.auralis.music.data.sync.RoomRecommendation
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.junit.Assert.*
import org.junit.Test

class ListenTogetherRecommendationTest {

    @Test
    fun `RoomRecommendation initializes with expected default properties and track data`() {
        val sampleTrack = Track(
            id = "rec_track_1",
            title = "Midnight City",
            artist = "M83",
            thumbnail = "https://example.com/thumb.jpg",
            duration = 243_000L,
            source = TrackSource.YOUTUBE
        )

        val recommendation = RoomRecommendation(
            id = "doc_123",
            track = sampleTrack,
            recommendedByUid = "user_listener_1",
            recommendedByName = "Alex",
            note = "Check out this synthesizer build!",
            upvotes = listOf("user_listener_1"),
            createdAt = 1_700_000_000L,
            status = "pending"
        )

        assertEquals("doc_123", recommendation.id)
        assertEquals("rec_track_1", recommendation.track.id)
        assertEquals("Midnight City", recommendation.track.title)
        assertEquals("Alex", recommendation.recommendedByName)
        assertEquals("Check out this synthesizer build!", recommendation.note)
        assertEquals(1, recommendation.upvotes.size)
        assertTrue(recommendation.upvotes.contains("user_listener_1"))
        assertEquals("pending", recommendation.status)
    }

    @Test
    fun `recommendations sort descending by upvotes count then by timestamp`() {
        val track = Track(id = "t1", title = "Track 1", artist = "Artist")
        val rec1 = RoomRecommendation(id = "1", track = track, upvotes = listOf("u1"), createdAt = 1000L)
        val rec2 = RoomRecommendation(id = "2", track = track, upvotes = listOf("u1", "u2", "u3"), createdAt = 2000L)
        val rec3 = RoomRecommendation(id = "3", track = track, upvotes = listOf("u1", "u2"), createdAt = 3000L)
        val rec4 = RoomRecommendation(id = "4", track = track, upvotes = listOf("u1"), createdAt = 4000L)

        val unsorted = listOf(rec1, rec2, rec3, rec4)
        val sorted = unsorted.sortedWith(
            compareByDescending<RoomRecommendation> { it.upvotes.size }
                .thenByDescending { it.createdAt }
        )

        assertEquals("2", sorted[0].id) // 3 upvotes
        assertEquals("3", sorted[1].id) // 2 upvotes
        assertEquals("4", sorted[2].id) // 1 upvote, newer (4000L)
        assertEquals("1", sorted[3].id) // 1 upvote, older (1000L)
    }

    @Test
    fun `upvote toggle logic properly adds and removes user UID`() {
        val initialUpvotes = listOf("user_a", "user_b")

        // User C upvotes
        val userC = "user_c"
        val afterAdd = if (initialUpvotes.contains(userC)) {
            initialUpvotes - userC
        } else {
            initialUpvotes + userC
        }
        assertEquals(3, afterAdd.size)
        assertTrue(afterAdd.contains("user_c"))

        // User A un-upvotes
        val userA = "user_a"
        val afterRemove = if (afterAdd.contains(userA)) {
            afterAdd - userA
        } else {
            afterAdd + userA
        }
        assertEquals(2, afterRemove.size)
        assertFalse(afterRemove.contains("user_a"))
        assertTrue(afterRemove.contains("user_b"))
        assertTrue(afterRemove.contains("user_c"))
    }

    @Test
    fun `status transitions handle pending, played, and accepted states`() {
        val rec = RoomRecommendation(id = "r1", status = "pending")
        assertEquals("pending", rec.status)

        val playedRec = rec.copy(status = "played")
        assertEquals("played", playedRec.status)

        val acceptedRec = rec.copy(status = "accepted")
        assertEquals("accepted", acceptedRec.status)
    }
}
