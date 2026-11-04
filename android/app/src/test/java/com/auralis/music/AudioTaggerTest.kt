package com.auralis.music

import com.auralis.music.data.download.AudioContainer
import com.auralis.music.data.download.AudioTagger
import com.auralis.music.data.download.AudioTags
import com.auralis.music.data.download.DownloadMetadataChoice
import com.auralis.music.data.download.lrcText
import com.auralis.music.data.download.oggCrc
import com.auralis.music.data.download.opusPacketSamples
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTaggerTest {
    @get:Rule val folder = TemporaryFolder()

    private val tags = AudioTags(title = "Like Him", artist = "Tyler", album = "Chromakopia", lyrics = "[00:01.00]hello")
    private val audio = ByteArray(4096) { (it * 7).toByte() }

    @Test fun `unchecking both extras still embeds song details without cover or lyrics`() {
        val track = Track("mandatory", title = "Song title", artist = "Artist name", album = "Album name")
        val basic = DownloadMetadataChoice.BASIC.tagsFor(track,
            cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            lyricsText = "optional lyric")
        val result = tagged(file(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + audio), AudioContainer.MP3, basic)
        for ((frame, expected) in listOf("TIT2" to "Song title", "TPE1" to "Artist name", "TALB" to "Album name")) {
            val offset = result.indexOf(frame)
            assertTrue(offset > 0)
            val length = ByteBuffer.wrap(result).getInt(offset + 4)
            assertEquals(expected, String(result, offset + 11, length - 1, Charsets.UTF_16))
        }
        assertEquals(-1, result.indexOf("APIC"))
        assertEquals(-1, result.indexOf("USLT"))
        assertArrayEquals(audio, result.takeLast(audio.size).toByteArray())
    }

    @Test fun `old remembered flags keep optional choices but cannot disable mandatory details`() {
        val track = Track("flags", title = "Title", artist = "Artist", album = null)
        for (flags in 0..7) {
            val choice = DownloadMetadataChoice.fromStoredFlags(flags)
            assertEquals(flags and 2 != 0, choice.coverArt)
            assertEquals(flags and 4 != 0, choice.lyrics)
            assertEquals(flags or 1, choice.storedFlags())
            val actual = choice.tagsFor(track)
            assertEquals("Title", actual.title)
            assertEquals("Artist", actual.artist)
            assertNull(actual.album)
        }
    }

    private fun int32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int32(8 + payload.sumOf { it.size }))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        payload.forEach(out::write)
        return out.toByteArray()
    }

    private fun file(bytes: ByteArray) = folder.newFile().apply { writeBytes(bytes) }
    private fun tagged(source: File, container: AudioContainer, with: AudioTags = tags): ByteArray {
        val target = folder.newFile()
        AudioTagger.write(source, container, with, target)
        return target.readBytes()
    }

    private fun ByteArray.indexOf(text: String, from: Int = 0): Int {
        val wanted = text.toByteArray(Charsets.ISO_8859_1)
        return (from..size - wanted.size).firstOrNull { start -> wanted.indices.all { this[start + it] == wanted[it] } } ?: -1
    }

    private fun stco(vararg offsets: Int) = box(
        "stco", int32(0), int32(offsets.size), *offsets.map(::int32).toTypedArray()
    )

    private fun trak(chunks: ByteArray) = box("trak", box("mdia", box("minf", box("stbl", chunks))))

    @Test fun `every container is recognised by its bytes, not its name`() {
        assertEquals(AudioContainer.MP4, AudioTagger.detect(file(box("ftyp", ByteArray(12)))))
        assertEquals(AudioContainer.WEBM_OPUS, AudioTagger.detect(file(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(12))))
        assertEquals(AudioContainer.MP3, AudioTagger.detect(file(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + ByteArray(12))))
        assertEquals(AudioContainer.MP3, AudioTagger.detect(file("ID3".toByteArray() + ByteArray(12))))
    }

    @Test fun `raw AAC has nowhere to keep tags`() {
        assertNull(AudioTagger.detect(file(byteArrayOf(0xFF.toByte(), 0xF1.toByte()) + ByteArray(12))))
    }

    @Test fun `tags ahead of the audio move its chunk offsets with it`() {
        val ftyp = box("ftyp", ByteArray(8))
        val mdat = box("mdat", audio)
        val moovSize = box("moov", trak(stco(0))).size
        val firstChunk = ftyp.size + moovSize + 8
        val source = ftyp + box("moov", trak(stco(firstChunk))) + mdat

        val result = tagged(file(source), AudioContainer.MP4)

        val moved = ByteBuffer.wrap(result).getInt(result.indexOf("stco") + 12)
        assertEquals(result.indexOf("mdat") + 4, moved)
        assertArrayEquals(audio, result.copyOfRange(moved, moved + audio.size))
    }

    @Test fun `offsets before the index stay put`() {
        val ftyp = box("ftyp", ByteArray(8))
        val mdat = box("mdat", audio)
        val firstChunk = ftyp.size + 8
        val source = ftyp + mdat + box("moov", trak(stco(firstChunk)))

        val result = tagged(file(source), AudioContainer.MP4)

        assertEquals(firstChunk, ByteBuffer.wrap(result).getInt(result.indexOf("stco") + 12))
    }

    @Test fun `tags already in the file are replaced, other user data stays`() {
        val oldMeta = box("meta", int32(0), box("ilst", box("©nam", "Old".toByteArray())))
        val source = box("ftyp", ByteArray(8)) +
            box("moov", box("udta", box("name", "kept".toByteArray()), oldMeta)) + box("mdat", audio)

        val result = tagged(file(source), AudioContainer.MP4)

        assertEquals(-1, result.indexOf("Old"))
        assertTrue(result.indexOf("kept") > 0)
        assertTrue(result.indexOf("Like Him") > 0)
    }

    @Test fun `only chosen tags are written`() {
        val source = box("ftyp", ByteArray(8)) + box("moov", ByteArray(0)) + box("mdat", audio)

        val result = tagged(file(source), AudioContainer.MP4, AudioTags(album = "Chromakopia"))

        assertTrue(result.indexOf("Chromakopia") > 0)
        assertEquals(-1, result.indexOf("©nam"))
        assertEquals(-1, result.indexOf("covr"))
    }

    @Test fun `a fragmented file keeps its fragments and drops the absolute seek index`() {
        val moof = box("moof", box("traf", box("tfhd", int32(0x020000), int32(1))))
        val mdat = box("mdat", audio)
        val source = box("ftyp", ByteArray(8)) + box("moov", box("mvex", ByteArray(4))) + moof + mdat + box("mfra", ByteArray(16))

        val result = tagged(file(source), AudioContainer.MP4)

        assertEquals(-1, result.indexOf("mfra"))
        val start = result.indexOf("moof") - 4
        assertArrayEquals(moof + mdat, result.copyOfRange(start, result.size))
    }

    @Test fun `a fragment with absolute offsets is refused rather than broken`() {
        val moof = box("moof", box("traf", box("tfhd", int32(0x000001), int32(1), ByteArray(8))))
        val source = box("ftyp", ByteArray(8)) + box("moov", ByteArray(0)) + moof + box("mdat", audio)

        assertThrows(IllegalStateException::class.java) { tagged(file(source), AudioContainer.MP4) }
    }

    /** One EBML element with a one-to-four byte id and an eight byte size. */
    private fun ebml(id: Long, payload: ByteArray): ByteArray {
        val idBytes = ByteBuffer.allocate(8).putLong(id).array().dropWhile { it == 0.toByte() }.toByteArray()
        return idBytes + byteArrayOf(0x01) + ByteBuffer.allocate(8).putLong(payload.size.toLong()).array().drop(1).toByteArray() + payload
    }

    private val opusHead = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
    /** Config 31 is 20ms CELT, so each packet is 960 samples. */
    private val packets = List(120) { index -> byteArrayOf((31 shl 3).toByte(), index.toByte(), 7, 7) }

    private fun simpleBlock(packet: ByteArray) = ebml(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + packet)

    private fun webm(lastBlock: ByteArray = simpleBlock(packets.last())): ByteArray {
        val entry = ebml(0xAE, ebml(0xD7, byteArrayOf(1)) + ebml(0x86, "A_OPUS".toByteArray()) + ebml(0x63A2, opusHead))
        val blocks = packets.dropLast(1).fold(ByteArray(0)) { all, packet -> all + simpleBlock(packet) } + lastBlock
        val segment = ebml(0x18538067, ebml(0x1654AE6B, entry) + ebml(0x1F43B675, ebml(0xE7, byteArrayOf(0)) + blocks))
        return ebml(0x1A45DFA3, ByteArray(4)) + segment
    }

    private class OggPage(val flags: Int, val granule: Long, val lacing: ByteArray, val body: ByteArray)

    private fun oggPages(bytes: ByteArray): List<OggPage> {
        val pages = mutableListOf<OggPage>()
        var position = 0
        while (position < bytes.size) {
            assertEquals("OggS", String(bytes, position, 4))
            val count = bytes[position + 26].toInt() and 0xFF
            val lacing = bytes.copyOfRange(position + 27, position + 27 + count)
            val size = lacing.sumOf { it.toInt() and 0xFF }
            val whole = bytes.copyOfRange(position, position + 27 + count + size)
            val stored = ByteBuffer.wrap(whole).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
            ByteBuffer.wrap(whole).order(ByteOrder.LITTLE_ENDIAN).putInt(22, 0)
            assertEquals(stored, oggCrc(whole))
            pages.add(
                OggPage(
                    bytes[position + 5].toInt(),
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(position + 6),
                    lacing,
                    whole.copyOfRange(27 + count, whole.size)
                )
            )
            position += whole.size
        }
        return pages
    }

    @Test fun `Opus moves out of WebM packet for packet, with valid pages`() {
        val pages = oggPages(tagged(file(webm()), AudioContainer.WEBM_OPUS))

        assertEquals(2, pages.first().flags)
        assertArrayEquals(opusHead, pages.first().body)
        assertEquals("OpusTags", String(pages[1].body, 0, 8))
        assertTrue(String(pages[1].body, Charsets.UTF_8).contains("TITLE=Like Him"))
        assertEquals(4, pages.last().flags)
        assertEquals(120L * 960L, pages.last().granule)
        val audioPages = pages.drop(2)
        assertArrayEquals(
            packets.fold(ByteArray(0)) { all, packet -> all + packet },
            audioPages.fold(ByteArray(0)) { all, page -> all + page.body }
        )
    }

    @Test fun `a final block's padding trims the end of the stream`() {
        val last = ebml(0xA0, ebml(0xA1, byteArrayOf(0x81.toByte(), 0, 0, 0) + packets.last()) + ebml(0x75A2, ByteBuffer.allocate(4).putInt(10_000_000).array()))

        val pages = oggPages(tagged(file(webm(last)), AudioContainer.WEBM_OPUS))

        assertEquals(120L * 960L - 480L, pages.last().granule)
    }

    @Test fun `a cover too big for one page continues on the next`() {
        val cover = ByteArray(200_000) { (it % 251).toByte() }

        val pages = oggPages(tagged(file(webm()), AudioContainer.WEBM_OPUS, tags.copy(cover = cover)))

        assertEquals(1, pages[2].flags and 1)
        val firstAudio = pages.indexOfFirst { String(it.body, 0, minOf(it.body.size, 8), Charsets.ISO_8859_1).let { head -> it.granule > 0 && head != "OpusTags" } }
        assertTrue(pages.take(firstAudio).drop(1).all { it.granule == 0L })
    }

    @Test fun `Vorbis audio in WebM is refused`() {
        val entry = ebml(0xAE, ebml(0xD7, byteArrayOf(1)) + ebml(0x86, "A_VORBIS".toByteArray()))
        val blocks = ebml(0x1F43B675, simpleBlock(packets.first()))
        val source = ebml(0x1A45DFA3, ByteArray(4)) + ebml(0x18538067, ebml(0x1654AE6B, entry) + blocks)

        assertThrows(IllegalStateException::class.java) { tagged(file(source), AudioContainer.WEBM_OPUS) }
    }

    @Test fun `Opus packet lengths follow its table of contents`() {
        assertEquals(960, opusPacketSamples(byteArrayOf((31 shl 3).toByte())))
        assertEquals(2880, opusPacketSamples(byteArrayOf((3 shl 3).toByte())))
        assertEquals(1920, opusPacketSamples(byteArrayOf(((31 shl 3) or 1).toByte())))
        assertEquals(2880, opusPacketSamples(byteArrayOf(((31 shl 3) or 3).toByte(), 3)))
    }

    @Test fun `MP3 tags replace an old tag and leave the audio alone`() {
        val oldTag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4)
        val frames = byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + audio

        val result = tagged(file(oldTag + frames), AudioContainer.MP3)

        assertEquals("ID3", String(result, 0, 3))
        assertTrue(result.indexOf("TIT2") > 0)
        val size = (6..9).fold(0) { total, index -> (total shl 7) or result[index].toInt() }
        assertArrayEquals(frames, result.copyOfRange(10 + size, result.size))
    }

    @Test fun `synced lyrics become timed lines, without background vocals`() {
        val lyrics = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                LyricLine(1_230, "first"),
                LyricLine(2_000, "", isInstrumental = true),
                LyricLine(2_500, "ooh", isBackground = true),
                LyricLine(65_040, "second")
            ),
            provider = LyricsProvider.QQMUSIC
        )
        assertEquals("[00:01.23]first\n[01:05.04]second", lrcText(lyrics))
    }

    @Test fun `plain lyrics stay plain`() {
        val lyrics = LyricsData(SyncType.PLAIN, emptyList(), plainLyrics = "just words", provider = LyricsProvider.QQMUSIC)
        assertEquals("just words", lrcText(lyrics))
    }

    @Test fun `nothing to embed reads as empty`() {
        assertTrue(AudioTags().isEmpty)
        assertFalse(AudioTags(lyrics = "x").isEmpty)
        assertNull(lrcText(LyricsData(SyncType.PLAIN, emptyList(), provider = LyricsProvider.QQMUSIC)))
    }
}
