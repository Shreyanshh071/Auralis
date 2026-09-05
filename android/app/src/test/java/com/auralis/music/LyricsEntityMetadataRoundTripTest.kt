package com.auralis.music

import com.auralis.music.data.local.entity.LyricsEntity
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsEntityMetadataRoundTripTest {

    private fun sampleLyrics(
        title: String = "Test Title",
        artist: String = "Test Artist",
        durationMs: Long? = null,
        leadingSilenceMs: Long? = null
    ): LyricsData {
        return LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                LyricLine(
                    time = 1000L,
                    text = "Hello world",
                    words = listOf(
                        LyricWord(word = "Hello", time = 1000L, duration = 500L),
                        LyricWord(word = "world", time = 1500L, duration = 500L)
                    )
                )
            ),
            plainLyrics = "Hello world",
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = title,
            artistName = artist,
            durationMs = durationMs,
            leadingSilenceMs = leadingSilenceMs
        )
    }

    @Test
    fun `durationMs round-trips accurately through LyricsEntity`() {
        val original = sampleLyrics(durationMs = 215400L)
        val entity = LyricsRepositoryImpl.domainToEntity("key_1", original, "Test Title", "Test Artist")

        assertEquals(215400L, entity.durationMs)

        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Test Title", "Test Artist")
        assertNotNull(restored)
        assertEquals(215400L, restored?.durationMs)
    }

    @Test
    fun `leadingSilenceMs round-trips accurately through LyricsEntity`() {
        val original = sampleLyrics(leadingSilenceMs = 1250L)
        val entity = LyricsRepositoryImpl.domainToEntity("key_2", original, "Test Title", "Test Artist")

        assertEquals(1250L, entity.leadingSilenceMs)

        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Test Title", "Test Artist")
        assertNotNull(restored)
        assertEquals(1250L, restored?.leadingSilenceMs)
    }

    @Test
    fun `null metadata remains null through round-trip`() {
        val original = sampleLyrics(durationMs = null, leadingSilenceMs = null)
        val entity = LyricsRepositoryImpl.domainToEntity("key_3", original, "Test Title", "Test Artist")

        assertNull(entity.durationMs)
        assertNull(entity.leadingSilenceMs)

        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Test Title", "Test Artist")
        assertNotNull(restored)
        assertNull(restored?.durationMs)
        assertNull(restored?.leadingSilenceMs)
    }

    @Test
    fun `cached Creep retains 238640ms duration and 940ms leading silence`() {
        val creep = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                LyricLine(
                    time = 19764L,
                    text = "When you were here before",
                    words = listOf(
                        LyricWord(word = "When", time = 19764L, duration = 341L),
                        LyricWord(word = "you", time = 20105L, duration = 94L),
                        LyricWord(word = "were", time = 20199L, duration = 127L),
                        LyricWord(word = "here", time = 20326L, duration = 281L),
                        LyricWord(word = "before", time = 20607L, duration = 1293L)
                    )
                ),
                LyricLine(
                    time = 230693L,
                    text = "Whatever makes you happy",
                    words = listOf(
                        LyricWord(word = "happy", time = 230693L, duration = 1500L)
                    )
                )
            ),
            plainLyrics = "When you were here before\nWhatever makes you happy",
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Creep",
            artistName = "Radiohead",
            durationMs = 238640L,
            leadingSilenceMs = 940L
        )

        val entity = LyricsRepositoryImpl.domainToEntity("creep_key", creep, "Creep", "Radiohead")
        assertEquals(238640L, entity.durationMs)
        assertEquals(940L, entity.leadingSilenceMs)

        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Creep", "Radiohead")
        assertNotNull(restored)
        assertEquals(238640L, restored?.durationMs)
        assertEquals(940L, restored?.leadingSilenceMs)
        // Effective duration should prefer stated durationMs over last vocal word
        assertEquals(238640L, restored?.effectiveDurationMs)
    }

    @Test
    fun `cached Love Me Not retains its provider metadata`() {
        val loveMeNot = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                LyricLine(
                    time = 16834L,
                    text = "See, right now, I need you",
                    words = listOf(
                        LyricWord(word = "See,", time = 16834L, duration = 355L),
                        LyricWord(word = "right", time = 17189L, duration = 216L),
                        LyricWord(word = "now,", time = 17405L, duration = 405L)
                    )
                ),
                LyricLine(
                    time = 201849L,
                    text = "Love me not",
                    words = listOf(
                        LyricWord(word = "not", time = 201849L, duration = 800L)
                    )
                )
            ),
            plainLyrics = "See, right now, I need you\nLove me not",
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Love Me Not",
            artistName = "Ravyn Lenae",
            durationMs = 213481L,
            leadingSilenceMs = 600L
        )

        val entity = LyricsRepositoryImpl.domainToEntity("lmn_key", loveMeNot, "Love Me Not", "Ravyn Lenae")
        assertEquals(213481L, entity.durationMs)
        assertEquals(600L, entity.leadingSilenceMs)

        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Love Me Not", "Ravyn Lenae")
        assertNotNull(restored)
        assertEquals(213481L, restored?.durationMs)
        assertEquals(600L, restored?.leadingSilenceMs)
        assertEquals(213481L, restored?.effectiveDurationMs)
    }

    @Test
    fun `existing lyrics without metadata continue to load correctly from legacy cache`() {
        // Simulates an existing SQLite row migrated from version 7 where durationMs and leadingSilenceMs are null
        val legacyEntity = LyricsEntity(
            trackId = "legacy_key",
            syncType = SyncType.LINE_SYNC.name,
            linesJson = """[{"time":1500,"text":"Line 1"},{"time":3000,"text":"Line 2"}]""",
            plainLyrics = "Line 1\nLine 2",
            provider = LyricsProvider.LRCLIB.name,
            trackName = "Legacy Track",
            artistName = "Legacy Artist",
            hasWordTiming = false,
            pipelineVersion = 1,
            durationMs = null,
            leadingSilenceMs = null
        )

        val restored = LyricsRepositoryImpl.entityToDomain(legacyEntity, "Legacy Track", "Legacy Artist")
        assertNotNull(restored)
        assertEquals(SyncType.LINE_SYNC, restored?.syncType)
        assertEquals(2, restored?.lines?.size)
        assertEquals("Legacy Track", restored?.trackName)
        assertNull(restored?.durationMs)
        assertNull(restored?.leadingSilenceMs)
        // Fallback to last line timestamp
        assertEquals(3000L, restored?.effectiveDurationMs)
    }

    @Test
    fun `network-fetched BetterLyrics TTML metadata reaches entity and survives round-trip`() {
        val ttml = java.io.File("c:/Users/shrey/OneDrive/Desktop/Auralis/scratch/creep.ttml").readText()
        val parsed = com.auralis.music.data.parser.BetterLyricsParser.parse(
            content = ttml,
            provider = LyricsProvider.BETTER_LYRICS,
            trackName = "Creep",
            artistName = "Radiohead"
        )
        assertNotNull(parsed)
        assertEquals(238640L, parsed?.durationMs)
        assertEquals(940L, parsed?.leadingSilenceMs)

        val entity = LyricsRepositoryImpl.domainToEntity("creep_test", parsed!!, "Creep", "Radiohead")
        assertEquals(238640L, entity.durationMs)
        assertEquals(940L, entity.leadingSilenceMs)

        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Creep", "Radiohead")
        assertNotNull(restored)
        assertEquals(238640L, restored?.durationMs)
        assertEquals(940L, restored?.leadingSilenceMs)
        assertEquals(238640L, restored?.effectiveDurationMs)
    }
}

