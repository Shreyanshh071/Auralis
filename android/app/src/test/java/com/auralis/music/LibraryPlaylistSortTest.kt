package com.auralis.music

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.library.sortLibraryPlaylists
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPlaylistSortTest {
    private val playlists = listOf(
        Playlist("older", "Alpha", tracks = listOf(Track(id = "old")), createdAt = 10L),
        Playlist("newer", "Zebra", tracks = listOf(Track(id = "new"), Track(id = "extra")), createdAt = 30L),
        Playlist("middle", "Mango", tracks = listOf(Track(id = "middle")), createdAt = 20L)
    )

    @Test
    fun eachLibraryOptionChangesTheDisplayedOrder() {
        val history = mapOf("old" to 100L, "new" to 200L, "middle" to 300L)
        val expected = mapOf(
            "Date added" to listOf("newer", "middle", "older"),
            "Recently played" to listOf("middle", "newer", "older"),
            "Alphabetical (A to Z)" to listOf("older", "middle", "newer"),
            "Alphabetical (Z to A)" to listOf("newer", "middle", "older"),
            "Track count" to listOf("newer", "older", "middle")
        )

        expected.forEach { (option, ids) ->
            assertEquals(ids, sortLibraryPlaylists(playlists, option, history).map { it.id })
        }
    }
}
