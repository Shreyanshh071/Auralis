package com.auralis.music

import com.auralis.music.data.parser.LrcParser
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LrcParserTest {

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
