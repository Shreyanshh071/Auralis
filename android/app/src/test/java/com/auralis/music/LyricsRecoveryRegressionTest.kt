package com.auralis.music

import com.auralis.music.data.parser.*
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.createFallbackPresentationTokens
import com.auralis.music.ui.lyrics.experimentalCharacterNudge
import com.auralis.music.ui.lyrics.experimentalTokenPulse
import com.auralis.music.ui.lyrics.experimentalWordProgress
import com.auralis.music.ui.lyrics.resolveExperimentalWordTimestamps
import org.junit.Assert.*
import org.junit.Test

class LyricsRecoveryRegressionTest {
    // Ordinary vocal text that the newly broadened metadata filter discarded.
    private val text = listOf("Credits", "Personnel", "Label: don't label me")

    @Test
    fun `line only LRC retains ambiguous vocal text and fallback timing`() {
        val data = LrcParser.parse(text.mapIndexed { i, value ->
            "[00:${10 + i * 5}.00]$value"
        }.joinToString("\n"))

        assertEquals(text, data.lines.map { it.text })
        assertEquals(SyncType.LINE_SYNC, data.syncType)
        assertFalse(LyricsValidator.isCorruptOrInvalid(data))
        data.lines.forEach { line ->
            assertNull(line.words)
            assertNull(resolveExperimentalWordTimestamps(line, data.syncType))
        }
    }

    @Test
    fun `starts only LRC remains usable with no guessed ends`() {
        val data = LrcParser.parse("""
            [00:10.00]<00:10.00>Label: <00:10.30>don't label me
            [00:15.00]<00:15.00>We <00:15.30>are here
            [00:20.00]<00:20.00>Stay <00:20.30>with me
        """.trimIndent())

        assertEquals(3, data.lines.size)
        assertEquals(SyncType.RICHSYNC, data.syncType)
        assertFalse(LyricsValidator.isCorruptOrInvalid(data))
        val words = resolveExperimentalWordTimestamps(data.lines.first(), data.syncType)!!
        assertEquals(2, words.size)
        assertTrue(words.all { it.endTime == null })
        assertEquals(0f, experimentalWordProgress(words[1], 10_299L), 0f)
        assertEquals(1f, experimentalWordProgress(words[1], 10_300L), 0f)
    }

    @Test
    fun `line only TTML and Better Lyrics retain source text without word timing`() {
        val ttml = "<tt><body><div>" + text.mapIndexed { i, value ->
            "<p begin=\"${10 + i * 5}s\" end=\"${12 + i * 5}s\">$value</p>"
        }.joinToString("") + "</div></body></tt>"
        val parsed = TtmlParser.parse(ttml)
        val better = BetterLyricsParser.parse(ttml)!!
        for (data in listOf(parsed, better)) {
            assertEquals(text, data.lines.map { it.text })
            assertEquals(SyncType.LINE_SYNC, data.syncType)
            assertTrue(data.lines.all { it.words.isNullOrEmpty() })
            assertFalse(LyricsValidator.isCorruptOrInvalid(data))
        }
    }

    @Test
    fun `QRC and richsync retain vocal lines previously removed as metadata`() {
        val qrc = text.mapIndexed { i, value ->
            "[00:${10 + i * 5}.00](${10_000 + i * 5_000},40)$value"
        }.joinToString("\n")
        val richsync = text.mapIndexed { i, value ->
            """{"ts":${10 + i * 5},"te":${11 + i * 5},"x":"$value","l":[{"c":"$value","o":0.0}]}"""
        }.joinToString(",", "[", "]")
        for (data in listOf(BetterLyricsParser.parse(qrc)!!, MusixmatchRichsyncParser.parse(richsync)!!)) {
            assertEquals(text, data.lines.map { it.text })
            assertFalse(LyricsValidator.isCorruptOrInvalid(data))
        }
    }

    @Test
    fun `YRC restores intro only credit filter in both dialects`() {
        val bracket = """
            [1000,400](1000,400,0)Producer: Intro credit
            [10000,400](10000,400,0)Producer: sung later
            [15000,400](15000,400,0)Credits
            [20000,400](20000,400,0)Personnel
        """.trimIndent()
        val json = """[
            {"t":1000,"d":400,"c":[{"t":0,"d":400,"tx":"Producer: Intro credit"}]},
            {"t":10000,"d":400,"c":[{"t":0,"d":400,"tx":"Producer: sung later"}]},
            {"t":15000,"d":400,"c":[{"t":0,"d":400,"tx":"Credits"}]},
            {"t":20000,"d":400,"c":[{"t":0,"d":400,"tx":"Personnel"}]}
        ]"""
        for (source in listOf(bracket, json)) {
            val data = YrcParser.parse(source)!!
            assertEquals(listOf("Producer: sung later", "Credits", "Personnel"), data.lines.map { it.text })
            assertEquals(listOf(10_000L, 15_000L, 20_000L), data.lines.map { it.time })
            assertFalse(LyricsValidator.isCorruptOrInvalid(data))
        }
    }

    @Test
    fun `cache retains line only starts only and measured word lyrics`() {
        for (type in listOf(SyncType.LINE_SYNC, SyncType.RICHSYNC)) {
            for (duration in listOf(null, 40L)) {
                val original = LyricsData(
                    syncType = type,
                    lines = text.mapIndexed { i, value ->
                        val start = 10_000L + i * 5_000L
                        val tokens = value.split(" ", limit = 2)
                        LyricLine(time = start, text = value, words = if (type == SyncType.LINE_SYNC) null else
                            tokens.mapIndexed { index, token ->
                                LyricWord(token + if (index < tokens.lastIndex) " " else "", start + index * 500L, duration)
                            })
                    },
                    provider = LyricsProvider.LRCLIB,
                    trackName = "Recovery fixture", artistName = "Fixture artist"
                )
                val entity = LyricsRepositoryImpl.domainToEntity("recovery", original, "Recovery fixture", "Fixture artist")
                val restored = LyricsRepositoryImpl.entityToDomain(entity, "Recovery fixture", "Fixture artist")!!
                assertEquals(text, restored.lines.map { it.text })
                assertEquals(type, restored.syncType)
                assertFalse(LyricsValidator.isCorruptOrInvalid(restored))
                if (type == SyncType.LINE_SYNC) assertTrue(restored.lines.all { it.words == null })
                else assertTrue(restored.lines.flatMap { it.words!! }.all { it.duration == duration })
            }
        }
    }

    @Test
    fun `existing LRC credit and separator cleanup remains intact`() {
        for (credit in listOf("Written by: Someone", "作词：Someone", "by: Someone", "♪", "---")) {
            assertTrue(credit, LrcParser.isMetadataOrCreditLine(credit))
        }
    }

    @Test
    fun `line only fallback presentation tokens and pulse match reference specification`() {
        val line = LyricLine(10_000L, "One whole highlighted line")
        assertNull(resolveExperimentalWordTimestamps(line, SyncType.LINE_SYNC))
        assertNull(line.words)

        // Reference presentation tokens: +30ms stagger, 180ms visual duration
        val tokens = createFallbackPresentationTokens(line.text, line.time)
        assertEquals(4, tokens.size)
        assertEquals("One", tokens[0].text)
        assertEquals(10.0, tokens[0].startTime, 0.0001)
        assertEquals(10.180, tokens[0].endTime!!, 0.0001)

        assertEquals("whole", tokens[1].text)
        assertEquals(10.030, tokens[1].startTime, 0.0001)
        assertEquals(10.210, tokens[1].endTime!!, 0.0001)

        assertEquals("highlighted", tokens[2].text)
        assertEquals(10.060, tokens[2].startTime, 0.0001)
        assertEquals(10.240, tokens[2].endTime!!, 0.0001)

        assertEquals("line", tokens[3].text)
        assertEquals(10.090, tokens[3].startTime, 0.0001)
        assertEquals(10.270, tokens[3].endTime!!, 0.0001)

        // Reference token scale pulse: 125ms rise, 625ms decay
        assertEquals(0f, experimentalTokenPulse(-1f), 0f)
        assertEquals(0f, experimentalTokenPulse(0f), 0f)
        assertEquals(0.5f, experimentalTokenPulse(62.5f), 0.001f)
        assertEquals(1f, experimentalTokenPulse(125f), 0f)
        assertEquals(0.5f, experimentalTokenPulse(125f + 312.5f), 0.001f)
        assertEquals(0f, experimentalTokenPulse(750f), 0f)
        assertEquals(0f, experimentalTokenPulse(800f), 0f)

        // Reference character nudge: N = 0.038 * sin(pi * c) * exp(-3 * c)
        assertEquals(0f, experimentalCharacterNudge(0f), 0.0001f)
        assertEquals(0f, experimentalCharacterNudge(1f), 0.0001f)
        assertTrue(experimentalCharacterNudge(0.25f) > 0f)
    }

    @Test
    fun `mixed richsync line fallback and plain text remain untimed`() {
        val line = LyricLine(10_000L, "A line without word timing")
        assertNull(resolveExperimentalWordTimestamps(line, SyncType.RICHSYNC))
        assertNull(resolveExperimentalWordTimestamps(line.copy(time = 0L), SyncType.PLAIN))
    }

    @Test
    fun `contributor lines are filtered and do not reject song wholesale`() {
        val lrc = """
            [00:01.00]Synced by JohnDoe
            [00:02.00]Transcribed by JaneDoe
            [00:03.00]Lyrics uploaded by Alex
            [00:10.00]First line of the song
            [00:15.00]Second line of the song
            [00:20.00]Third line of the song
        """.trimIndent()
        val data = LrcParser.parse(lrc)
        assertEquals(listOf("First line of the song", "Second line of the song", "Third line of the song"), data.lines.map { it.text })
        assertFalse(LyricsValidator.isCorruptOrInvalid(data))
    }

    @Test
    fun `song with contributor line already in cache passes validation and is preserved`() {
        val data = LyricsData(
            provider = LyricsProvider.LRCLIB,
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                LyricLine(time = 1000L, text = "Synced by Musixmatch"),
                LyricLine(time = 10000L, text = "First line of the song"),
                LyricLine(time = 15000L, text = "Second line of the song"),
                LyricLine(time = 20000L, text = "Third line of the song"),
                LyricLine(time = 25000L, text = "Fourth line of the song")
            ),
            trackName = "Test Song",
            artistName = "Test Artist"
        )
        // Must NOT be declared corrupt or purged
        assertFalse(LyricsValidator.isCorruptOrInvalid(data))

        val entity = LyricsRepositoryImpl.domainToEntity("test_key", data, "Test Song", "Test Artist")
        val restored = LyricsRepositoryImpl.entityToDomain(entity, "Test Song", "Test Artist")!!
        assertFalse(LyricsValidator.isCorruptOrInvalid(restored))
        assertEquals(
            listOf("First line of the song", "Second line of the song", "Third line of the song", "Fourth line of the song"),
            restored.lines.map { it.text }
        )
    }

    @Test
    fun `contributor only stub payload without lyrics is rejected`() {
        val stub = LyricsData(
            provider = LyricsProvider.LRCLIB,
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                LyricLine(time = 1000L, text = "Synced by JohnDoe")
            ),
            trackName = "Test Song",
            artistName = "Test Artist"
        )
        assertTrue(LyricsValidator.isCorruptOrInvalid(stub))
    }
}
