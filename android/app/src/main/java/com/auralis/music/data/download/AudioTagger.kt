package com.auralis.music.data.download

import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import com.auralis.music.domain.model.Track
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Locale

/** What goes inside an exported file. The private copy Auralis plays and reads is never given these. */
data class DownloadMetadataChoice(
    val coverArt: Boolean = true,
    val lyrics: Boolean = true
) {
    internal fun storedFlags(): Int = 1 or (if (coverArt) 2 else 0) or (if (lyrics) 4 else 0)

    internal fun tagsFor(track: Track, cover: ByteArray? = null, lyricsText: String? = null): AudioTags = AudioTags(
        title = track.title.takeIf { it.isNotBlank() },
        artist = track.artist.takeIf { it.isNotBlank() },
        album = track.album?.takeIf { it.isNotBlank() },
        cover = cover.takeIf { coverArt },
        lyrics = lyricsText.takeIf { lyrics }
    )

    companion object {
        /** Preserve optional choices from older saves while ignoring their song-details bit. */
        internal fun fromStoredFlags(flags: Int) = DownloadMetadataChoice(
            coverArt = flags and 2 != 0,
            lyrics = flags and 4 != 0
        )

        /** Only mandatory song details; neither optional attachment. */
        val BASIC = DownloadMetadataChoice(coverArt = false, lyrics = false)
    }
}

data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: ByteArray? = null,
    val lyrics: String? = null
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && cover == null && lyrics == null
}

/** What a stream really is: downloads are saved as .m4a whatever YouTube or JioSaavn served. */
enum class AudioContainer(val extension: String, val mimeType: String) {
    MP4("m4a", "audio/mp4"),

    /** WebM has no tags players read, so its Opus packets move to Ogg untouched. */
    WEBM_OPUS("ogg", "audio/ogg"),
    MP3("mp3", "audio/mpeg")
}

/** Timed LRC when the source has line times; word timing flattens to lines, which is all other players read. */
fun lrcText(lyrics: LyricsData): String? {
    val sung = lyrics.lines.filterNot { it.isBackground || it.isInstrumental || it.text.isBlank() }
    if (lyrics.syncType != SyncType.PLAIN && sung.isNotEmpty()) {
        return sung.joinToString("\n") { line ->
            val centis = line.time.coerceAtLeast(0L) / 10L
            "[%02d:%02d.%02d]%s".format(Locale.ROOT, centis / 6000L, centis / 100L % 60L, centis % 100L, line.text.trim())
        }
    }
    return lyrics.plainLyrics?.takeIf { it.isNotBlank() } ?: sung.joinToString("\n") { it.text.trim() }.ifBlank { null }
}

object AudioTagger {
    fun detect(file: File): AudioContainer? {
        val head = ByteArray(12)
        val read = FileInputStream(file).use { it.read(head) }
        if (read < 12) return null
        val first = head[0].toInt() and 0xFF
        val second = head[1].toInt() and 0xFF
        return when {
            String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> AudioContainer.MP4
            first == 0x1A && second == 0x45 && head[2].toInt() and 0xFF == 0xDF && head[3].toInt() and 0xFF == 0xA3 ->
                AudioContainer.WEBM_OPUS
            String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> AudioContainer.MP3
            // ADTS shares the sync bits but has no layer, and has nowhere to keep tags.
            first == 0xFF && second and 0xE0 == 0xE0 && second and 0x06 != 0 -> AudioContainer.MP3
            else -> null
        }
    }

    /** Writes a tagged copy of [source] to [target], or throws so the caller can export the plain file. */
    fun write(source: File, container: AudioContainer, tags: AudioTags, target: File) {
        when (container) {
            AudioContainer.MP4 -> Mp4Tagger.write(source, tags, target)
            AudioContainer.WEBM_OPUS -> OggOpusRemuxer.write(source, tags, target)
            AudioContainer.MP3 -> Id3Tagger.write(source, tags, target)
        }
    }

    internal fun isPng(image: ByteArray): Boolean =
        image.size > 4 && image[0].toInt() and 0xFF == 0x89 && image[1].toInt() == 0x50 && image[2].toInt() == 0x4E

    internal fun isJpeg(image: ByteArray): Boolean =
        image.size > 3 && image[0].toInt() and 0xFF == 0xFF && image[1].toInt() and 0xFF == 0xD8

    internal fun imageMime(image: ByteArray): String = if (isPng(image)) "image/png" else "image/jpeg"
}

private fun bigEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
private fun int32(value: Int): ByteArray = bigEndian(4).putInt(value).array()

/** iTunes-style tags in moov/udta/meta/ilst, for fragmented YouTube files and plain JioSaavn ones alike. */
private object Mp4Tagger {
    private const val MAX_INDEX_BYTES = 64L * 1024 * 1024
    private const val BASE_DATA_OFFSET_PRESENT = 0x000001

    private class Box(val type: String, val start: Long, val size: Long, val headerSize: Int) {
        val end: Long get() = start + size
    }

    fun write(source: File, tags: AudioTags, target: File) {
        RandomAccessFile(source, "r").use { input ->
            val boxes = topLevelBoxes(input)
            val moov = boxes.single { it.type == "moov" }
            check(moov.size <= MAX_INDEX_BYTES) { "moov is too large to rewrite" }
            boxes.filter { it.type == "moof" && it.start > moov.start }.forEach { rejectAbsoluteOffsets(input, it) }
            val rebuilt = rebuildMoov(read(input, moov), moov, tags)
            BufferedOutputStream(FileOutputStream(target), COPY_BUFFER).use { out ->
                boxes.forEach { box ->
                    when (box.type) {
                        "moov" -> out.write(rebuilt)
                        // An optional seek index of absolute positions, which moving the audio would falsify.
                        "mfra" -> Unit
                        else -> copyRange(input, box.start, box.size, out)
                    }
                }
            }
        }
    }

    private fun topLevelBoxes(input: RandomAccessFile): List<Box> {
        val boxes = mutableListOf<Box>()
        val length = input.length()
        var position = 0L
        while (position + 8 <= length) {
            input.seek(position)
            val small = input.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also(input::readFully).toString(Charsets.ISO_8859_1)
            val header = if (small == 1L) 16 else 8
            val size = when (small) {
                0L -> length - position
                1L -> input.readLong()
                else -> small
            }
            check(size >= header && position + size <= length) { "Damaged MP4 box $type" }
            boxes.add(Box(type, position, size, header))
            position += size
        }
        return boxes
    }

    private fun read(input: RandomAccessFile, box: Box): ByteArray {
        input.seek(box.start)
        return ByteArray(box.size.toInt()).also(input::readFully)
    }

    private fun children(bytes: ByteArray, start: Int, end: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var position = start
        while (position + 8 <= end) {
            val small = buffer.getInt(position).toLong() and 0xFFFFFFFFL
            val type = String(bytes, position + 4, 4, Charsets.ISO_8859_1)
            val header = if (small == 1L) 16 else 8
            val size = when (small) {
                0L -> (end - position).toLong()
                1L -> buffer.getLong(position + 8)
                else -> small
            }
            check(size >= header && position + size <= end) { "Damaged MP4 box $type" }
            boxes.add(Box(type, position.toLong(), size, header))
            position += size.toInt()
        }
        return boxes
    }

    /** A fragment naming its data by file position would break when the tags push it along. */
    private fun rejectAbsoluteOffsets(input: RandomAccessFile, moof: Box) {
        check(moof.size <= MAX_INDEX_BYTES) { "moof is too large to read" }
        val bytes = read(input, moof)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        children(bytes, moof.headerSize, bytes.size).filter { it.type == "traf" }.forEach { traf ->
            children(bytes, (traf.start + traf.headerSize).toInt(), traf.end.toInt())
                .filter { it.type == "tfhd" }
                .forEach { tfhd ->
                    val flags = buffer.getInt((tfhd.start + tfhd.headerSize).toInt())
                    check(flags and BASE_DATA_OFFSET_PRESENT == 0) { "Fragment uses absolute offsets" }
                }
        }
    }

    private fun rebuildMoov(moov: ByteArray, box: Box, tags: AudioTags): ByteArray {
        val parts = children(moov, box.headerSize, moov.size)
        val oldUdta = parts.firstOrNull { it.type == "udta" }
        val keptUdta = ByteArrayOutputStream()
        oldUdta?.let { udta ->
            children(moov, (udta.start + udta.headerSize).toInt(), udta.end.toInt())
                .filter { it.type != "meta" }
                .forEach { keptUdta.write(moov, it.start.toInt(), it.size.toInt()) }
        }
        val udta = box("udta", keptUdta.toByteArray(), meta(tags))
        val others = parts.filter { it.type != "udta" }
        val newSize = 8L + others.sumOf { it.size } + udta.size
        check(newSize <= Int.MAX_VALUE) { "moov is too large to rewrite" }

        shiftChunkOffsets(moov, others, box.end, newSize - box.size)
        val out = ByteArrayOutputStream(newSize.toInt())
        out.write(int32(newSize.toInt()))
        out.write("moov".toByteArray(Charsets.ISO_8859_1))
        others.forEach { out.write(moov, it.start.toInt(), it.size.toInt()) }
        out.write(udta)
        return out.toByteArray()
    }

    private fun shiftChunkOffsets(moov: ByteArray, boxes: List<Box>, moovEnd: Long, delta: Long) {
        if (delta == 0L) return
        val buffer = ByteBuffer.wrap(moov).order(ByteOrder.BIG_ENDIAN)
        boxes.forEach { box ->
            val payload = (box.start + box.headerSize).toInt()
            when (box.type) {
                "trak", "mdia", "minf", "stbl" ->
                    shiftChunkOffsets(moov, children(moov, payload, box.end.toInt()), moovEnd, delta)
                "stco" -> repeat(buffer.getInt(payload + 4)) { index ->
                    val position = payload + 8 + index * 4
                    val offset = buffer.getInt(position).toLong() and 0xFFFFFFFFL
                    if (offset >= moovEnd) {
                        val moved = offset + delta
                        check(moved <= 0xFFFFFFFFL) { "Chunk offset overflow" }
                        buffer.putInt(position, moved.toInt())
                    }
                }
                "co64" -> repeat(buffer.getInt(payload + 4)) { index ->
                    val position = payload + 8 + index * 8
                    val offset = buffer.getLong(position)
                    if (offset >= moovEnd) buffer.putLong(position, offset + delta)
                }
            }
        }
    }

    private fun meta(tags: AudioTags): ByteArray {
        val handler = box(
            "hdlr", int32(0), int32(0), "mdir".toByteArray(Charsets.ISO_8859_1),
            "appl".toByteArray(Charsets.ISO_8859_1), ByteArray(8), ByteArray(1)
        )
        val items = listOfNotNull(
            tags.title?.let { text("©nam", it) },
            tags.artist?.let { text("©ART", it) },
            tags.album?.let { text("©alb", it) },
            tags.cover?.let { box("covr", box("data", int32(if (AudioTagger.isPng(it)) 14 else 13), int32(0), it)) },
            tags.lyrics?.let { text("©lyr", it) }
        )
        return box("meta", int32(0), handler, box("ilst", *items.toTypedArray()))
    }

    private fun text(type: String, value: String): ByteArray =
        box(type, box("data", int32(1), int32(0), value.toByteArray(Charsets.UTF_8)))

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int32(8 + payload.sumOf { it.size }))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        payload.forEach(out::write)
        return out.toByteArray()
    }
}

/** Moves a WebM's Opus packets into Ogg byte for byte, then adds Vorbis comments. Nothing is re-encoded. */
private object OggOpusRemuxer {
    private const val EBML_HEADER = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val CODEC_ID = 0x86L
    private const val CODEC_PRIVATE = 0x63A2L
    private const val CLUSTER = 0x1F43B675L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val DISCARD_PADDING = 0x75A2L
    private const val LACING_MASK = 0x06
    private const val UNKNOWN_SIZE = -1L
    private const val NANOS_PER_SAMPLE = 1_000_000_000.0 / 48_000.0

    private class OpusTrack(val number: Long, val head: ByteArray)

    fun write(source: File, tags: AudioTags, target: File) {
        DataInputStream(BufferedInputStream(FileInputStream(source), COPY_BUFFER)).use { input ->
            check(readId(input) == EBML_HEADER) { "Not a WebM file" }
            skip(input, readSize(input))
            BufferedOutputStream(FileOutputStream(target), COPY_BUFFER).use { out ->
                var track: OpusTrack? = null
                var writer: OggOpusWriter? = null
                var pending: ByteArray? = null
                var trimSamples = 0L
                while (true) {
                    val id = try { readId(input) } catch (_: EOFException) { break }
                    val size = readSize(input)
                    when (id) {
                        // Their children are read in turn, which also covers live-style unknown sizes.
                        SEGMENT, TRACKS, CLUSTER, BLOCK_GROUP -> Unit
                        TRACK_ENTRY -> readTrack(ByteArray(element(size)).also(input::readFully))?.let { track = it }
                        SIMPLE_BLOCK, BLOCK -> {
                            val opus = checkNotNull(track) { "WebM has no Opus track" }
                            val packet = blockPacket(ByteArray(element(size)).also(input::readFully), opus.number)
                            if (packet != null) {
                                val current = writer ?: OggOpusWriter(out).also {
                                    it.writeHeaders(opus.head, tags)
                                    writer = it
                                }
                                pending?.let { current.add(it) }
                                pending = packet
                                trimSamples = 0L
                            }
                        }
                        DISCARD_PADDING -> {
                            val value = ByteArray(element(size)).also(input::readFully)
                            val nanos = value.fold(if (value.isNotEmpty() && value[0] < 0) -1L else 0L) { total, byte ->
                                (total shl 8) or (byte.toLong() and 0xFF)
                            }
                            trimSamples = (nanos / NANOS_PER_SAMPLE).toLong().coerceAtLeast(0L)
                        }
                        else -> skip(input, size)
                    }
                }
                val finished = checkNotNull(writer) { "WebM has no audio" }
                finished.finish(checkNotNull(pending), trimSamples)
            }
        }
    }

    private fun element(size: Long): Int {
        check(size in 0..(16L * 1024 * 1024)) { "Unexpected WebM element size" }
        return size.toInt()
    }

    private fun readId(input: DataInputStream): Long {
        val first = input.readUnsignedByte()
        check(first != 0) { "Damaged WebM element" }
        var id = first.toLong()
        repeat(Integer.numberOfLeadingZeros(first) - 24) { id = (id shl 8) or input.readUnsignedByte().toLong() }
        return id
    }

    private fun readSize(input: DataInputStream): Long {
        val first = input.readUnsignedByte()
        check(first != 0) { "Damaged WebM size" }
        val extra = Integer.numberOfLeadingZeros(first) - 24
        var value = (first and (0xFF shr (extra + 1))).toLong()
        var allOnes = value == (0xFF shr (extra + 1)).toLong()
        repeat(extra) {
            val next = input.readUnsignedByte()
            allOnes = allOnes && next == 0xFF
            value = (value shl 8) or next.toLong()
        }
        return if (allOnes) UNKNOWN_SIZE else value
    }

    private fun skip(input: DataInputStream, size: Long) {
        check(size != UNKNOWN_SIZE) { "Unknown size on a skipped WebM element" }
        var left = size
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) left -= skipped else {
                input.readByte()
                left--
            }
        }
    }

    /** The entry's Opus details, or null for any other kind of track. */
    private fun readTrack(entry: ByteArray): OpusTrack? {
        val input = DataInputStream(entry.inputStream())
        var number = 0L
        var codec = ""
        var head: ByteArray? = null
        while (input.available() > 0) {
            val id = readId(input)
            val size = readSize(input)
            when (id) {
                TRACK_NUMBER -> number = ByteArray(element(size)).also(input::readFully)
                    .fold(0L) { total, byte -> (total shl 8) or (byte.toLong() and 0xFF) }
                CODEC_ID -> codec = String(ByteArray(element(size)).also(input::readFully), Charsets.US_ASCII)
                CODEC_PRIVATE -> head = ByteArray(element(size)).also(input::readFully)
                else -> skip(input, size)
            }
        }
        if (codec != "A_OPUS") return null
        val opusHead = checkNotNull(head) { "Opus track has no header" }
        check(opusHead.size >= 19 && String(opusHead, 0, 8, Charsets.US_ASCII) == "OpusHead") { "Unexpected Opus header" }
        return OpusTrack(number, opusHead)
    }

    private fun blockPacket(block: ByteArray, wantedTrack: Long): ByteArray? {
        val input = DataInputStream(block.inputStream())
        val track = readSize(input)
        input.readShort()
        val flags = input.readUnsignedByte()
        if (track != wantedTrack) return null
        check(flags and LACING_MASK == 0) { "Laced WebM blocks aren't supported" }
        return ByteArray(input.available()).also(input::readFully)
    }
}

private class OggOpusWriter(private val out: OutputStream) {
    private val serial = 0x41555241 // "AURA"
    private var sequence = 0
    private var granule = 0L
    private var pageSamples = 0
    private val lacing = ByteArrayOutputStream()
    private val body = ByteArrayOutputStream()

    fun writeHeaders(opusHead: ByteArray, tags: AudioTags) {
        page(FLAG_FIRST, 0L, ByteArray(1) { opusHead.size.toByte() }, opusHead)
        writeTags(comments(tags))
    }

    /** One packet per stream, spread over as many pages as a cover needs, ending on a page of its own. */
    private fun writeTags(packet: ByteArray) {
        var offset = 0
        var continued = false
        while (true) {
            val left = packet.size - offset
            val take = minOf(left, MAX_PAGE_SEGMENTS * 255)
            val pageLacing = ByteArrayOutputStream()
            repeat(take / 255) { pageLacing.write(255) }
            val ends = pageLacing.size() < MAX_PAGE_SEGMENTS
            if (ends) pageLacing.write(take % 255)
            page(if (continued) FLAG_CONTINUED else 0, 0L, pageLacing.toByteArray(), packet.copyOfRange(offset, offset + take))
            offset += take
            if (ends) return
            continued = true
        }
    }

    fun add(packet: ByteArray) {
        append(packet)
        if (pageSamples >= SAMPLES_PER_PAGE) flush(0)
    }

    fun finish(lastPacket: ByteArray, trimSamples: Long) {
        append(lastPacket)
        granule = (granule - trimSamples).coerceAtLeast(0L)
        flush(FLAG_LAST)
    }

    private fun append(packet: ByteArray) {
        check(packet.isNotEmpty()) { "Empty Opus packet" }
        val segments = packet.size / 255 + 1
        check(segments <= MAX_PAGE_SEGMENTS) { "Opus packet is too large" }
        if (lacing.size() + segments > MAX_PAGE_SEGMENTS) flush(0)
        repeat(packet.size / 255) { lacing.write(255) }
        lacing.write(packet.size % 255)
        body.write(packet)
        val samples = opusPacketSamples(packet)
        granule += samples
        pageSamples += samples
    }

    private fun flush(flags: Int) {
        page(flags, granule, lacing.toByteArray(), body.toByteArray())
        lacing.reset()
        body.reset()
        pageSamples = 0
    }

    private fun page(flags: Int, position: Long, pageLacing: ByteArray, pageBody: ByteArray) {
        val header = ByteBuffer.allocate(27 + pageLacing.size + pageBody.size).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray(Charsets.US_ASCII))
        header.put(0)
        header.put(flags.toByte())
        header.putLong(position)
        header.putInt(serial)
        header.putInt(sequence++)
        header.putInt(0)
        header.put(pageLacing.size.toByte())
        header.put(pageLacing)
        header.put(pageBody)
        val bytes = header.array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, oggCrc(bytes))
        out.write(bytes)
    }

    private fun comments(tags: AudioTags): ByteArray {
        val fields = listOfNotNull(
            tags.title?.let { "TITLE=$it" },
            tags.artist?.let { "ARTIST=$it" },
            tags.album?.let { "ALBUM=$it" },
            tags.lyrics?.let { "LYRICS=$it" },
            tags.cover?.let { "METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(flacPicture(it)) }
        ).map { it.toByteArray(Charsets.UTF_8) }
        val vendor = "Auralis".toByteArray(Charsets.UTF_8)
        val size = 8 + 4 + vendor.size + 4 + fields.sumOf { 4 + it.size }
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusTags".toByteArray(Charsets.US_ASCII))
            putInt(vendor.size)
            put(vendor)
            putInt(fields.size)
            fields.forEach {
                putInt(it.size)
                put(it)
            }
        }.array()
    }

    private fun flacPicture(image: ByteArray): ByteArray {
        val mime = AudioTagger.imageMime(image).toByteArray(Charsets.US_ASCII)
        return bigEndian(32 + mime.size + image.size).apply {
            putInt(FRONT_COVER)
            putInt(mime.size)
            put(mime)
            putInt(0) // description
            putInt(0) // width, height, depth and palette are optional
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(image.size)
            put(image)
        }.array()
    }

    private companion object {
        const val FLAG_CONTINUED = 0x01
        const val FLAG_FIRST = 0x02
        const val FLAG_LAST = 0x04
        const val MAX_PAGE_SEGMENTS = 255
        const val SAMPLES_PER_PAGE = 48_000
        const val FRONT_COVER = 3
    }
}

/** Length of an Opus packet at 48kHz, from its table-of-contents byte (RFC 6716 section 3.1). */
internal fun opusPacketSamples(packet: ByteArray): Int {
    val toc = packet[0].toInt() and 0xFF
    val config = toc shr 3
    val frameSamples = when {
        config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
        config < 16 -> intArrayOf(480, 960)[config and 1]
        else -> intArrayOf(120, 240, 480, 960)[config and 3]
    }
    val frames = when (toc and 3) {
        0 -> 1
        3 -> packet[1].toInt() and 0x3F
        else -> 2
    }
    return frameSamples * frames
}

private val OGG_CRC_TABLE = IntArray(256) { index ->
    var value = index shl 24
    repeat(8) { value = if (value < 0) (value shl 1) xor 0x04C11DB7 else value shl 1 }
    value
}

internal fun oggCrc(bytes: ByteArray): Int {
    var crc = 0
    for (byte in bytes) crc = (crc shl 8) xor OGG_CRC_TABLE[((crc ushr 24) xor (byte.toInt() and 0xFF)) and 0xFF]
    return crc
}

/** ID3v2.3 in UTF-16: the version every Android player reads. */
private object Id3Tagger {
    private const val HEADER_SIZE = 10
    private const val UTF_16 = 1
    private const val FRONT_COVER = 3

    fun write(source: File, tags: AudioTags, target: File) {
        val frames = ByteArrayOutputStream()
        tags.title?.let { frames.write(frame("TIT2", byteArrayOf(UTF_16.toByte()) + utf16(it))) }
        tags.artist?.let { frames.write(frame("TPE1", byteArrayOf(UTF_16.toByte()) + utf16(it))) }
        tags.album?.let { frames.write(frame("TALB", byteArrayOf(UTF_16.toByte()) + utf16(it))) }
        tags.cover?.let { image ->
            val mime = AudioTagger.imageMime(image).toByteArray(Charsets.ISO_8859_1)
            frames.write(frame("APIC", byteArrayOf(0) + mime + byteArrayOf(0, FRONT_COVER.toByte(), 0) + image))
        }
        tags.lyrics?.let {
            val language = "und".toByteArray(Charsets.ISO_8859_1)
            frames.write(frame("USLT", byteArrayOf(UTF_16.toByte()) + language + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + utf16(it)))
        }
        val size = frames.size()
        check(size < 1 shl 28) { "Tags are too large for ID3" }
        RandomAccessFile(source, "r").use { input ->
            val audioStart = existingTagSize(input)
            BufferedOutputStream(FileOutputStream(target), COPY_BUFFER).use { out ->
                out.write("ID3".toByteArray(Charsets.ISO_8859_1))
                out.write(byteArrayOf(3, 0, 0))
                for (shift in intArrayOf(21, 14, 7, 0)) out.write((size shr shift) and 0x7F)
                frames.writeTo(out)
                copyRange(input, audioStart, input.length() - audioStart, out)
            }
        }
    }

    private fun utf16(text: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    private fun frame(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + int32(payload.size) + ByteArray(2) + payload

    private fun existingTagSize(input: RandomAccessFile): Long {
        val header = ByteArray(HEADER_SIZE)
        input.seek(0)
        input.readFully(header)
        if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return 0L
        val size = (6..9).fold(0L) { total, index -> (total shl 7) or (header[index].toLong() and 0x7F) }
        return HEADER_SIZE + size
    }
}

private const val COPY_BUFFER = 64 * 1024

private fun copyRange(input: RandomAccessFile, start: Long, size: Long, out: OutputStream) {
    val buffer = ByteArray(COPY_BUFFER)
    input.seek(start)
    var left = size
    while (left > 0) {
        val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
        if (read < 0) throw EOFException("Audio file ended early")
        out.write(buffer, 0, read)
        left -= read
    }
}
