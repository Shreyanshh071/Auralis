package com.auralis.music.windows.dev

import com.auralis.music.windows.profile.YouTubeMusicClient
import com.auralis.music.windows.home.parseRemoteHome
import kotlinx.coroutines.runBlocking

/** Read-only public-feed check: never loads the listener's credentials or plays audio. */
fun main() = runBlocking {
    val api = YouTubeMusicClient(cookie = "")
    api.resolvePublicScope()
    var total = 0
    for (page in listOf("FEmusic_home", "FEmusic_explore", "FEmusic_new_releases")) {
        try {
            val shelves = parseRemoteHome(api.browseHome(page))
            val cards = shelves.sumOf { it.tracks.size + it.albums.size }
            total += cards
            println("$page: ${shelves.size} shelves, $cards cards; ${shelves.joinToString { it.title }}")
        } catch (e: Exception) { println("$page: ${e.javaClass.simpleName}") }
    }
    check(total > 0) { "No public feed cards returned" }
}
