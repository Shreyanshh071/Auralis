package com.auralis.music

import com.auralis.music.data.network.provider.YouLyPlusLyricsSource
import com.auralis.music.data.network.provider.YouTubeCaptionsLyricsSource
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewLyricsSourcesTest {

    @Test
    fun `youly plus word lines keep provider timing and split background vocals into their own line`() {
        val body = """
            {"type":"Word","metadata":{"title":"Blinding Lights"},"lyrics":[
              {"time":1000,"duration":2000,"text":"I said ooh (back to)","element":{"singer":"v1"},
               "syllabus":[
                 {"time":1000,"duration":300,"text":"I "},
                 {"time":1300,"duration":400,"text":"said "},
                 {"time":1700,"duration":500,"text":"ooh"},
                 {"time":2400,"duration":200,"text":"(back ","isBackground":true},
                 {"time":2600,"duration":300,"text":"to)","isBackground":true}
               ]}
            ]}
        """.trimIndent()
        val data = YouLyPlusLyricsSource.parse(body)
        assertNotNull(data)
        data!!
        assertEquals(SyncType.RICHSYNC, data.syncType)
        assertEquals("Blinding Lights", data.trackName)
        assertEquals(2, data.lines.size)

        val lead = data.lines[0]
        assertFalse(lead.isBackground)
        assertEquals("I said ooh", lead.text)
        assertEquals("v1", lead.agent)
        assertEquals(listOf(1000L, 1300L, 1700L), lead.words!!.map { it.time })
        assertEquals(listOf(300L, 400L, 500L), lead.words!!.map { it.duration })
        assertEquals(3000L, lead.endTime)

        val bg = data.lines[1]
        assertTrue(bg.isBackground)
        assertEquals("(back to)", bg.text)
        assertEquals(2400L, bg.time)
        assertEquals(2900L, bg.endTime)
    }

    @Test
    fun `youly plus line-only songs come back line synced without inventing words`() {
        val body = """
            {"type":"Line","metadata":{"title":"Tum Hi Ho"},"lyrics":[
              {"time":5000,"duration":3000,"text":"Hum tere bin ab reh nahi sakte","syllabus":[],"element":{"singer":"v1"}},
              {"time":9000,"duration":2500,"text":"Tere bina kya wajood mera","syllabus":[],"element":{"singer":"v1"}}
            ]}
        """.trimIndent()
        val data = YouLyPlusLyricsSource.parse(body)!!
        assertEquals(SyncType.LINE_SYNC, data.syncType)
        assertEquals(listOf(5000L, 9000L), data.lines.map { it.time })
        assertTrue(data.lines.all { it.words == null })
    }

    @Test
    fun `youly plus mirror order starts with the configured list`() {
        assertEquals(YouLyPlusLyricsSource.MIRRORS.toSet(), YouLyPlusLyricsSource.mirrorOrder().toSet())
        assertEquals(YouLyPlusLyricsSource.MIRRORS.size, YouLyPlusLyricsSource.mirrorOrder().size)
    }

    @Test
    fun `caption cues parse into timed lines and drop direction marks and music notes`() {
        val body = """
            {"events":[
              {"tStartMs":0,"dDurationMs":900},
              {"tStartMs":9600,"dDurationMs":6440,"segs":[{"utf8":"‎I've been tryna call"}]},
              {"tStartMs":16000,"dDurationMs":4000,"segs":[{"utf8":"♪ I've been on my own\nfor long enough ♪"}]},
              {"tStartMs":21000,"dDurationMs":1000,"segs":[{"utf8":"♪"}]}
            ]}
        """.trimIndent()
        val lines = YouTubeCaptionsLyricsSource.parseJson3(body)
        assertEquals(2, lines.size)
        assertEquals("I've been tryna call", lines[0].text)
        assertEquals(9600L, lines[0].time)
        assertEquals(16040L, lines[0].endTime)
        assertEquals("I've been on my own for long enough", lines[1].text)
    }

    @Test
    fun `a translated caption track does not match the song's own lyrics`() {
        val lyrics = YouTubeCaptionsLyricsSource.tokens(
            "Tujhko main rakh loon wahan jahan pe kahin hai mera yaqeen main jo tera na hua kisi ka nahi"
        )
        val matching = YouTubeCaptionsLyricsSource.tokens(
            "Tujhko main rakh loon wahan, jahan pe kahin hai mera yaqeen. Main jo tera na hua"
        )
        val arabic = YouTubeCaptionsLyricsSource.tokens("أيمكن لأحدكم أن يخبرني كيف لا يقع الشخص في حبك")
        val english = YouTubeCaptionsLyricsSource.tokens("I would keep you there, where my faith lies somewhere")

        val (goodPrecision, goodCoverage) = YouTubeCaptionsLyricsSource.overlap(matching, lyrics)!!
        assertTrue(goodPrecision >= YouTubeCaptionsLyricsSource.MIN_PRECISION)
        assertTrue(goodCoverage >= YouTubeCaptionsLyricsSource.MIN_COVERAGE)

        assertTrue(YouTubeCaptionsLyricsSource.overlap(arabic, lyrics)!!.first < YouTubeCaptionsLyricsSource.MIN_PRECISION)
        assertTrue(YouTubeCaptionsLyricsSource.overlap(english, lyrics)!!.first < YouTubeCaptionsLyricsSource.MIN_PRECISION)
        assertNull(YouTubeCaptionsLyricsSource.overlap(emptySet(), lyrics))
    }
}
