package com.auralis.music.windows.player

import androidx.compose.ui.graphics.Color
import com.auralis.music.desktop.library.TrackJson
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.DefaultArtworkPalette
import com.auralis.music.windows.settings.DesktopArtworkPaletteStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class DesktopPlaybackSessionTest {
    private fun tempDir(): File {
        val dir = File.createTempFile("auralis-session-test", "").apply { delete(); mkdirs() }
        dir.deleteOnExit()
        return dir
    }

    private fun sampleTrack(id: String = "test1", title: String = "Borderline", artist: String = "Tame Impala") =
        Track(id = id, title = title, artist = artist, thumbnail = "https://artwork.url/$id.jpg", duration = 237L)

    @Test
    fun paletteEncodingAndDecodingPreservesColors() {
        val original = ArtworkPalette(
            primary = Color(0xFF6B2D5C),
            secondary = Color(0xFF1E3F66),
            tertiary = Color(0xFFE07A5F),
            seedColor = Color(0xFF6B2D5C),
            isMonochrome = false,
            glowColors = listOf(Color(0xFF6B2D5C), Color(0xFF1E3F66))
        )

        val json = DesktopPlaybackSessionStore.encodePalette(original)
        val decoded = DesktopPlaybackSessionStore.decodePalette(json)

        assertEquals(original.primary, decoded.primary)
        assertEquals(original.secondary, decoded.secondary)
        assertEquals(original.tertiary, decoded.tertiary)
        assertEquals(original.seedColor, decoded.seedColor)
        assertEquals(original.isMonochrome, decoded.isMonochrome)
        assertEquals(original.glowColors.size, decoded.glowColors.size)
    }

    @Test
    fun sessionSavesAndRestoresTrackAndPalette() {
        val dir = tempDir()
        DesktopPlaybackSessionStore.init(dir)

        val track = sampleTrack()
        val palette = ArtworkPalette(
            primary = Color(0xFF5A2A75),
            secondary = Color(0xFF2A5A75),
            tertiary = Color(0xFF755A2A)
        )

        DesktopPlaybackSessionStore.save(
            track = track,
            queue = listOf(track),
            queueIndex = 0,
            artworkUrl = track.thumbnail,
            palette = palette
        )

        val restored = DesktopPlaybackSessionStore.currentSession()
        assertNotNull(restored)
        assertEquals("test1", restored.track.id)
        assertEquals("Borderline", restored.track.title)
        assertEquals("Tame Impala", restored.track.artist)
        assertEquals(track.thumbnail, restored.artworkUrl)
        assertEquals(palette.primary, restored.palette?.primary)
    }

    @Test
    fun fallbackToHistoryWhenNoSessionExists() {
        val dir = tempDir()
        val historyFile = File(dir, "history.json")
        val track = sampleTrack("hist1", "Let Down", "Radiohead")

        val historyJson = JSONObject().apply {
            put("version", 1)
            put("history", JSONArray().apply {
                put(JSONObject().apply {
                    put("playedAt", System.currentTimeMillis())
                    put("track", TrackJson.encode(track))
                })
            })
        }
        historyFile.writeText(historyJson.toString())

        DesktopPlaybackSessionStore.init(dir)
        val session = DesktopPlaybackSessionStore.currentSession()

        assertNotNull(session)
        assertEquals("hist1", session.track.id)
        assertEquals("Let Down", session.track.title)
        assertEquals("Radiohead", session.track.artist)
    }

    @Test
    fun paletteStoreCachesAndSuppliesThemeImmediately() {
        val dir = tempDir()
        DesktopPlaybackSessionStore.init(dir)

        val track = sampleTrack("art1", "Currents", "Tame Impala")
        val palette = ArtworkPalette(Color(0xFF334455), Color(0xFF112233), Color(0xFF556677))

        DesktopPlaybackSessionStore.save(track, artworkUrl = track.thumbnail, palette = palette)
        DesktopArtworkPaletteStore.put(track.thumbnail, palette)

        val retrieved = DesktopArtworkPaletteStore.get(track.thumbnail)
        assertNotNull(retrieved)
        assertEquals(Color(0xFF334455), retrieved.primary)

        val last = DesktopArtworkPaletteStore.lastPalette()
        assertNotNull(last)
        assertEquals(Color(0xFF334455), last.primary)
    }
}
