package com.auralis.music.playlistsync

import com.auralis.music.data.playlistsync.*
import com.auralis.music.domain.model.Track
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class OwnCopyPlaylistSyncTest {
    private class Memory : KeyValueStore {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String, default: String) = data[key] ?: default
        override fun putStrings(values: Map<String, String>) { data.putAll(values) }
        override fun clear() { data.clear() }
    }
    private fun track(id: String) = Track(id, "Song $id", "Artist", duration = 180)

    /** A YouTube account: [lists] are its playlists; anything else can't be read. */
    private class Account(val lists: MutableMap<String, SyncSnapshot>, val readOnly: Set<String> = emptySet()) : PlaylistSyncRemote {
        override val service = SyncService.YOUTUBE
        var writes = 0
        var offline = false
        override suspend fun account() = "second-account"
        override suspend fun read(id: String): RemoteSyncPlaylist {
            if (offline) throw java.io.IOException("Offline")
            val list = lists[id] ?: error("YouTube did not return a complete playlist header. Nothing was changed.")
            return RemoteSyncPlaylist(id, "second-account", list, list.ids.map { Track(it, "Song $it", "Artist", duration = 180) }, "1", id !in readOnly)
        }
        override suspend fun editablePlaylistsNamed(title: String) =
            lists.entries.filter { (id, list) -> list.title.equals(title, true) && id !in readOnly }.map { it.key }
        override suspend fun create(title: String, description: String) = error("Must not create a playlist")
        override suspend fun identify(track: Track) = track.id.takeUnless { it == "unmatched" }
        override suspend fun write(before: RemoteSyncPlaylist, target: SyncSnapshot) { writes++; lists[before.id] = target }
    }

    private suspend fun scenario(account: Account, body: suspend (SyncTestLibrary, PlaylistSyncStore, PlaylistSyncEngine, String) -> Unit) {
        val dir = Files.createTempDirectory("own-copy").toFile()
        try {
            val library = SyncTestLibrary(java.io.File(dir, "library.json"))
            val id = library.restorePlaylist("imported:youtube_music:PLtheirs0000", "A").id
            library.addTrackToPlaylist(id, track("a"))
            val store = PlaylistSyncStore(Memory())
            body(library, store, PlaylistSyncEngine(library, store, mapOf(SyncService.YOUTUBE to account)), id)
        } finally { dir.deleteRecursively() }
    }

    @Test fun anotherAccountSyncsWithItsOwnPlaylistOfTheSameNameAndSongs() = runBlocking<Unit> {
        val account = Account(mutableMapOf("PLmine000000" to SyncSnapshot("a", "", listOf("a"))))
        scenario(account) { library, store, engine, id ->
            engine.enable(id)
            val link = store.links.value.single()
            assertEquals("PLmine000000", link.remoteId)
            assertTrue(link.ownCopy)

            library.addTrackToPlaylist(id, track("c"))
            engine.sync(id)
            assertEquals(listOf("a", "c"), account.lists.getValue("PLmine000000").ids)
            assertFalse(engine.status.value[id].orEmpty().contains("paused"))
            assertTrue(store.links.value.single().ownCopy)
        }
    }

    @Test fun theSameSongsInAnotherOrderAreStillTheSamePlaylist() = runBlocking<Unit> {
        val account = Account(mutableMapOf("PLmine000000" to SyncSnapshot("A", "", listOf("b", "a"))))
        scenario(account) { library, store, engine, id ->
            library.addTrackToPlaylist(id, track("b"))
            engine.enable(id)
            assertEquals("PLmine000000", store.links.value.single().remoteId)
        }
    }

    @Test fun aSameNamedPlaylistWithOtherSongsIsNeverMergedInto() = runBlocking<Unit> {
        val account = Account(mutableMapOf("PLmine000000" to SyncSnapshot("A", "", listOf("x", "y"))))
        scenario(account) { _, store, engine, id ->
            val message = assertFailsWith<IllegalStateException> { engine.enable(id) }.message.orEmpty()
            assertTrue("same name and songs" in message)
            assertTrue(store.links.value.isEmpty())
            assertEquals(0, account.writes)
            assertEquals(listOf("x", "y"), account.lists.getValue("PLmine000000").ids)
        }
    }

    @Test fun anAccountWithoutThePlaylistIsToldWhatToDoAndNothingChanges() = runBlocking<Unit> {
        val account = Account(mutableMapOf())
        scenario(account) { _, store, engine, id ->
            val message = assertFailsWith<IllegalStateException> { engine.enable(id) }.message.orEmpty()
            assertTrue("Sign in with the account that owns it" in message)
            assertTrue(store.links.value.isEmpty())
            assertEquals(0, account.writes)
        }
    }

    @Test fun someoneElsesPlaylistItCanOnlyReadIsNotEdited() = runBlocking<Unit> {
        val account = Account(mutableMapOf("PLtheirs0000" to SyncSnapshot("A", "", listOf("a"))), readOnly = setOf("PLtheirs0000"))
        scenario(account) { _, store, engine, id ->
            assertFailsWith<IllegalStateException> { engine.enable(id) }
            assertTrue(store.links.value.isEmpty())
            assertEquals(0, account.writes)
        }
    }

    @Test fun theOwnerStillLinksTheOriginalPlaylist() = runBlocking<Unit> {
        val account = Account(mutableMapOf("PLtheirs0000" to SyncSnapshot("A", "", listOf("a"))))
        scenario(account) { _, store, engine, id ->
            engine.enable(id)
            assertEquals("PLtheirs0000", store.links.value.single().remoteId)
            assertFalse(store.links.value.single().ownCopy)
        }
    }

    @Test fun beingOfflineIsNotReportedAsAMissingPlaylist() = runBlocking<Unit> {
        val account = Account(mutableMapOf()).apply { offline = true }
        scenario(account) { _, _, engine, id ->
            assertFailsWith<java.io.IOException> { engine.enable(id) }
        }
    }

    @Test fun anUnmatchedSongKeepsItsOwnMessage() = runBlocking<Unit> {
        val account = Account(mutableMapOf("PLtheirs0000" to SyncSnapshot("A", "", emptyList())))
        scenario(account) { library, _, engine, id ->
            library.addTrackToPlaylist(id, track("unmatched"))
            val message = assertFailsWith<IllegalStateException> { engine.enable(id) }.message.orEmpty()
            assertTrue("Cannot confidently match" in message)
        }
    }

    @Test fun theOwnCopyLinkSurvivesARestart() = runBlocking<Unit> {
        val memory = Memory()
        val first = PlaylistSyncStore(memory)
        first.put(PlaylistSyncLink("imported:youtube_music:x", SyncService.YOUTUBE, "PLmine", "account",
            SyncSnapshot("A", "", emptyList()), emptyMap(), ownCopy = true))
        assertTrue(PlaylistSyncStore(memory).links.value.single().ownCopy)
    }
}
