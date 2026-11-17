package com.auralis.music.windows.discord

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsDiscordPresenceLiveTest {
    @Test
    fun realDiscordConnectionUpdatesStatusOnMac() = runBlocking {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (isWindows) return@runBlocking
        val hasSocket = com.auralis.music.desktop.discord.DiscordIpc.unixSocketCandidates().any { it.exists() }
        if (!hasSocket) return@runBlocking

        val track = DiscordTrack(Track("39fdxGmYfvg", "Live Test Song", "Live Test Artist", album = "Live Test Album", duration = 200))
        val store = DiscordCardStore(object : com.auralis.music.util.KeyValueStore {
            private val map = mutableMapOf("card" to DiscordCardSettings(enabled = true).json().toString())
            override fun getString(key: String, default: String) = map[key] ?: default
            override fun putStrings(values: Map<String, String>) { map.putAll(values) }
            override fun clear() { map.clear() }
        })
        val search = Proxy.newProxyInstance(SearchRepository::class.java.classLoader, arrayOf(SearchRepository::class.java)) { _, _, _ ->
            emptyList<com.auralis.music.domain.model.Artist>()
        } as SearchRepository
        val input = MutableStateFlow(DiscordNow(track, playing = true, played = true, position = 10000, duration = 200000))

        val presence = WindowsDiscordPresence(playback = null, legacy = null, search = search, store = store, input = input)
        try {
            withTimeout(5000) {
                while (presence.status.value != "Connected to Discord desktop") {
                    delay(50)
                }
            }
            assertEquals("Connected to Discord desktop", presence.status.value)
        } finally {
            presence.close()
        }
    }
}
