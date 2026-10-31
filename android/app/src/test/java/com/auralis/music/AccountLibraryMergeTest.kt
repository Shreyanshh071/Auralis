package com.auralis.music

import com.auralis.music.data.sync.AccountLibraryMerge
import com.auralis.music.data.sync.SyncBaseline
import com.auralis.music.data.sync.SyncedLibrary
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import org.junit.Assert.*
import org.junit.Test

class AccountLibraryMergeTest {
    private val now = 1_000_000_000_000L
    private fun track(id: String) = Track(id = id, title = id, artist = "Artist")
    private fun list(id: String, vararg songs: String, createdAt: Long = 100L) =
        Playlist(id, id, null, null, songs.map(::track), createdAt = createdAt)
    private fun lib(vararg lists: Playlist, deleted: Map<String, Long> = emptyMap()) =
        SyncedLibrary(playlists = lists.toList(), deleted = deleted)
    private fun agreed(vararg lists: Playlist) =
        SyncBaseline(playlists = lists.associate { it.id to AccountLibraryMerge.fingerprint(it) })

    @Test fun `a playlist added on another device reaches this one and stays in the account`() {
        val shared = list("shared", "a")
        val plan = AccountLibraryMerge.merge(lib(shared), lib(shared, list("new", "b")), agreed(shared), now)
        assertEquals(listOf("new"), plan.writePlaylists.map { it.id })
        assertEquals(setOf("shared", "new"), plan.account.playlists.map { it.id }.toSet())
    }

    @Test fun `a device that has not caught up cannot erase another device's playlist`() {
        val shared = list("shared", "a")
        val plan = AccountLibraryMerge.merge(lib(shared, list("mine", "c")), lib(shared, list("theirs", "d")), agreed(shared), now)
        assertEquals(setOf("shared", "mine", "theirs"), plan.account.playlists.map { it.id }.toSet())
        assertTrue(plan.accountChanged)
    }

    @Test fun `a deletion here reaches the account and marks the playlist deleted`() {
        val keep = list("keep", "a"); val gone = list("gone", "b"); val other = list("other", "c"); val more = list("more", "d")
        val plan = AccountLibraryMerge.merge(lib(keep, other, more), lib(keep, other, more, gone), agreed(keep, other, more, gone), now)
        assertFalse(plan.account.playlists.any { it.id == "gone" })
        assertEquals(now, plan.account.deleted["gone"])
    }

    @Test fun `a deletion on another device removes the playlist here`() {
        val gone = list("gone", "b", createdAt = 100L)
        val plan = AccountLibraryMerge.merge(lib(gone), lib(deleted = mapOf("gone" to now - 1000L)), agreed(gone), now)
        assertEquals(setOf("gone"), plan.deletePlaylists)
    }

    @Test fun `a deleted playlist imported again afterwards comes back`() {
        val again = list("gone", "b", createdAt = now - 500L)
        val plan = AccountLibraryMerge.merge(lib(again), lib(deleted = mapOf("gone" to now - 1000L)), null, now)
        assertTrue(plan.deletePlaylists.isEmpty())
        assertTrue(plan.account.deleted.isEmpty())
        assertEquals(listOf("gone"), plan.account.playlists.map { it.id })
    }

    @Test fun `an old device overwriting the account never deletes playlists elsewhere`() {
        val mine = list("mine", "a")
        val plan = AccountLibraryMerge.merge(lib(mine), lib(), agreed(mine), now)
        assertTrue(plan.deletePlaylists.isEmpty())
        assertEquals(listOf("mine"), plan.account.playlists.map { it.id })
    }

    @Test fun `a wiped phone never turns into deleting the whole library`() {
        val cloud = lib(list("a", "1"), list("b", "2"), list("c", "3"))
        val plan = AccountLibraryMerge.merge(lib(), cloud, agreed(*cloud.playlists.toTypedArray()), now)
        assertEquals(3, plan.writePlaylists.size)
        assertTrue(plan.account.deleted.isEmpty())
    }

    @Test fun `most of a library going missing at once is treated as lost not deleted`() {
        val all = (1..6).map { list("p$it", "s$it") }
        val plan = AccountLibraryMerge.merge(lib(all[0], all[1]), lib(*all.toTypedArray()), agreed(*all.toTypedArray()), now)
        assertEquals(6, plan.account.playlists.size)
        assertTrue(plan.account.deleted.isEmpty())
    }

    @Test fun `first sync combines both sides and loses nothing`() {
        val plan = AccountLibraryMerge.merge(lib(list("x", "a", "b")), lib(list("x", "b", "c"), list("y", "d")), null, now)
        val x = plan.account.playlists.first { it.id == "x" }
        assertEquals(setOf("a", "b", "c"), x.tracks.map { it.id }.toSet())
        assertEquals(setOf("x", "y"), plan.writePlaylists.map { it.id }.toSet())
    }

    @Test fun `a song added on another device arrives, and one edited here goes up`() {
        val before = list("x", "a")
        val cloudNewer = AccountLibraryMerge.merge(lib(before), lib(list("x", "a", "b")), agreed(before), now)
        assertEquals(listOf("a", "b"), cloudNewer.writePlaylists.single().tracks.map { it.id })
        val localNewer = AccountLibraryMerge.merge(lib(list("x", "a", "b")), lib(before), agreed(before), now)
        assertTrue(localNewer.writePlaylists.isEmpty())
        assertEquals(listOf("a", "b"), localNewer.account.playlists.single().tracks.map { it.id })
    }

    @Test fun `a linked playlist keeps this device's copy`() {
        val before = list("x", "a")
        val plan = AccountLibraryMerge.merge(lib(list("x", "a", "local")), lib(list("x", "a", "cloud")), agreed(before), now, protected = setOf("x"))
        assertTrue(plan.writePlaylists.isEmpty())
        assertEquals(listOf("a", "local"), plan.account.playlists.single().tracks.map { it.id })
    }

    @Test fun `the oldest date added wins on every device`() {
        val tracks = arrayOf("a")
        val plan = AccountLibraryMerge.merge(lib(list("x", *tracks, createdAt = 900L)), lib(list("x", *tracks, createdAt = 100L)), null, now)
        assertEquals(100L, plan.writePlaylists.single().createdAt)
    }

    @Test fun `nothing to do writes nothing, so devices cannot loop`() {
        val same = list("x", "a")
        val plan = AccountLibraryMerge.merge(lib(same), lib(same), agreed(same), now)
        assertFalse(plan.accountChanged)
        assertTrue(plan.writePlaylists.isEmpty())
    }

    @Test fun `a second device settles after one sync`() {
        val first = AccountLibraryMerge.merge(lib(list("a", "1")), lib(list("b", "2")), null, now)
        val after = first.account.copy(playlists = first.account.playlists)
        val second = AccountLibraryMerge.merge(after, after, first.baseline, now)
        assertFalse(second.accountChanged)
    }

    @Test fun `an unliked song is unliked everywhere, a new like arrives everywhere`() {
        val keep = (1..8).map { track("k$it") }
        val base = SyncBaseline(favorites = (keep + track("gone")).map { it.id }.toSet())
        val plan = AccountLibraryMerge.merge(
            SyncedLibrary(favorites = keep), SyncedLibrary(favorites = keep + track("gone") + track("new")), base, now)
        assertEquals(listOf("new"), plan.addFavorites.map { it.id })
        assertFalse(plan.account.favorites.any { it.id == "gone" })
    }

    @Test fun `the newest pin change wins on every device`() {
        val plan = AccountLibraryMerge.merge(
            SyncedLibrary(pins = listOf("a"), pinsAt = 10L), SyncedLibrary(pins = listOf("b", "c"), pinsAt = 20L), null, now)
        assertEquals(listOf("b", "c"), plan.pins)
        assertEquals(20L, plan.pinsAt)
    }

    @Test fun `old deletion markers are forgotten`() {
        val plan = AccountLibraryMerge.merge(lib(), lib(deleted = mapOf("old" to 1L)), null, now)
        assertTrue(plan.account.deleted.isEmpty())
    }

    @Test fun `the baseline survives being saved`() {
        val base = SyncBaseline(mapOf("imported:spotify:x" to "12"), setOf("a"), setOf("b"), setOf("c"))
        assertEquals(base, SyncBaseline.decode(base.encode()))
        assertNull(SyncBaseline.decode(null))
    }
}
