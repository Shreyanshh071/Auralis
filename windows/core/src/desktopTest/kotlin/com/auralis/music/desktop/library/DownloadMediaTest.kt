package com.auralis.music.desktop.library

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadMediaTest {
    private val base = "https://playback.example.com/aac_160k/abc/playlist.m3u8?expires=1&Signature=x~y"

    @Test fun soundCloudStylePlaylistGivesInitThenEverySegmentInOrder() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:10
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="https://playback.example.com/aac_160k/abc/init.mp4?expires=1&Policy=a~b=&Signature=c"
            #EXTINF:10.0078,
            https://playback.example.com/aac_160k/abc/data000.m4s?expires=1
            #EXTINF:10.0078,
            data001.m4s?expires=1
            #EXTINF:3.2,
            /other/data002.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = assertIs<HlsPlaylist.Parsed.Media>(HlsPlaylist.parse(text, base))
        assertEquals("https://playback.example.com/aac_160k/abc/init.mp4?expires=1&Policy=a~b=&Signature=c", media.init?.url)
        assertEquals(listOf(
            "https://playback.example.com/aac_160k/abc/data000.m4s?expires=1",
            "https://playback.example.com/aac_160k/abc/data001.m4s?expires=1",
            "https://playback.example.com/other/data002.m4s",
        ), media.segments.map { it.url })
        assertTrue(media.segments.all { it.range == null })
    }

    @Test fun multiVariantPlaylistListsTheHighestBandwidthFirst() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="opus"
            low/playlist.m3u8
            #EXT-X-STREAM-INF:CODECS="mp4a.40.2",BANDWIDTH=160000
            high/playlist.m3u8
        """.trimIndent()
        val variants = assertIs<HlsPlaylist.Parsed.Variants>(HlsPlaylist.parse(text, base))
        assertEquals(listOf("https://playback.example.com/aac_160k/abc/high/playlist.m3u8",
            "https://playback.example.com/aac_160k/abc/low/playlist.m3u8"), variants.urls)
    }

    @Test fun byteRangesFollowOnFromThePreviousSegment() {
        val text = """
            #EXTM3U
            #EXT-X-MAP:URI="song.mp4",BYTERANGE="700@0"
            #EXTINF:10,
            #EXT-X-BYTERANGE:1000@700
            song.mp4
            #EXTINF:10,
            #EXT-X-BYTERANGE:500
            song.mp4
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = assertIs<HlsPlaylist.Parsed.Media>(HlsPlaylist.parse(text, base))
        assertEquals(0L..699L, media.init?.range)
        assertEquals(listOf(700L..1699L, 1700L..2199L), media.segments.map { it.range })
    }

    @Test fun refusesWhatCannotBecomeAWholeSong() {
        val encrypted = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:10,\na.ts\n#EXT-X-ENDLIST"
        assertFailsWith<IllegalArgumentException> { HlsPlaylist.parse(encrypted, base) }
        val live = "#EXTM3U\n#EXTINF:10,\na.m4s\n#EXTINF:10,\nb.m4s"
        assertFailsWith<IllegalArgumentException> { HlsPlaylist.parse(live, base) }
        assertFailsWith<IllegalArgumentException> { HlsPlaylist.parse("<html>Forbidden</html>", base) }
        assertFailsWith<IllegalArgumentException> { HlsPlaylist.parse("#EXTM3U\n#EXT-X-ENDLIST", base) }
        // METHOD=NONE is a plain playlist.
        val plain = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:10,\na.mp3\n#EXT-X-ENDLIST"
        assertIs<HlsPlaylist.Parsed.Media>(HlsPlaylist.parse(plain, base))
    }

    @Test fun recognizesPlaylistAddressesByPathOnly() {
        assertTrue(HlsPlaylist.isPlaylist(base))
        assertFalse(HlsPlaylist.isPlaylist("https://aac.saavncdn.com/409/abc_320.mp4"))
        assertFalse(HlsPlaylist.isPlaylist("https://cdn.example.com/a.mp3?next=b.m3u8"))
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0), ByteArray::plus)
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }
    private fun ints(vararg values: Int) = ByteBuffer.allocate(4 * values.size).apply { values.forEach { putInt(it) } }.array()
    private fun full(version: Int, flags: Int) = ints((version shl 24) or flags)

    /** An HLS init segment (all durations 0, as SoundCloud's are) followed by [fragments]. */
    private fun joinedHls(vararg fragments: ByteArray, movieDuration: Int = 0) =
        box("ftyp", "iso6".toByteArray(), ints(0)) + box("moov",
            box("mvhd", full(0, 0), ints(0, 0, 1000, movieDuration), ByteArray(80)),
            box("trak", box("tkhd", full(0, 3), ints(0, 0, 1, 0, 0), ByteArray(60)),
                box("mdia", box("mdhd", full(0, 0), ints(0, 0, 44100, 0), ByteArray(4)))),
            box("mvex", box("trex", full(0, 0), ints(1, 1, 0, 0, 0)))) + fragments.fold(ByteArray(0), ByteArray::plus)

    /** A moof with [samples] of the tfhd's default 1024 ticks, or of each of [durations]. */
    private fun fragment(samples: Int, durations: IntArray? = null): ByteArray {
        val tfhd = if (durations == null) box("tfhd", full(0, 0x020038), ints(1, 1024, 400, 0))
            else box("tfhd", full(0, 0x020000), ints(1))
        val trun = if (durations == null) box("trun", full(0, 0x000201), ints(samples, 0), ints(*IntArray(samples) { 400 }))
            else box("trun", full(0, 0x000301), ints(durations.size, 0), ints(*durations.flatMap { listOf(it, 400) }.toIntArray()))
        return box("styp", "msdh".toByteArray()) + box("moof", box("mfhd", full(0, 0), ints(1)),
            box("traf", tfhd, box("tfdt", full(1, 0), ByteArray(8)), trun)) + box("mdat", ByteArray(64))
    }

    private fun duration(file: ByteArray, type: String, at: Int): Int =
        ByteBuffer.wrap(file).getInt(String(file, Charsets.ISO_8859_1).indexOf(type) + 4 + at)

    @Test fun joinedFragmentsGetTheLengthTheirSamplesAddUpTo() {
        val file = File.createTempFile("joined", ".m4a").apply { deleteOnExit() }
        file.writeBytes(joinedHls(fragment(431), fragment(431), fragment(100)))
        val before = file.readBytes()
        val ticks = (431 + 431 + 100) * 1024
        assertEquals(ticks * 1000L / 44100, FragmentedMp4.fillInDuration(file))
        val after = file.readBytes()
        assertEquals(before.size, after.size, "nothing moves")
        assertEquals(ticks * 1000 / 44100, duration(after, "mvhd", 16))
        assertEquals(ticks * 1000 / 44100, duration(after, "tkhd", 20))
        assertEquals(ticks, duration(after, "mdhd", 16))
        val changed = before.indices.filter { before[it] != after[it] }
        assertTrue(changed.all { it < String(before, Charsets.ISO_8859_1).indexOf("mvex") }, "only the header's duration fields change")
        assertNull(FragmentedMp4.fillInDuration(file), "a length already written is left alone")
    }

    @Test fun perSampleDurationsAreSummed() {
        val file = File.createTempFile("joined", ".m4a").apply { deleteOnExit() }
        file.writeBytes(joinedHls(fragment(0, intArrayOf(1024, 1024, 512)), fragment(0, intArrayOf(2048))))
        assertEquals(4608L * 1000 / 44100, FragmentedMp4.fillInDuration(file))
        assertEquals(4608, duration(file.readBytes(), "mdhd", 16))
    }

    @Test fun filesWithALengthOrWithoutFragmentsAreUntouched() {
        val timed = File.createTempFile("timed", ".m4a").apply { deleteOnExit(); writeBytes(joinedHls(fragment(431), movieDuration = 10_008)) }
        val bytes = timed.readBytes()
        assertNull(FragmentedMp4.fillInDuration(timed))
        assertContentEquals(bytes, timed.readBytes())
        val plain = File.createTempFile("plain", ".m4a").apply { deleteOnExit(); writeBytes(joinedHls()) }
        assertNull(FragmentedMp4.fillInDuration(plain))
        val junk = File.createTempFile("junk", ".m4a").apply { deleteOnExit(); writeBytes(box("ftyp", "iso6".toByteArray()) + ByteArray(40) { 1 }) }
        assertNull(FragmentedMp4.fillInDuration(junk))
    }

    @Test fun containerComesFromTheBytesNotTheServer() {
        fun head(vararg bytes: Int) = ByteArray(16).also { bytes.forEachIndexed { i, b -> it[i] = b.toByte() } }
        assertEquals(AudioContainer.MP4, AudioContainer.of(byteArrayOf(0, 0, 0, 0x18) + "ftypdash".toByteArray() + ByteArray(4)))
        assertEquals("m4a", AudioContainer.of(byteArrayOf(0, 0, 0, 0x18) + "ftypiso6".toByteArray() + ByteArray(4))?.extension)
        assertEquals(AudioContainer.WEBM, AudioContainer.of(head(0x1A, 0x45, 0xDF, 0xA3)))
        assertEquals(AudioContainer.OGG, AudioContainer.of("OggS".toByteArray() + ByteArray(12)))
        assertEquals(AudioContainer.MP3, AudioContainer.of("ID3".toByteArray() + ByteArray(13)))
        assertEquals(AudioContainer.MP3, AudioContainer.of(head(0xFF, 0xFB, 0x90, 0x64)))
        assertEquals(AudioContainer.AAC, AudioContainer.of(head(0xFF, 0xF1, 0x50, 0x80)))
        assertNull(AudioContainer.of("<!DOCTYPE html><html>".toByteArray()))
        assertNull(AudioContainer.of(head(0x47, 0x40, 0x00, 0x10)), "MPEG-TS needs remuxing; it is not saved as a song")
        assertNull(AudioContainer.of(ByteArray(4)))
    }
}
