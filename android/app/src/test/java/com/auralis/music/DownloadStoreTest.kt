package com.auralis.music

import com.auralis.music.data.download.DownloadStore
import com.auralis.music.domain.model.Track
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `unchecked cover remains hidden after restart even with a cached image`() {
        val store = DownloadStore(folder.root)
        store.load()
        audio(store, "one")
        val original = Track(id = "one", thumbnail = "https://example.com/cover.jpg")
        store.add(original)
        store.artworkFile("one").writeBytes(ByteArray(1200) { 7 })
        store.recordCoverChoice("one", false)
        val restored = DownloadStore(folder.root)
        val raw = restored.load().single()
        assertEquals(false, restored.downloadCoverChoices()["one"])
        assertEquals(original.thumbnail, raw.thumbnail)
        val visible = com.auralis.music.data.download.DownloadArtworkPolicy.display(raw, restored.downloadCoverChoices()[raw.id])
        assertEquals(com.auralis.music.data.download.DownloadArtworkPolicy.HIDDEN, visible.thumbnail)
        assertEquals(original.id, visible.id)
        assertEquals(original.title, visible.title)
        assertTrue(restored.artworkFile("one").exists())
        restored.recordCoverChoice("one", true)
        assertEquals(original.thumbnail, com.auralis.music.data.download.DownloadArtworkPolicy.display(raw, restored.downloadCoverChoices()[raw.id]).thumbnail)
    }

    @Test fun `cover and lyrics choices stay independent and are removed with the download`() {
        val store = DownloadStore(folder.root)
        store.load()
        audio(store, "one")
        store.add(Track(id = "one"))
        assertTrue(store.downloadCoverChoices().isEmpty())
        store.recordCoverChoice("one", false)
        store.recordLyricsChoice("one", true)
        assertEquals(mapOf("one" to false), store.downloadCoverChoices())
        assertEquals(mapOf("one" to true), store.downloadLyricsChoices())
        store.recordCoverChoice("one", true)
        store.recordLyricsChoice("one", false)
        assertEquals(mapOf("one" to true), store.downloadCoverChoices())
        assertEquals(mapOf("one" to false), store.downloadLyricsChoices())
        store.remove("one")
        assertTrue(store.downloadCoverChoices().isEmpty())
        assertTrue(store.downloadLyricsChoices().isEmpty())
        audio(store, "one")
        store.add(Track(id = "one"))
        assertTrue(store.downloadCoverChoices().isEmpty())
    }

    @Test fun `per song lyrics choices survive restart without removing cover previews`() {
        val store = DownloadStore(folder.root)
        store.load()
        listOf("one", "two").forEach { id ->
            audio(store, id)
            store.add(Track(id = id, thumbnail = "https://example.com/$id.jpg"))
        }
        val cover = store.artworkFile("one")
        val bytes = ByteArray(1200) { 9 }
        cover.writeBytes(bytes)
        store.recordLyricsChoice("one", false)
        store.recordLyricsChoice("two", true)
        val restored = DownloadStore(folder.root)
        val tracks = restored.load()
        assertEquals(mapOf("one" to false, "two" to true), restored.downloadLyricsChoices())
        assertEquals("https://example.com/one.jpg", tracks.first { it.id == "one" }.thumbnail)
        assertArrayEquals(bytes, cover.readBytes())
        assertNotNull(restored.downloadedFile("one"))
    }

    @Test fun `legacy downloads have unknown lyrics choice and removal clears recorded choices`() {
        val store = DownloadStore(folder.root)
        store.load()
        audio(store, "one")
        store.add(Track(id = "one"))
        assertFalse(store.downloadLyricsChoices().containsKey("one"))
        store.recordLyricsChoice("one", false)
        store.remove("one")
        assertTrue(store.downloadLyricsChoices().isEmpty())
        audio(store, "one")
        store.add(Track(id = "one"))
        assertFalse(store.downloadLyricsChoices().containsKey("one"))
        store.recordLyricsChoice("one", true)
        store.clear()
        val restored = DownloadStore(folder.root)
        restored.load()
        assertTrue(restored.downloadLyricsChoices().isEmpty())
    }

    private fun audio(store: DownloadStore, id: String) {
        store.audioFile(id).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(6000) { 1 }) }
    }

    @Test fun `concurrent commits preserve every track across restart`() {
        val store = DownloadStore(folder.root)
        store.load()
        val ids = (1..32).map { "track_$it" }
        ids.forEach { audio(store, it) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = ids.map { id -> pool.submit { start.await(); store.add(Track(id = id, title = id)) } }
            start.countDown()
            jobs.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val restored = DownloadStore(folder.root).load()
        assertEquals(ids.toSet(), restored.map { it.id }.toSet())
        assertEquals(32, JSONArray(File(folder.root, "downloaded_tracks_v1.json").readText()).length())
    }

    @Test fun `Spotify logical ID is saved detected and restored without changing identity`() {
        val store = DownloadStore(folder.root)
        store.load()
        val track = Track(id = "sp_abc123", title = "Song", artist = "Artist", duration = 200)
        audio(store, track.id)
        store.add(track)
        assertEquals("sp_abc123.m4a", store.downloadedFile(track.id)!!.name)
        val restored = DownloadStore(folder.root)
        assertEquals(listOf(track), restored.load())
        assertNotNull(restored.downloadedFile(track.id))
        assertNull(restored.downloadedFile("unrelated_youtube_alias"))
    }

    @Test fun `legacy metadata remains readable and Spotify file is not purged`() {
        val store = DownloadStore(folder.root)
        audio(store, "sp_legacy")
        File(folder.root, "downloaded_tracks_v1.json").writeText("""[{"id":"sp_legacy","title":"Legacy","artist":"Artist","duration":123}]""")
        assertEquals("sp_legacy", store.load().single().id)
        assertNotNull(store.downloadedFile("sp_legacy"))
    }

    @Test fun `metadata failure does not publish new track or lose previous commit`() {
        val store = DownloadStore(folder.root)
        store.load()
        audio(store, "one")
        store.add(Track(id = "one"))
        audio(store, "two")
        File(folder.root, "downloaded_tracks_v1.json.new").mkdir()
        assertThrows(Exception::class.java) { store.add(Track(id = "two")) }
        assertNull(store.downloadedFile("two"))
        assertNotNull(store.downloadedFile("one"))
        assertEquals(listOf("one"), DownloadStore(folder.root).load().map { it.id })
    }

    @Test fun `interrupted replacement restores last committed metadata`() {
        val store = DownloadStore(folder.root)
        store.load()
        audio(store, "one")
        store.add(Track(id = "one"))
        val metadata = File(folder.root, "downloaded_tracks_v1.json")
        metadata.copyTo(File(folder.root, "downloaded_tracks_v1.json.bak"))
        metadata.writeText("broken partial contents")
        val restored = DownloadStore(folder.root)
        assertEquals(listOf("one"), restored.load().map { it.id })
        assertNotNull(restored.downloadedFile("one"))
    }

    @Test fun `missing and tiny files cannot count as downloaded`() {
        val store = DownloadStore(folder.root)
        store.load()
        assertThrows(IllegalStateException::class.java) { store.add(Track(id = "missing")) }
        audio(store, "one")
        store.add(Track(id = "one"))
        store.audioFile("one").writeBytes(ByteArray(10))
        assertNull(store.downloadedFile("one"))
        assertTrue(DownloadStore(folder.root).load().isEmpty())
    }

    @Test fun `removal and clear are persisted without dropping unrelated records`() {
        val store = DownloadStore(folder.root)
        store.load()
        listOf("one", "two").forEach { audio(store, it); store.add(Track(id = it)) }
        store.remove("one")
        assertEquals(listOf("two"), DownloadStore(folder.root).load().map { it.id })
        store.clear()
        assertTrue(DownloadStore(folder.root).load().isEmpty())
    }
}
