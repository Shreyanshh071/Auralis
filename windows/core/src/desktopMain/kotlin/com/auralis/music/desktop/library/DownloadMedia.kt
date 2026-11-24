package com.auralis.music.desktop.library

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** What a downloaded file really is, read from its first bytes; it names the file's extension. */
internal enum class AudioContainer(val extension: String) {
    MP4("m4a"), WEBM("webm"), OGG("ogg"), MP3("mp3"), AAC("aac");

    companion object {
        /** The container [head] starts, or null for anything that isn't audio we can save (an error page, MPEG-TS). */
        fun of(head: ByteArray): AudioContainer? {
            if (head.size < 8) return null
            val b = head.map { it.toInt() and 0xFF }
            return when {
                String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> MP4
                b[0] == 0x1A && b[1] == 0x45 && b[2] == 0xDF && b[3] == 0xA3 -> WEBM
                String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" -> OGG
                String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> MP3
                // MPEG audio and ADTS share the 11 sync bits; ADTS has no layer.
                b[0] == 0xFF && b[1] and 0xE0 == 0xE0 && b[1] and 0x06 != 0 -> MP3
                b[0] == 0xFF && b[1] and 0xF6 == 0xF0 -> AAC
                else -> null
            }
        }
    }
}

/**
 * The parts of an HLS playlist a download needs. SoundCloud serves many uploads only this way: a
 * fragmented-MP4 init segment and AAC fragments, which joined in order are one playable .m4a.
 */
internal object HlsPlaylist {
    /** One file (or byte range of one) to append. */
    data class Part(val url: String, val range: LongRange? = null)

    sealed interface Parsed {
        /** A multi-variant playlist: the variant URLs, best first. */
        data class Variants(val urls: List<String>) : Parsed
        /** A whole recording: the init segment (fragmented MP4) and then every media segment. */
        data class Media(val init: Part?, val segments: List<Part>) : Parsed
    }

    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun isPlaylist(url: String): Boolean =
        runCatching { java.net.URI(url).path.orEmpty().lowercase().endsWith(".m3u8") }.getOrDefault(false)

    /**
     * Reads [text], fetched from [base]. Throws for what can't become a whole song: no playlist
     * header, encrypted media, a live (still growing) playlist, or no segments.
     */
    fun parse(text: String, base: String): Parsed {
        val baseUrl = requireNotNull(base.toHttpUrlOrNull()) { "Invalid playlist address" }
        fun resolve(reference: String) = requireNotNull(baseUrl.resolve(reference.trim())) { "Invalid segment address" }.toString()
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.firstOrNull()?.removePrefix("﻿") == "#EXTM3U") { "Not an HLS playlist" }

        val variants = mutableListOf<Pair<Long, String>>()
        var pendingBandwidth: Long? = null
        var init: Part? = null
        val segments = mutableListOf<Part>()
        var pendingRange: LongRange? = null
        var nextOffset = 0L
        var ended = false
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") ->
                    pendingBandwidth = attributes(line)["BANDWIDTH"]?.toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attributes(line)["METHOD"]
                    require(method == null || method.equals("NONE", ignoreCase = true)) { "Encrypted HLS ($method) can't be saved" }
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val attributes = attributes(line)
                    val uri = requireNotNull(attributes["URI"]) { "HLS init segment without an address" }
                    init = Part(resolve(uri), attributes["BYTERANGE"]?.let { byteRange(it, 0L) })
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> pendingRange = byteRange(line.substringAfter(':'), nextOffset)
                line == "#EXT-X-ENDLIST" -> ended = true
                line.startsWith("#") -> Unit
                pendingBandwidth != null -> {
                    variants += pendingBandwidth!! to resolve(line)
                    pendingBandwidth = null
                }
                else -> {
                    val range = pendingRange
                    segments += Part(resolve(line), range)
                    nextOffset = range?.let { it.last + 1 } ?: 0L
                    pendingRange = null
                }
            }
        }
        if (variants.isNotEmpty()) return Parsed.Variants(variants.sortedByDescending { it.first }.map { it.second })
        require(segments.isNotEmpty()) { "HLS playlist has no segments" }
        require(ended) { "HLS playlist is still growing (live)" }
        return Parsed.Media(init, segments)
    }

    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

    /** "length[@offset]"; without an offset the range follows the previous one. */
    private fun byteRange(value: String, defaultOffset: Long): LongRange {
        val length = requireNotNull(value.substringBefore('@').trim().toLongOrNull()) { "Invalid HLS byte range" }
        val offset = if ('@' in value) requireNotNull(value.substringAfter('@').trim().toLongOrNull()) { "Invalid HLS byte range" } else defaultOffset
        require(length > 0 && offset >= 0) { "Invalid HLS byte range" }
        return offset until offset + length
    }
}

/**
 * Joined HLS fragments keep the init segment's header, which gives the song no length (0): libVLC
 * then shows no duration and can't seek in the saved file. This writes the length the fragments
 * add up to into that header's duration fields, in place, so no box moves and every offset holds.
 */
internal object FragmentedMp4 {
    private class Box(val type: String, val start: Long, val size: Long) {
        val body: Long get() = start + 8
        val end: Long get() = start + size
    }

    /** A duration field: where it is, how wide (4 or 8 bytes), and its timescale. */
    private class Field(val offset: Long, val wide: Boolean, val timescale: Long)

    /**
     * Fills in a fragmented MP4's zero durations from its fragments' samples. Returns the length
     * in ms when it did, or null when the file isn't a single-track fragmented MP4 with zero
     * durations (it is then left exactly as it was).
     */
    fun fillInDuration(file: File): Long? = RandomAccessFile(file, "rw").use { input ->
        val top = children(input, 0L, input.length())
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val moofs = top.filter { it.type == "moof" }
        if (moofs.isEmpty() || moofs.first().start < moov.end) return null
        val moovChildren = children(input, moov.body, moov.end)
        val mvhd = moovChildren.firstOrNull { it.type == "mvhd" } ?: return null
        val trak = moovChildren.filter { it.type == "trak" }.singleOrNull() ?: return null
        val tkhd = children(input, trak.body, trak.end).firstOrNull { it.type == "tkhd" } ?: return null
        val mdia = children(input, trak.body, trak.end).firstOrNull { it.type == "mdia" } ?: return null
        val mdhd = children(input, mdia.body, mdia.end).firstOrNull { it.type == "mdhd" } ?: return null
        val mvex = moovChildren.firstOrNull { it.type == "mvex" } ?: return null
        val mvexChildren = children(input, mvex.body, mvex.end)

        val movie = timed(input, mvhd) ?: return null
        val media = timed(input, mdhd) ?: return null
        val trackId = int(input, tkhd.body + if (byte(input, tkhd.body) == 1) 20 else 12)
        val track = Field(tkhd.body + if (byte(input, tkhd.body) == 1) 28 else 20, byte(input, tkhd.body) == 1, movie.timescale)
        val mehd = mvexChildren.firstOrNull { it.type == "mehd" }?.let { Field(it.body + 4, byte(input, it.body) == 1, movie.timescale) }
        val trexDuration = mvexChildren.filter { it.type == "trex" }
            .firstOrNull { int(input, it.body + 4) == trackId }?.let { int(input, it.body + 12) } ?: 0L
        val fields = listOfNotNull(movie, track, media, mehd)
        if (fields.any { read(input, it) != 0L }) return null

        var total = 0L
        for (moof in moofs) {
            for (traf in children(input, moof.body, moof.end).filter { it.type == "traf" }) {
                total += fragmentDuration(input, traf, trackId, trexDuration) ?: return null
            }
        }
        if (total <= 0 || media.timescale <= 0 || movie.timescale <= 0) return null
        for (field in fields) {
            val value = if (field.timescale == media.timescale) total else total * field.timescale / media.timescale
            if (!field.wide && value > 0xFFFF_FFFFL) return null
        }
        for (field in fields) write(input, field, if (field.timescale == media.timescale) total else total * field.timescale / media.timescale)
        total * 1000 / media.timescale
    }

    /** The sample durations of [traf] summed, 0 for another track, or null when they can't be known. */
    private fun fragmentDuration(input: RandomAccessFile, traf: Box, trackId: Long, trexDuration: Long): Long? {
        val parts = children(input, traf.body, traf.end)
        val tfhd = parts.firstOrNull { it.type == "tfhd" } ?: return null
        val tfhdFlags = int(input, tfhd.body) and 0xFFFFFF
        if (int(input, tfhd.body + 4) != trackId) return 0L
        var at = tfhd.body + 8
        if (tfhdFlags and 0x01 != 0L) at += 8
        if (tfhdFlags and 0x02 != 0L) at += 4
        val defaultDuration = if (tfhdFlags and 0x08 != 0L) int(input, at) else trexDuration
        var sum = 0L
        for (trun in parts.filter { it.type == "trun" }) {
            val flags = int(input, trun.body) and 0xFFFFFF
            val count = int(input, trun.body + 4)
            if (flags and 0x100 == 0L) {
                if (defaultDuration <= 0) return null
                sum += count * defaultDuration
                continue
            }
            var sample = trun.body + 8 + (if (flags and 0x01 != 0L) 4 else 0) + (if (flags and 0x04 != 0L) 4 else 0)
            val stride = 4L * listOf(0x100, 0x200, 0x400, 0x800).count { flags and it.toLong() != 0L }
            if (sample + count * stride > trun.end) return null
            repeat(count.toInt()) { sum += int(input, sample); sample += stride }
        }
        return sum
    }

    /** mvhd/mdhd's duration: version 1 has 64-bit times, which move the timescale and widen the duration. */
    private fun timed(input: RandomAccessFile, box: Box): Field? {
        val wide = byte(input, box.body) == 1
        val timescale = int(input, box.body + if (wide) 20 else 12)
        return Field(box.body + if (wide) 24 else 16, wide, timescale).takeIf { timescale > 0 }
    }

    private fun children(input: RandomAccessFile, start: Long, end: Long): List<Box> {
        val boxes = mutableListOf<Box>()
        var at = start
        val header = ByteArray(16)
        while (at + 8 <= end) {
            input.seek(at); input.readFully(header, 0, 8)
            var size = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xFFFF_FFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var body = 8L
            if (size == 1L) {
                if (at + 16 > end) break
                input.readFully(header, 8, 8); size = ByteBuffer.wrap(header, 8, 8).long; body = 16L
            }
            else if (size == 0L) size = end - at
            if (size < body || at + size > end) break
            // A 64-bit-size box is recorded 8 bytes in, so its body and end still line up.
            boxes += Box(type, at + body - 8, size - body + 8)
            at += size
        }
        return boxes
    }

    private fun byte(input: RandomAccessFile, at: Long): Int { input.seek(at); return input.read() }
    private fun int(input: RandomAccessFile, at: Long): Long { input.seek(at); return input.readInt().toLong() and 0xFFFF_FFFFL }
    private fun read(input: RandomAccessFile, field: Field): Long { input.seek(field.offset); return if (field.wide) input.readLong() else input.readInt().toLong() and 0xFFFF_FFFFL }
    private fun write(input: RandomAccessFile, field: Field, value: Long) {
        input.seek(field.offset)
        if (field.wide) input.writeLong(value) else input.writeInt(value.toInt())
    }
}
