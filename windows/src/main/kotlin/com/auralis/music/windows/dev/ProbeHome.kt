package com.auralis.music.windows.dev

import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.util.desktopAppDataDir
import com.auralis.music.windows.home.parseRemoteHome
import com.auralis.music.windows.profile.YouTubeMusicClient
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Loads the signed-in YouTube Music Home the way the app does (the app's own saved session; no
 * browser is read) and prints each shelf's first covers with whether they load. Read only.
 * usage: probeHome
 */
fun main(args: Array<String>) = runBlocking {
    // probeHome -Pargs=signin: the sign-in window's browser pipe, unseen (no window, no account).
    if (args.firstOrNull() == "signin") {
        println("SIGNIN " + com.auralis.music.windows.profile.YouTubeBrowserSignIn.devtoolsCheck())
        return@runBlocking
    }
    YouTubeSession.init(com.auralis.music.windows.profile.ProtectedYouTubeStore(File(desktopAppDataDir(), "youtube-session.properties")))
    check(YouTubeSession.isSignedIn) { "No YouTube session saved in the app" }
    val headers = YouTubeSession.authHeaders(YouTubeMusicClient.ORIGIN)
    val api = YouTubeMusicClient(headers["Cookie"].orEmpty(), headers["X-Goog-AuthUser"] ?: "0")
    api.resolveScope()
    val http = com.auralis.music.data.network.NetworkClientProvider.okHttpClient
    // The first page and two more, as the app loads them while scrolling.
    var root = api.browseHome()
    val shelves = parseRemoteHome(root).toMutableList()
    repeat(2) {
        val token = com.auralis.music.windows.home.remoteHomeContinuation(root) ?: return@repeat
        root = api.browseHome(continuation = token); shelves += parseRemoteHome(root)
    }
    for (shelf in shelves) {
        println("SHELF ${shelf.title} tracks=${shelf.tracks.size} albums=${shelf.albums.size}")
        val urls = shelf.tracks.take(3).map { it.title to it.thumbnail } + shelf.albums.take(2).map { it.title to it.thumbnail.orEmpty() }
        for ((title, url) in urls) {
            val optimized = com.auralis.music.ui.components.getOptimizedThumbnailUrl(url) ?: ""
            fun code(u: String) = if (u.isBlank()) "blank" else runCatching {
                http.newCall(okhttp3.Request.Builder().url(u).head().build()).execute().use { it.code.toString() } }.getOrElse { "err ${it.message}" }
            println("  $title\n    raw=$url -> ${code(url)}\n    opt=$optimized -> ${code(optimized)}")
        }
    }
}
