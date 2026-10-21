package com.auralis.music

import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.sync.AccountLibraryRestore
import com.auralis.music.data.sync.CloudLibrary
import com.auralis.music.data.sync.CloudListen
import com.auralis.music.data.sync.CloudListens
import com.auralis.music.data.sync.InTransaction
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing in on a new phone must show the account's whole library and stats at once: playlists
 * with all their songs, every like, the full listening total. Signing out takes off only what the
 * account brought; the phone's own imports stay.
 */
class AccountRestoreOnePassTest {

    /**
     * A library that behaves like Room: writes inside a transaction are seen by observers only
     * when it commits; writes outside one are seen immediately. Records every state observers saw.
     */
    private class RoomLikeLibrary : LibraryRepository {
        private var playlists = listOf<Playlist>()
        private var favorites = listOf<Track>()
        private var albums = listOf<SavedAlbum>()
        private var artists = listOf<SavedArtist>()
        private val published = MutableStateFlow(Snapshot(emptyList(), emptyList()))
        data class Snapshot(val playlists: List<Playlist>, val favorites: List<Track>)
        val seen = mutableListOf<Snapshot>()
        var inTransaction = false
        var writesOutsideTransaction = 0
        var transactions = 0

        val transaction: InTransaction = { block ->
            transactions++
            inTransaction = true
            try { block() } finally { inTransaction = false; publish() }
        }

        private fun wrote() {
            if (!inTransaction) { writesOutsideTransaction++; publish() }
        }
        private fun publish() {
            val s = Snapshot(playlists, favorites)
            if (s != published.value) { published.value = s; seen += s }
        }

        /** Sets up what the phone had before signing in (not counted as restore writes). */
        fun seed(p: List<Playlist>, f: List<Track>) { playlists = p; favorites = f; publish(); seen.clear() }

        override fun getFavoriteTracks(): Flow<List<Track>> = published.map { it.favorites }
        override fun isFavorite(trackId: String): Flow<Boolean> = published.map { s -> s.favorites.any { it.id == trackId } }
        override suspend fun toggleFavorite(track: Track) = setFavorite(track, favorites.none { it.id == track.id })
        override suspend fun setFavorite(track: Track, isFavorite: Boolean) {
            favorites = if (isFavorite) { if (favorites.any { it.id == track.id }) favorites else listOf(track) + favorites }
            else favorites.filterNot { it.id == track.id }
            wrote()
        }
        override fun getPlaylists(): Flow<List<Playlist>> = published.map { it.playlists }
        override fun getPlaylist(playlistId: String): Flow<Playlist?> = published.map { s -> s.playlists.firstOrNull { it.id == playlistId } }
        override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist =
            restorePlaylist("local-${playlists.size + 1}", title, description, coverUrl)
        override suspend fun restorePlaylist(id: String, title: String, description: String?, coverUrl: String?): Playlist {
            val existing = playlists.firstOrNull { it.id == id }
            val p = existing?.copy(title = title, description = description, coverUrl = coverUrl)
                ?: Playlist(id, title, description, coverUrl, createdAt = 1L)
            playlists = if (existing != null) playlists.map { if (it.id == id) p else it } else playlists + p
            wrote()
            return p
        }
        override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) {
            restorePlaylist(playlistId, title, description, coverUrl)
        }
        override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Boolean = error("not used")
        override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) = error("not used")
        override suspend fun deletePlaylist(playlistId: String) { playlists = playlists.filterNot { it.id == playlistId }; wrote() }
        override suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>) = replacePlaylistTracks(playlistId, tracks)
        override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) {
            playlists = playlists.map { if (it.id == playlistId) it.copy(tracks = tracks) else it }
            wrote()
        }
        override fun getSavedArtists(): Flow<List<SavedArtist>> = MutableStateFlow(artists)
        override fun isArtistSaved(artistId: String): Flow<Boolean> = MutableStateFlow(artists.any { it.id == artistId })
        override suspend fun saveArtist(artist: SavedArtist) { artists = artists.filterNot { it.id == artist.id } + artist; wrote() }
        override suspend fun removeArtist(artistId: String) { artists = artists.filterNot { it.id == artistId }; wrote() }
        override fun getSavedAlbums(): Flow<List<SavedAlbum>> = MutableStateFlow(albums)
        override fun isAlbumSaved(albumId: String): Flow<Boolean> = MutableStateFlow(albums.any { it.id == albumId })
        override suspend fun saveAlbum(album: SavedAlbum) { albums = albums.filterNot { it.id == album.id } + album; wrote() }
        override suspend fun removeAlbum(albumId: String) { albums = albums.filterNot { it.id == albumId }; wrote() }
        fun current() = Snapshot(playlists, favorites)
    }

    private fun trackMap(id: String) = mapOf("id" to id, "title" to "Song $id", "artist" to "Artist", "album" to null,
        "thumbnail" to "https://i.ytimg.com/vi/$id/hq.jpg", "duration" to 200L)

    private fun playlistMap(id: String, title: String, songs: Int) = mapOf(
        "id" to id, "title" to title, "description" to null, "coverUrl" to null,
        "tracks" to (1..songs).map { trackMap("$id-$it") })

    /** An account backup as the main phone writes it (GoogleAccountSyncManager.backupLibraryToCloud). */
    private val accountDoc: Map<String, Any?> = mapOf(
        "playlists" to listOf(
            playlistMap("p-gym", "Gym", 120),
            playlistMap("p-chill", "Chill", 87),
            playlistMap("imported:spotify:abc", "Road trip", 45)
        ),
        "favorites" to (1..300).map { trackMap("liked-$it") },
        "savedAlbums" to listOf(mapOf("id" to "al1", "title" to "Album", "savedAt" to 5L)),
        "savedArtists" to listOf(mapOf("id" to "ar1", "name" to "Artist"))
    )

    @Test
    fun `fresh sign-in restores every playlist with all its songs and every like in one step`() = runBlocking {
        val phone = RoomLikeLibrary()
        val restored = AccountLibraryRestore.apply(phone, CloudLibrary.parse(accountDoc), phone.transaction)

        assertEquals("one transaction for the whole library", 1, phone.transactions)
        assertEquals("no row written on its own", 0, phone.writesOutsideTransaction)
        assertEquals("observers see one change: empty, then everything", 1, phone.seen.size)
        val shown = phone.seen.single()
        assertEquals(listOf("Gym" to 120, "Chill" to 87, "Road trip" to 45), shown.playlists.map { it.title to it.tracks.size })
        assertTrue("no playlist ever shown with 0 songs", phone.seen.none { s -> s.playlists.any { it.tracks.isEmpty() } })
        assertEquals(300, shown.favorites.size)
        assertEquals(3, restored.playlists)
        assertEquals(300, restored.liked)
        assertEquals(setOf("p-gym", "p-chill", "imported:spotify:abc"), restored.addedPlaylistIds)
        assertEquals(300, restored.addedFavoriteIds.size)
    }

    @Test
    fun `sign-out removes only what the account brought and keeps the phone's own imports`() = runBlocking {
        val phone = RoomLikeLibrary()
        val ownSpotify = Playlist("imported:spotify:mine", "My Spotify import", tracks = listOf(Track("s1", "S", "A")))
        val ownYtLink = Playlist("imported:youtube_music:PLlink", "From a YT Music link", tracks = listOf(Track("y1", "Y", "A")))
        val ownPick = Playlist("imported:youtube_music:PLpick", "Picked from YouTube Music", tracks = listOf(Track("y2", "Y2", "A")))
        val ownMade = Playlist("uuid-local", "Made on this phone", tracks = listOf(Track("m1", "M", "A")))
        // The same Spotify playlist imported here AND on the main phone: it's the user's, it stays.
        val sharedImport = Playlist("imported:spotify:abc", "Road trip", tracks = listOf(Track("r1", "R", "A")))
        val ownLike = Track("own-like", "Mine", "Me")
        val alsoInAccount = Track("liked-1", "Song liked-1", "Artist")
        phone.seed(listOf(ownSpotify, ownYtLink, ownPick, ownMade, sharedImport), listOf(ownLike, alsoInAccount))

        val restored = AccountLibraryRestore.apply(phone, CloudLibrary.parse(accountDoc), phone.transaction)
        assertEquals(setOf("p-gym", "p-chill"), restored.addedPlaylistIds)
        assertFalse("a like already here isn't the account's", "liked-1" in restored.addedFavoriteIds)
        assertEquals(299, restored.addedFavoriteIds.size)

        val removed = AccountLibraryRestore.removeAccountItems(phone, restored.addedPlaylistIds, restored.addedFavoriteIds, phone.transaction)
        assertEquals(2, removed)
        val after = phone.current()
        assertEquals(
            setOf("imported:spotify:mine", "imported:youtube_music:PLlink", "imported:youtube_music:PLpick", "uuid-local", "imported:spotify:abc"),
            after.playlists.map { it.id }.toSet()
        )
        assertEquals("the phone's own playlists keep their songs", 1, after.playlists.first { it.id == "uuid-local" }.tracks.size)
        assertEquals(setOf("own-like", "liked-1"), after.favorites.map { it.id }.toSet())
        assertEquals(0, phone.writesOutsideTransaction)
    }

    @Test
    fun `sign-out with nothing recorded removes nothing`() = runBlocking {
        val phone = RoomLikeLibrary()
        phone.seed(listOf(Playlist("uuid-local", "Mine")), listOf(Track("own-like", "Mine", "Me")))
        assertEquals(0, AccountLibraryRestore.removeAccountItems(phone, emptySet(), emptySet(), phone.transaction))
        assertEquals(1, phone.current().playlists.size)
        assertEquals(1, phone.current().favorites.size)
    }

    @Test
    fun `parse skips malformed entries and keeps the rest`() {
        val cloud = CloudLibrary.parse(mapOf(
            "playlists" to listOf("junk", mapOf("title" to "", "tracks" to listOf(mapOf("title" to "no id"), trackMap("ok")))),
            "favorites" to listOf(mapOf("id" to ""), trackMap("f1")),
            "savedAlbums" to listOf(mapOf("id" to "a")),
            "savedArtists" to listOf(mapOf("id" to "x", "name" to "X"))
        ))
        assertEquals(1, cloud.playlists.size)
        assertEquals("Restored Playlist", cloud.playlists[0].title)
        assertEquals(null, cloud.playlists[0].id)
        assertEquals(listOf("ok"), cloud.playlists[0].tracks.map { it.id })
        assertEquals(listOf("f1"), cloud.favorites.map { it.id })
        assertTrue(cloud.albums.isEmpty())
        assertEquals(1, cloud.artists.size)
    }

    private fun day(vararg events: Triple<String, Long, Long>): Map<*, *> =
        events.associate { (t, ts, ms) -> "${ts}_$t" to mapOf("t" to t, "ts" to ts, "ms" to ms, "ti" to "Song $t", "ar" to "A", "du" to 200L) }

    @Test
    fun `fresh phone gets the account's full measured listening total in one plan`() {
        val days = listOf(
            day(Triple("a", 1_000L, 60_000L), Triple("b", 500_000L, 185_000L)),
            day(Triple("a", 90_000_000L, 12_345L), Triple("c", 90_400_000L, 7L))
        )
        val incoming = CloudListens.parse(days)
        val plan = CloudListens.plan(incoming, existing = emptyList())
        assertEquals(4, plan.inserts.size)
        assertTrue(plan.grows.isEmpty())
        assertEquals("exactly the measured time, nothing estimated", 60_000L + 185_000L + 12_345L + 7L, plan.inserts.sumOf { it.playTimeMs })
    }

    @Test
    fun `listens already here are skipped, shorter copies grow, never shrink`() {
        val existing = listOf(
            PlaybackEventEntity(id = 1, trackId = "a", timestamp = 1_000L, playTimeMs = 60_000L),
            PlaybackEventEntity(id = 2, trackId = "b", timestamp = 500_000L, playTimeMs = 5_000L)
        )
        val incoming = listOf(
            CloudListen(Track("a"), 1_500L, 30_000L),     // same listen, shorter: skipped
            CloudListen(Track("b"), 499_000L, 58_000L),   // same listen, synced while playing: grows
            CloudListen(Track("a"), 9_000L, 10_000L)      // a new listen of the same song
        )
        val plan = CloudListens.plan(incoming, existing)
        assertEquals(listOf(9_000L), plan.inserts.map { it.timestamp })
        assertEquals(1, plan.grows.size)
        assertEquals("b", plan.grows[0].track.id)
        assertEquals(500_000L, plan.grows[0].timestamp)
        assertEquals(58_000L, plan.grows[0].playTimeMs)
    }

    @Test
    fun `one listen arriving twice in the same pull is added once at its longest`() {
        val incoming = listOf(
            CloudListen(Track("a"), 1_000L, 5_000L),
            CloudListen(Track("a"), 1_800L, 40_000L),
            CloudListen(Track("a"), 2_500L, 20_000L)
        )
        val plan = CloudListens.plan(incoming, emptyList())
        assertEquals(1, plan.inserts.size)
        assertEquals(40_000L, plan.inserts[0].playTimeMs)
        assertTrue(plan.grows.isEmpty())
    }

    @Test
    fun `malformed listens are skipped`() {
        val parsed = CloudListens.parse(listOf(mapOf(
            "x" to "junk",
            "y" to mapOf("t" to "", "ts" to 1L, "ms" to 5L),
            "z" to mapOf("t" to "a", "ts" to 1L, "ms" to 0L),
            "w" to mapOf("t" to "a", "ms" to 5L),
            "ok" to mapOf("t" to "a", "ts" to 1L, "ms" to 5L)
        )))
        assertEquals(1, parsed.size)
        assertEquals("Unknown Track", parsed[0].track.title)
    }
}
