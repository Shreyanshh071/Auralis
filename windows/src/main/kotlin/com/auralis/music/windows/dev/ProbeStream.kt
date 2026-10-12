package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.DesktopStreamResolver
import kotlinx.coroutines.runBlocking

/**
 * Resolves a video's audio stream the way playback does and fetches byte ranges from it, printing
 * each status. No window, no audio, no app data written.
 * usage: probeStream -Pargs="videoId"
 */
fun main(args: Array<String>) = runBlocking {
    val id = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "4NRXx6U8ABQ"
    val started = System.currentTimeMillis()
    val url = DesktopStreamResolver.resolve(id)
    println("RESOLVE $id -> ${if (url == null) "null" else "ok"} in ${System.currentTimeMillis() - started}ms; backoff=${com.auralis.music.data.network.YouTubeBackoff.active}")
    if (url == null) return@runBlocking
    println("CLIENT " + Regex("[?&]c=([^&]+)").find(url)?.groupValues?.get(1) + " HOST " + java.net.URI(url).host)
    val client = com.auralis.music.data.network.NetworkClientProvider.okHttpClient
    listOf(0L to 524287L, 524288L to 1048575L, 1048576L to 1572863L, 2097152L to 2621439L).forEach { (a, b) ->
        val request = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .header("Range", "bytes=$a-$b").build()
        val result = runCatching { client.newCall(request).execute().use { "${it.code} ${it.body?.bytes()?.size ?: 0} bytes" } }
        println("RANGE $a-$b -> ${result.getOrElse { "error ${it.message}" }}")
    }
}
