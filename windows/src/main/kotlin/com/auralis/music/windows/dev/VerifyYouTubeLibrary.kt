package com.auralis.music.windows.dev

import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.windows.profile.ProtectedYouTubeStore
import com.auralis.music.windows.profile.YouTubeMusicClient
import java.io.File
import kotlinx.coroutines.runBlocking

/** Explicit read-only diagnostic; prints counts, never session credentials or profile details. */
fun main() = runBlocking {
    YouTubeSession.init(ProtectedYouTubeStore(File(com.auralis.music.util.desktopAppDataDir(), "youtube-session.properties")))
    val client = YouTubeMusicClient()
    client.resolveScope()
    client.identity()
    println("YouTube account verified")
    val playlists = client.playlists { println("Library entries loaded: ${it.size}") }
    val first = playlists.firstOrNull { it.id != "VLLM" }
    if (first != null) println("Tracks loaded from one playlist: ${client.tracks(first) {}.size}")
}
