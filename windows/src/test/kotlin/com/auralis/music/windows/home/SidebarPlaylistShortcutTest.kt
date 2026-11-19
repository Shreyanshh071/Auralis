package com.auralis.music.windows.home

import com.auralis.music.data.playlistsync.PlaylistSyncLink
import com.auralis.music.data.playlistsync.SyncService
import com.auralis.music.data.playlistsync.SyncSnapshot
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SidebarPlaylistShortcutTest {
    private val imported = Playlist("imported:youtube_music:PLremote", "Imported playlist")
    private val linked = Playlist("local-copy", "Renamed playlist")
    private val sameName = Playlist("unrelated", imported.title)
    private val shortcut = SpeedDialItem(
        "album-local:${imported.id}", imported.title, SpeedDialType.ALBUM,
        album = PlaylistResult("local:${imported.id}", imported.title)
    )
    private val link = PlaylistSyncLink(
        linked.id, SyncService.YOUTUBE, "VLPLremote", "account",
        SyncSnapshot(imported.title, "", emptyList()), emptyMap()
    )

    @Test fun resolvesTheLinkedLocalPlaylistAfterItsImportedRowDisappears() {
        val playlists = listOf(sameName, linked)
        assertEquals(linked, resolveShortcutPlaylist(shortcut, playlists, listOf(link)))
        val legacyShortcut = SpeedDialItem("playlist-${imported.id}", imported.title, SpeedDialType.PLAYLIST)
        assertEquals(linked, resolveShortcutPlaylist(legacyShortcut, playlists, listOf(link)))
    }

    @Test fun prefersTheOriginalPlaylistWhenItsIdStillExists() {
        assertEquals(imported, resolveShortcutPlaylist(shortcut, listOf(linked, imported), listOf(link)))
    }

    @Test fun doesNotGuessWhenTheLinkIsMissingOrAmbiguous() {
        assertNull(resolveShortcutPlaylist(shortcut, listOf(sameName), listOf(link)))
        assertNull(resolveShortcutPlaylist(shortcut, listOf(linked), listOf(link.copy(service = SyncService.SPOTIFY))))
        assertNull(resolveShortcutPlaylist(shortcut, listOf(linked, sameName), listOf(link, link.copy(localId = sameName.id))))
    }

    @Test fun hidesDeletedPlaylistShortcutsAndKeepsValidLinks() {
        assertFalse(isShortcutAvailable(shortcut, listOf(sameName), emptyList()))
        assertFalse(isShortcutAvailable(shortcut.copy(type = SpeedDialType.PLAYLIST,
            id = "playlist-${imported.id}", album = null), emptyList(), emptyList()))
        assertTrue(isShortcutAvailable(shortcut, listOf(linked), listOf(link)))
        val album = PlaylistResult("MPRE-album", "Album")
        assertTrue(isShortcutAvailable(shortcut.copy(id = "album-${album.id}", album = album), emptyList(), emptyList()))
    }
}
