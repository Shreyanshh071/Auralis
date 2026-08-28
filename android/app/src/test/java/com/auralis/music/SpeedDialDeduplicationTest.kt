package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.ui.viewmodel.SpeedDialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedDialDeduplicationTest {

    @Test
    fun `deduplicateTracks removes duplicates with identical title and artist but different YouTube IDs`() {
        val track1 = Track(
            id = "yt_audio_1",
            title = "Lovers Rock",
            artist = "TV Girl",
            thumbnail = "https://i.ytimg.com/vi/yt_audio_1/hqdefault.jpg",
            duration = 214L
        )
        val track2 = Track(
            id = "yt_video_2",
            title = "Lovers Rock",
            artist = "TV Girl",
            thumbnail = "https://i.ytimg.com/vi/yt_video_2/hqdefault.jpg",
            duration = 215L
        )

        val tracks = listOf(track1, track2)
        val deduplicated = TrackDeduplicator.deduplicateTracks(tracks)

        assertEquals(1, deduplicated.size)
        assertEquals("yt_audio_1", deduplicated[0].id)
        assertEquals("Lovers Rock", deduplicated[0].title)
        assertEquals("TV Girl", deduplicated[0].artist)
    }

    @Test
    fun `deduplicateTracks removes duplicates with bracket noise in title`() {
        val clean = Track(id = "1", title = "Lovers Rock", artist = "TV Girl")
        val officialAudio = Track(id = "2", title = "Lovers Rock (Official Audio)", artist = "TV Girl")
        val officialVideo = Track(id = "3", title = "Lovers Rock [Official Video]", artist = "TV Girl")
        val rawAudio = Track(id = "4", title = "Lovers Rock (Audio)", artist = "TV Girl")
        val lyrics = Track(id = "5", title = "Lovers Rock (Lyrics)", artist = "TV Girl")

        val list = listOf(clean, officialAudio, officialVideo, rawAudio, lyrics)
        val deduplicated = TrackDeduplicator.deduplicateTracks(list)

        assertEquals(1, deduplicated.size)
        assertEquals("Lovers Rock", deduplicated[0].title)
    }

    @Test
    fun `deduplicateTracks handles YouTube title with hyphenated artist vs separate artist`() {
        val hyphenated = Track(id = "1", title = "TV Girl - Lovers Rock", artist = "")
        val standard = Track(id = "2", title = "Lovers Rock", artist = "TV Girl")

        val deduplicated = TrackDeduplicator.deduplicateTracks(listOf(hyphenated, standard))

        assertEquals(1, deduplicated.size)
        assertEquals("Lovers Rock", deduplicated[0].title)
    }

    @Test
    fun `deduplicateTracks matches artist with - Topic or VEVO suffix`() {
        val standard = Track(id = "1", title = "Lovers Rock", artist = "TV Girl")
        val topic = Track(id = "2", title = "Lovers Rock", artist = "TV Girl - Topic")
        val vevo = Track(id = "3", title = "Lovers Rock", artist = "TV Girl VEVO")

        val deduplicated = TrackDeduplicator.deduplicateTracks(listOf(standard, topic, vevo))

        assertEquals(1, deduplicated.size)
        assertEquals("TV Girl", deduplicated[0].artist)
    }

    @Test
    fun `deduplicateTracks enriches earlier track with better thumbnail and metadata`() {
        val earlyNoThumb = Track(id = "1", title = "Lovers Rock", artist = "TV Girl", thumbnail = "", duration = 0L)
        val laterWithThumb = Track(id = "2", title = "Lovers Rock", artist = "TV Girl", thumbnail = "https://thumb.jpg", duration = 214L)

        val deduplicated = TrackDeduplicator.deduplicateTracks(listOf(earlyNoThumb, laterWithThumb))

        assertEquals(1, deduplicated.size)
        assertEquals("https://thumb.jpg", deduplicated[0].thumbnail)
        assertEquals(214L, deduplicated[0].duration)
    }

    @Test
    fun `deduplicateTracks preserves distinct songs by different artists`() {
        val t1 = Track(id = "1", title = "Lovers Rock", artist = "TV Girl")
        val t2 = Track(id = "2", title = "Let Down", artist = "Radiohead")
        val t3 = Track(id = "3", title = "Choo Lo", artist = "The Local Train")
        val t4 = Track(id = "4", title = "GUZARISH", artist = "Javed Ali")

        val deduplicated = TrackDeduplicator.deduplicateTracks(listOf(t1, t2, t3, t4))

        assertEquals(4, deduplicated.size)
        assertEquals("Lovers Rock", deduplicated[0].title)
        assertEquals("Let Down", deduplicated[1].title)
        assertEquals("Choo Lo", deduplicated[2].title)
        assertEquals("GUZARISH", deduplicated[3].title)
    }

    @Test
    fun `deduplicateTracks handles Indian movie track attributions and noise`() {
        val t1 = Track(id = "1", title = "Kesariya (From \"Brahmastra\")", artist = "Arijit Singh")
        val t2 = Track(id = "2", title = "Kesariya [Official 4K Video]", artist = "Arijit Singh")

        val deduplicated = TrackDeduplicator.deduplicateTracks(listOf(t1, t2))

        assertEquals(1, deduplicated.size)
    }

    @Test
    fun `isDuplicateTrack correctly identifies duplicate variations`() {
        val a = Track(id = "a", title = "Starboy [Official Video] [4K]", artist = "The Weeknd")
        val b = Track(id = "b", title = "Starboy", artist = "The Weeknd - Topic")

        assertTrue("Tracks must be recognized as duplicate", TrackDeduplicator.isDuplicateTrack(a, b))

        val c = Track(id = "c", title = "Starboy", artist = "The Weeknd")
        val d = Track(id = "d", title = "Blinding Lights", artist = "The Weeknd")

        assertFalse("Different songs must not be duplicates", TrackDeduplicator.isDuplicateTrack(c, d))
    }

    @Test
    fun `deduplicateTracks guarantees unique tracks across Speed Dial pages`() {
        // Construct a realistic list with multiple duplicates of Lovers Rock and others
        val candidates = listOf(
            Track(id = "yt_1", title = "Lovers Rock", artist = "TV Girl"),
            Track(id = "yt_2", title = "Lovers Rock", artist = "TV Girl"), // duplicate
            Track(id = "yt_3", title = "Let Down", artist = "Radiohead"),
            Track(id = "yt_4", title = "Lovers Rock (Official Audio)", artist = "TV Girl"), // duplicate
            Track(id = "yt_5", title = "I Thought I Saw Your Face Today", artist = "She & Him"),
            Track(id = "yt_6", title = "Love Me Not", artist = "Ravyn Lenae"),
            Track(id = "yt_7", title = "Ye Tune Kya Kiya", artist = "Pritam"),
            Track(id = "yt_8", title = "Choo Lo", artist = "The Local Train"),
            Track(id = "yt_9", title = "GUZARISH", artist = "Javed Ali"),
            Track(id = "yt_10", title = "Lovers Rock [Official Video]", artist = "TV Girl - Topic"), // duplicate
            Track(id = "yt_11", title = "All I Need", artist = "Radiohead")
        )

        val unique = TrackDeduplicator.deduplicateTracks(candidates)

        // Lovers Rock should appear exactly ONCE
        val loversRockCount = unique.count { it.title.equals("Lovers Rock", ignoreCase = true) }
        assertEquals("Lovers Rock must only appear once", 1, loversRockCount)

        // All 8 unique titles
        val titles = unique.map { it.title }
        assertEquals(titles.distinct().size, unique.size)
    }
}
