package com.auralis.music

import com.auralis.music.data.parser.LrcParser
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {

    @Test
    fun `same-timestamp translation lines fold into the lyric instead of becoming sung lines`() {
        val lrc = """
            [00:10.00]ab aao mere paas rah jaao mere saath
            [00:10.00]Now come close to me, stay with me
            [00:14.00]prem ni aa mosam che
            [00:14.00]This is the season of love
            [00:18.00]chogada tara
            [00:18.00]Your chogada
            [00:22.00]solo line with no translation
        """.trimIndent()
        val lines = com.auralis.music.data.parser.LrcParser.parse(lrc).lines
        assertEquals(4, lines.size)
        assertEquals("ab aao mere paas rah jaao mere saath", lines[0].text)
        assertEquals("Now come close to me, stay with me", lines[0].translatedText)
        assertEquals("This is the season of love", lines[1].translatedText)
        assertNull(lines[3].translatedText)
    }

    @Test
    fun `translation pairs a few ms off the sung line still fold instead of becoming duplicate sung lines`() {
        // Reproduces a real Bollywood LRC where the romanized line lands a handful of ms after
        // its Hindi counterpart instead of at the identical millisecond stamp.
        val lrc = """
            [00:10.00]बनाती है जो तू वो यादें जाने संग मेरे कब तक चलें
            [00:10.03]Banaati hai jo tu wo yaadein jaane sang mere kab tak chalein
            [00:14.00]उठता धुआ तोह
            [00:14.02]Uthata Dhua Toh
            [00:18.00]इश्क़ की धुनी रोज़ जलाए
            [00:18.05]Ishk Ki Dhuni Roj Jalae
            [00:22.00]solo line with no translation
        """.trimIndent()
        val lines = com.auralis.music.data.parser.LrcParser.parse(lrc).lines
        assertEquals(4, lines.size)
        assertEquals("Banaati hai jo tu wo yaadein jaane sang mere kab tak chalein", lines[0].translatedText)
        assertEquals("Uthata Dhua Toh", lines[1].translatedText)
        assertEquals("Ishk Ki Dhuni Roj Jalae", lines[2].translatedText)
        assertNull(lines[3].translatedText)
    }

    @Test
    fun `a single coincidental timestamp collision is not treated as a translation`() {
        val lrc = """
            [00:10.00]first line
            [00:10.00]second line same time
            [00:14.00]third line
            [00:18.00]fourth line
        """.trimIndent()
        val lines = com.auralis.music.data.parser.LrcParser.parse(lrc).lines
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.translatedText == null })
    }


    @Test
    fun `parse handles standard line-synced LRC files`() {
        val lrc = """
            [ti:Blinding Lights]
            [ar:The Weeknd]
            [00:10.50]Yeah
            [00:15.20]I've been on my own for long enough
            [00:20.00]Maybe you can show me how to love, maybe
        """.trimIndent()

        val data = LrcParser.parse(lrc)
        assertEquals(SyncType.LINE_SYNC, data.syncType)
        assertEquals(3, data.lines.size)
        assertEquals(10500L, data.lines[0].time)
        assertEquals("Yeah", data.lines[0].text)
        assertEquals(15200L, data.lines[1].time)
        assertEquals("I've been on my own for long enough", data.lines[1].text)
    }

    @Test
    fun `parse handles multi-timestamp LRC lines`() {
        val lrc = """
            [00:10.00][00:30.00]I'm drowning in the night
        """.trimIndent()

        val data = LrcParser.parse(lrc)
        assertEquals(2, data.lines.size)
        assertEquals(10000L, data.lines[0].time)
        assertEquals("I'm drowning in the night", data.lines[0].text)
        assertEquals(30000L, data.lines[1].time)
        assertEquals("I'm drowning in the night", data.lines[1].text)
    }

    @Test
    fun `parse handles enhanced RichSync word-by-word timestamps`() {
        val lrc = """
            [00:12.50]<00:12.50>I <00:13.00>said, <00:13.50>ooh, <00:14.00>I'm <00:14.50>blinded
        """.trimIndent()

        val data = LrcParser.parse(lrc)
        assertEquals(SyncType.RICHSYNC, data.syncType)
        assertEquals(1, data.lines.size)
        val line = data.lines[0]
        assertEquals(12500L, line.time)
        assertEquals("I said, ooh, I'm blinded", line.text)
        assertNotNull(line.words)
        assertEquals(5, line.words?.size)
        assertEquals(12500L, line.words?.get(0)?.time)
        assertEquals("I ", line.words?.get(0)?.word)
    }
}
