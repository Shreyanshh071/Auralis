package com.auralis.music.data.service

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

enum class SignalSource { NOT_STARTED, YOUTUBE, JIOSAAVN, DOWNLOAD, OTHER }

/**
 * What the player is really doing for the song on screen (Stats for nerds). Every figure comes from
 * the player or the stream itself; one it can't report stays null and is left off the sheet.
 */
data class PlaybackSignal(
    val source: SignalSource,
    val mediaId: String? = null,
    val itag: Int? = null,
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val decoder: String? = null,
    val outputBits: String? = null,
    val outputDevice: String? = null,
    val bufferedMs: Long = 0L,
    val networkKbps: Int? = null,
    val underruns: Int = 0,
    val speed: Float = 1f
)

internal object PlaybackSignals {
    private val SaavnBitrate = Regex("_(\\d{2,3})\\.mp4", RegexOption.IGNORE_CASE)

    fun sourceOf(url: String): SignalSource {
        val lower = url.lowercase()
        return when {
            lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("/") -> SignalSource.DOWNLOAD
            "saavncdn.com" in lower -> SignalSource.JIOSAAVN
            "googlevideo.com" in lower -> SignalSource.YOUTUBE
            else -> SignalSource.OTHER
        }
    }

    /** A googlevideo URL parameter, in its query or path form. */
    private fun urlNumber(url: String, name: String): Long? =
        Regex("[?&/]$name[=/](\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    fun itagOf(url: String): Int? = urlNumber(url, "itag")?.toInt()

    /** Bytes over time is the file's real average, which a stream's container usually leaves out. */
    fun averageKbps(bytes: Long, durationMs: Long): Int? =
        if (bytes > 0 && durationMs > 0) ((bytes * 8) / durationMs).toInt() else null

    /** The first figure that is known, most direct first. JioSaavn names its rendition in the file. */
    fun bitrateOf(url: String, durationMs: Long?, fileBytes: Long?, reportedBitsPerSecond: Int): Int? {
        val bytes = fileBytes ?: urlNumber(url, "clen")
        return durationMs?.let { duration -> bytes?.let { averageKbps(it, duration) } }
            ?: (reportedBitsPerSecond / 1000).takeIf { it > 0 }
            ?: if (sourceOf(url) == SignalSource.JIOSAAVN) SaavnBitrate.find(url)?.groupValues?.get(1)?.toIntOrNull() else null
    }

    fun codecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> mime.removePrefix("audio/").uppercase()
    }

    fun outputBits(encoding: Int): String? = when (encoding) {
        C.ENCODING_PCM_16BIT -> "16-bit"
        C.ENCODING_PCM_24BIT -> "24-bit"
        C.ENCODING_PCM_32BIT -> "32-bit"
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        else -> null
    }
}
