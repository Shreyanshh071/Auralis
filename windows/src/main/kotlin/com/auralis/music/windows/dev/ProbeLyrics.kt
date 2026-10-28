package com.auralis.music.windows.dev

import com.auralis.music.data.network.LyricsClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Runs the app's lyrics race for one song and prints the answer; the race logs every provider's
 * verdict to stderr. Normal mode uses no app data. spicy-profile reads an explicitly
 * supplied profile's session/settings, never writes them, and never prints credentials.
 * usage: probeLyrics <title> <artist> <durationSec> [videoId]
 */
fun main(rawArgs: Array<String>) = runBlocking {
    val args = if (rawArgs.first() in setOf("spicy-profile", "opening-profile")) {
        val folder = java.io.File(requireNotNull(System.getProperty("auralis.probeDataDir")))
        val stored = com.auralis.music.windows.profile.ProtectedYouTubeStore(java.io.File(folder, "spotify-session.properties"))
        com.auralis.music.data.network.SpotifySession.init(object : com.auralis.music.data.network.SpotifySession.Store {
            override fun getString(key: String) = stored.getString(key, "").orEmpty()
            override fun getLong(key: String) = stored.getString(key, "0")?.toLongOrNull() ?: 0L
            override fun put(values: Map<String, Any>) { /* read-only diagnostic */ }
            override fun clear() { /* read-only diagnostic */ }
        })
        val settings = com.auralis.music.desktop.settings.DesktopDataStores(java.io.File(folder, "settings"))
            .content.settingsFlow.first()
        com.auralis.music.data.network.ContentSettingsSource.current = { settings }
        println("SPOTIFY PROFILE: signedIn=${com.auralis.music.data.network.SpotifySession.isSignedIn}, spicyEnabled=${LyricsClient.isProviderEnabled(com.auralis.music.domain.model.LyricsProvider.SPICY_LYRICS)}")
        arrayOf(if (rawArgs.first() == "opening-profile") "opening" else "spicy", *rawArgs.drop(1).toTypedArray())
    } else rawArgs
    if (args.first() == "opening") {
        val client = LyricsClient()
        for (provider in listOf(com.auralis.music.domain.model.LyricsProvider.YOULYPLUS,
            com.auralis.music.domain.model.LyricsProvider.SPICY_LYRICS,
            com.auralis.music.domain.model.LyricsProvider.BETTER_LYRICS,
            com.auralis.music.domain.model.LyricsProvider.AMLL,
            com.auralis.music.domain.model.LyricsProvider.QQMUSIC,
            com.auralis.music.domain.model.LyricsProvider.LRCLIB)) {
            val result = client.searchProvider(provider, args[1], args[2], args[3].toLong(),
                args.getOrNull(4), durationMs = args[3].toLong() * 1000L, spotifyId = args.getOrNull(5))
            println("OPENING provider=$provider available=${result != null} sync=${result?.syncType} lines=${result?.lines?.size}")
            result?.lines?.filter { it.time < 30000L }?.forEach {
                println("OPENING_LINE provider=$provider time=${it.time} end=${it.endTime} background=${it.isBackground} text=${it.text.take(22)}")
            }
        }
        return@runBlocking
    }
    if (args.first() == "spicy") {
        val result = LyricsClient().searchProvider(com.auralis.music.domain.model.LyricsProvider.SPICY_LYRICS,
            title = args[1], artist = args[2], durationSec = args[3].toLong(),
            durationMs = args[3].toLong() * 1000L, videoId = args.getOrNull(4), spotifyId = args.getOrNull(5))
        println("SPICY CHECK: keyConfigured=${com.auralis.music.data.network.provider.SpicyLyricsSource.isNativeKey(com.auralis.music.data.network.provider.SpicyLyricsSource.effectiveKey())}, provider=${result?.provider}, sync=${result?.syncType}, lines=${result?.lines?.size}, splitWords=${result?.lines?.sumOf { line -> line.words.orEmpty().count { !it.syllables.isNullOrEmpty() } }}")
        if (result != null) {
            val units = result.lines.flatMap { line -> line.words.orEmpty().flatMap { word ->
                word.syllables ?: listOf(word)
            }.map { word -> "${word.time}:${word.endTime}:${if (line.isBackground) 1 else 0}:${word.word.trim()}" } }.sorted()
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(units.joinToString("\n").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            println("SPICY TIMING: sourceUnits=${units.size}, sha256=$fingerprint")
        }
        return@runBlocking
    }
    // probeLyrics -Pargs="album::Title::Artist::KnownAlbum": the album a song table would show.
    if (args.first() == "album") {
        println("ALBUM: " + com.auralis.music.data.network.AlbumMetadataResolver.resolveAlbum(args[1], args[2], args.getOrNull(3)))
        return@runBlocking
    }
    val (title, artist, duration) = args
    val result = LyricsClient().getLyrics(
        title = title, artist = artist, durationSec = duration.toLong(), videoId = args.getOrNull(3) ?: "",
        album = null, channelTitle = null, durationMs = duration.toLong() * 1000L,
        audioLeadingSilenceMs = null, onInterim = {})
    println("RESULT: ${result?.provider} ${result?.syncType} lines=${result?.lines?.size}")
}
