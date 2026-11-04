package com.auralis.music.windows.immersive

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.lyrics.DesktopLyricsRepository
import com.auralis.music.desktop.lyrics.LyricsDiskCache
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.DefaultArtworkPalette
import com.auralis.music.ui.theme.LocalAppearanceSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

/** Every view at every display shape, as PNGs in windows/build/immersive-renders for a look. */
class ImmersiveScreenRenderTest {
    @Test fun everyViewFillsEveryDisplayShape() = runBlocking {
        val folder = Files.createTempDirectory("auralis-immersive").toFile()
        val out = File("build/immersive-renders").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vlc = assertNotNull(VlcAudioPlayer.createOrNull(File(System.getenv("LOCALAPPDATA"), "Auralis/app/resources/vlc")))
        val playback = DesktopPlaybackController(vlc, scope, offlineFile = { File(folder, "unused") }, resolveStream = { null })
        val synced = LyricsData(SyncType.LINE_SYNC, listOf(LyricLine(1_000, "Let the music fill the room"),
            LyricLine(5_000, "And carry us through the night")), provider = LyricsProvider.BETTER_LYRICS)
        try {
            val disk = LyricsDiskCache(folder)
            disk.write("render", synced)
            val repository = DesktopLyricsRepository(disk, spicyEnabled = { false }, providerLookup = { _, _ -> synced })
            val lyrics = DesktopLyricsController(playback, repository, scope)
            playback.restoreQueue(listOf(Track("render", "Blinding Lights", "The Weeknd", duration = 200),
                Track("next", "Save Your Tears", "The Weeknd", duration = 215)))
            withTimeout(5_000) { lyrics.state.first { it.lyrics != null } }
            val stores = DesktopDataStores(File(folder, "settings"))
            for ((name, width, height) in listOf(Triple("standard", 960, 540), Triple("portrait", 540, 960), Triple("ultrawide", 1720, 720))) {
                for (view in ImmersiveView.entries) {
                    val display = ImmersiveDisplay("0,0", Rectangle(0, 0, width, height))
                    val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                        CompositionLocalProvider(LocalSettingsStores provides stores, LocalAppearanceSettingsStore provides stores.appearance) {
                            MaterialTheme(colorScheme = darkColorScheme()) {
                                ImmersiveScreen(view, {}, playback, lyrics, null, DefaultArtworkPalette, "Gradient", inlineLyric = true,
                                    displays = listOf(display), shownDisplays = listOf(display), thisDisplay = display,
                                    onToggleDisplay = {}, onLeave = {})
                            }
                        }
                    }
                    try {
                        repeat(12) { scene.render(it * 100_000_000L).close() }
                        val bytes = scene.render(1_500_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
                        File(out, "${view.name.lowercase()}-$name.png").writeBytes(bytes)
                        val image = ImageIO.read(bytes.inputStream())
                        val bright = (0 until width step 2).sumOf { x -> (0 until height step 2).count { y ->
                            image.getRGB(x, y) and 0xff > 200 && image.getRGB(x, y) shr 8 and 0xff > 200 } }
                        assertTrue(bright > 50, "$view at $name drew no text")
                    } finally { scene.close() }
                }
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            playback.release()
        }
    }
}
