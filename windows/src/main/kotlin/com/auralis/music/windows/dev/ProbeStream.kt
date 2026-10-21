package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.DesktopStreamResolver
import kotlinx.coroutines.runBlocking

/**
 * Resolves a video's audio stream the way playback does and fetches byte ranges from it, printing
 * each status. No window, no audio, no app data written.
 * usage: probeStream -Pargs="videoId"
 */
fun main(rawArgs: Array<String>) = runBlocking {
    // gradlew.bat splits arguments at spaces, so spaces are written as '+'.
    val args = rawArgs.flatMap { it.split("::") }.map { it.replace('+', ' ') }
    val id = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "4NRXx6U8ABQ"
    val started = System.currentTimeMillis()
    val target = if (args.size >= 4) com.auralis.music.desktop.playback.DesktopPlaybackMatch.videoId(
        com.auralis.music.domain.model.Track(id = id, title = args[1], artist = args[2], duration = args[3].toLong())) else id
    println("MATCH $id -> $target")
    val url = target?.let { DesktopStreamResolver.resolve(it) } ?: target?.let { failed ->
        // As playback does: another upload of the same recording when this one can't be played.
        val track = com.auralis.music.domain.model.Track(id = id, title = args.getOrElse(1) { "" }, artist = args.getOrElse(2) { "" },
            duration = args.getOrNull(3)?.toLongOrNull() ?: 0L)
        com.auralis.music.desktop.playback.DesktopPlaybackMatch.alternative(track, failed) { DesktopStreamResolver.resolve(it) }
            ?.also { println("ALTERNATIVE $failed -> ${it.first}") }?.second
    }
    println("RESOLVE $id -> ${if (url == null) "null" else "ok"} in ${System.currentTimeMillis() - started}ms; backoff=${com.auralis.music.data.network.YouTubeBackoff.active}")
    target?.let { println("VIDEO $it -> ${if (DesktopStreamResolver.resolveVideo(it) == null) "none (still picture or no stream)" else "video"}") }
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
    if (args.size >= 5) {
        val player = checkNotNull(com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull(java.io.File(args[4])))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val playback = com.auralis.music.desktop.playback.DesktopPlaybackController(player, scope)
        try {
            playback.setVolume(0f)
            val track = com.auralis.music.domain.model.Track(id, args[1], args[2], duration = args[3].toLong())
            playback.playFrom(track, listOf(track))
            kotlinx.coroutines.withTimeout(20_000) {
                while (playback.state.value != com.auralis.music.desktop.playback.VlcAudioPlayer.State.PLAYING) kotlinx.coroutines.delay(100)
            }
            val before = playback.positionMs.value
            kotlinx.coroutines.delay(4000)
            val after = playback.positionMs.value
            check(after - before >= 2500) { "Playback did not advance: $before -> $after" }
            println("PLAYBACK PASS: imported ID played via controller; position $before -> $after ms")
        } finally {
            playback.release()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
