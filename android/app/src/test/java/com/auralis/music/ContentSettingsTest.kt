package com.auralis.music

import com.auralis.music.data.network.ContentProxy
import com.auralis.music.domain.lyrics.LyricsRomanizer
import com.auralis.music.domain.model.RomanizationScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentSettingsTest {

    // ── Proxy address ──

    @Test
    fun proxyHostPortParses() {
        val a = ContentProxy.parseHostPort("127.0.0.1:8080")!!
        assertEquals("127.0.0.1", a.hostString)
        assertEquals(8080, a.port)
        assertEquals("proxy.example.com", ContentProxy.parseHostPort("http://proxy.example.com:3128/")!!.hostString)
        assertEquals("::1", ContentProxy.parseHostPort("[::1]:1080")!!.hostString)
    }

    @Test
    fun proxyRejectsMissingOrBadPort() {
        assertNull(ContentProxy.parseHostPort("host:port"))
        assertNull(ContentProxy.parseHostPort("example.com"))
        assertNull(ContentProxy.parseHostPort("example.com:70000"))
        assertNull(ContentProxy.parseHostPort(":8080"))
    }

    // ── Romanization ──

    private val all = RomanizationScript.entries.toSet()

    @Test
    fun koreanRomanizes() {
        assertEquals("annyeong", LyricsRomanizer.romanize("안녕", all))
    }

    @Test
    fun japaneseKanaRomanizesAndKanjiOnlyLineIsSkipped() {
        assertEquals("arigatou", LyricsRomanizer.romanize("ありがとう", all))
        // Kanji have no reading without a dictionary: an all-kanji line yields no second line.
        assertNull(LyricsRomanizer.romanize("東京", setOf(RomanizationScript.JAPANESE)))
    }

    @Test
    fun russianRomanizes() {
        assertEquals("privet", LyricsRomanizer.romanize("привет", all)?.lowercase())
    }

    @Test
    fun unselectedScriptAndLatinLinesAreLeftAlone() {
        assertNull(LyricsRomanizer.romanize("안녕", setOf(RomanizationScript.HINDI)))
        assertNull(LyricsRomanizer.romanize("hello world", all))
    }
}
