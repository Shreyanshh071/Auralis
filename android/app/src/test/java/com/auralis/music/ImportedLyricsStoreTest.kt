package com.auralis.music

import com.auralis.music.data.local.ImportedLyricsStore
import com.auralis.music.domain.model.LyricsProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking
import io.mockk.mockk
import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.repository.LyricsRepositoryImpl

class ImportedLyricsStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val entry = ImportedLyricsStore.Entry("spotify:recording", "Song", "Artist", "song.ttml")
    private val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s"><span begin="1s" end="2s">Hello </span><span begin="2s" end="3s">world</span></p></div></body></tt>"""
    @Before fun setup() { ImportedLyricsStore.initDirectory(folder.root) }

    @Test fun survivesReinitializationAndPreservesWordTimingAndIdentity() {
        ImportedLyricsStore.save(entry, xml, listOf("youtube-id"))
        ImportedLyricsStore.initDirectory(folder.root)
        val saved = ImportedLyricsStore.read(entry.id)!!
        assertEquals(LyricsProvider.LOCAL, saved.provider)
        assertEquals("Song", saved.trackName)
        assertEquals(1000L, saved.lines.first().words!!.first().time)
        assertEquals(1000L, saved.lines.first().words!!.first().duration)
        assertNotNull(ImportedLyricsStore.read("youtube-id"))
        assertNull(ImportedLyricsStore.read("another-song"))
        assertEquals("song.ttml", ImportedLyricsStore.entries().single().filename)
    }

    @Test fun invalidReplacementPreservesExistingImport() {
        ImportedLyricsStore.save(entry, xml)
        assertThrows(IllegalArgumentException::class.java) { ImportedLyricsStore.save(entry, "<tt/>") }
        assertNotNull(ImportedLyricsStore.read(entry.id))
        assertThrows(IllegalArgumentException::class.java) { ImportedLyricsStore.save(entry, "<!DOCTYPE tt><tt/>") }
        assertNotNull(ImportedLyricsStore.read(entry.id))
    }

    @Test fun replacementAndRemovalUpdatePersistentStateAndAliases() {
        ImportedLyricsStore.save(entry, xml, listOf("youtube-id"))
        ImportedLyricsStore.save(entry.copy(filename = "new.ttml"), xml.replace("Hello", "Goodbye"))
        assertEquals(1, ImportedLyricsStore.entries().size)
        assertEquals("new.ttml", ImportedLyricsStore.entries().single().filename)
        assertTrue(ImportedLyricsStore.read(entry.id)!!.lines.first().text.contains("Goodbye"))
        assertNotNull(ImportedLyricsStore.read("youtube-id"))
        ImportedLyricsStore.remove(entry.id)
        ImportedLyricsStore.initDirectory(folder.root)
        assertNull(ImportedLyricsStore.read(entry.id))
        assertNull(ImportedLyricsStore.read("youtube-id"))
        assertTrue(ImportedLyricsStore.entries().isEmpty())
    }

    @Test fun importedLyricsWinOverForcedNetworkRefreshAndSpicyUpgrade() = runBlocking {
        ImportedLyricsStore.save(entry, xml, listOf("youtube-id"))
        // An unconfigured mock throws on any network call, so these must resolve locally.
        val repository = LyricsRepositoryImpl(mockk<LyricsClient>(), spicyEnabled = { true })
        val result = repository.getLyrics("Song", "Artist", 180, "youtube-id", forceRefresh = true)
        assertEquals(LyricsProvider.LOCAL, result!!.provider)
        val cached = repository.getCachedLyrics("Song", "Artist", 180, "youtube-id")
        assertEquals(LyricsProvider.LOCAL, cached!!.provider)
        val upgrade = repository.upgradeSpicy("Song", "Artist", 180, "youtube-id", null, null, 180000, null, null)
        assertEquals(LyricsProvider.LOCAL, upgrade!!.provider)
    }

    @Test fun interruptedReplacementRestoresLastSavedFile() {
        ImportedLyricsStore.save(entry, xml)
        val saved = folder.root.listFiles()!!.single { it.extension == "json" }
        assertTrue(saved.renameTo(java.io.File(saved.path + ".bak")))
        java.io.File(saved.path + ".new").writeText("interrupted write")
        ImportedLyricsStore.initDirectory(folder.root)
        assertEquals("song.ttml", ImportedLyricsStore.entries().single().filename)
        assertNotNull(ImportedLyricsStore.read(entry.id))
        ImportedLyricsStore.remove(entry.id)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
