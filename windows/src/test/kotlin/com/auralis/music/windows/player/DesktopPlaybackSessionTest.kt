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

    @Test
    fun newTrackDoesNotInheritPreviousTracksPalette() {
        val dir = tempDir()
        DesktopPlaybackSessionStore.init(dir)

        val track1 = sampleTrack("track1", "Song 1", "Artist 1")
        val palette1 = ArtworkPalette(Color(0xFFFF8A80), Color(0xFFFF8A80), Color(0xFFFF8A80))
        DesktopPlaybackSessionStore.save(track1, artworkUrl = track1.thumbnail, palette = palette1)

        val track2 = sampleTrack("track2", "Song 2", "Artist 2")
        // Main.kt saves new track without palette on queue change
        DesktopPlaybackSessionStore.save(track2, artworkUrl = track2.thumbnail)

        val session = DesktopPlaybackSessionStore.currentSession()
        assertNotNull(session)
        assertEquals("track2", session.track.id)
        assertNull(session.palette, "New track must not inherit previous track's palette")
        assertNull(DesktopArtworkPaletteStore.get(track2.thumbnail), "New track's URL must not return previous track's palette")
    }

    @Test
    fun sameTrackPreservesPaletteOnQueueChange() {
        val dir = tempDir()
        DesktopPlaybackSessionStore.init(dir)

        val track = sampleTrack("track1", "Song 1", "Artist 1")
        val palette = ArtworkPalette(Color(0xFF2196F3), Color(0xFF1976D2), Color(0xFF0D47A1))
        DesktopPlaybackSessionStore.save(track, artworkUrl = track.thumbnail, palette = palette)

        // Queue update for the same track
        DesktopPlaybackSessionStore.save(track, queue = listOf(track, sampleTrack("track2")), queueIndex = 0)

        val session = DesktopPlaybackSessionStore.currentSession()
        assertNotNull(session)
        assertEquals("track1", session.track.id)
        assertNotNull(session.palette)
        assertEquals(Color(0xFF2196F3), session.palette?.primary)
    }

    @Test
    fun loadAndExtractPaletteExtractsDistinctColorsFromFiles() {
        val dir = tempDir()
        val redFile = File(dir, "red.png")
        val blueFile = File(dir, "blue.png")

        val redImg = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        redImg.createGraphics().apply { color = java.awt.Color.RED; fillRect(0, 0, 100, 100); dispose() }
        javax.imageio.ImageIO.write(redImg, "png", redFile)

        val blueImg = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
        blueImg.createGraphics().apply { color = java.awt.Color.BLUE; fillRect(0, 0, 100, 100); dispose() }
        javax.imageio.ImageIO.write(blueImg, "png", blueFile)

        val redPalette = com.auralis.music.windows.settings.loadAndExtractPalette(redFile.absolutePath)
        val bluePalette = com.auralis.music.windows.settings.loadAndExtractPalette(blueFile.absolutePath)

        assertFalse(redPalette.isDefault)
        assertFalse(bluePalette.isDefault)
        assertNotEquals(redPalette.primary, bluePalette.primary)
    }
}
