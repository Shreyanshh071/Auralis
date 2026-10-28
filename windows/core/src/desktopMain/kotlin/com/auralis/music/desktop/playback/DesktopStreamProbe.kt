package com.auralis.music.desktop.playback

import okhttp3.OkHttpClient
import okhttp3.Request

internal object DesktopStreamProbe {
    fun playable(status: Int) = status == 200 || status == 206

    /** Validate real starting audio, with bounded reads even when a server ignores Range. */
    fun check(client: OkHttpClient, url: String, userAgent: String,
        firstBlock: (String, ByteArray) -> Unit): Int {
        return try {
            val request = Request.Builder().url(url).header("User-Agent", userAgent)
                .header("Range", "bytes=0-${LocalStreamRelay.FIRST_BLOCK_BYTES - 1}")
                .header("Accept-Encoding", "identity").build()
            client.newCall(request).execute().use { response ->
                if (!playable(response.code)) return response.code
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (type.startsWith("text/") || type.contains("json")) return -1
                val range = response.header("Content-Range")
                if (response.code == 206 && range?.startsWith("bytes 0-") != true) return -1
                val length = Regex("[?&]clen=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
                    ?: range?.substringAfter('/')?.toLongOrNull()
                val expected = minOf(LocalStreamRelay.FIRST_BLOCK_BYTES,
                    length?.takeIf { it > 0 } ?: LocalStreamRelay.FIRST_BLOCK_BYTES).toInt()
                val bytes = response.body?.byteStream()?.readNBytes(expected) ?: return -1
                if (bytes.size != expected) return -1
                firstBlock(url, bytes)
                response.code
            }
        } catch (_: Exception) { -1 }
    }
}
