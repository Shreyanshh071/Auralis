package com.auralis.music.windows.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.auralis.music.data.connect.ConnectDevice
import com.auralis.music.data.connect.ConnectDeviceRow
import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.data.connect.ConnectOutput
import com.auralis.music.data.connect.ConnectPlatform
import com.auralis.music.data.connect.ConnectRowState
import com.auralis.music.data.connect.ConnectUi
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectOutputRowRenderTest {
    private val me = ConnectDevice("pc", "PC", platform = ConnectPlatform.WINDOWS, type = ConnectDeviceType.COMPUTER)
    private val phone = ConnectOutput("phone", "Pixel 8", ConnectDeviceType.PHONE, isThis = false, active = true, playing = true)
    private fun ui(output: ConnectOutput?, isOutput: Boolean = false) = ConnectUi(enabled = true, me = me, output = output,
        devices = listOf(ConnectDeviceRow(me, "PC", isThis = true, isOutput = isOutput, state = ConnectRowState.IDLE)))

    private fun brightPixels(bytes: ByteArray): Int {
        val image = javax.imageio.ImageIO.read(bytes.inputStream())
        var count = 0
        for (x in 0 until image.width) for (y in 0 until image.height) if (Color(image.getRGB(x, y)).red > 0.5f) count++
        return count
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(ui: ConnectUi, width: Int, tap: Offset? = null, onPlayHere: (String) -> Unit = {}): ByteArray {
        val scene = ImageComposeScene(width + 24, 80, Density(1.5f)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF15171C)).padding(12.dp)) {
                Column(Modifier.width((width / 1.5f - 16).dp)) { ConnectOutputRow(ui, true, true, {}, onPlayHere) }
            }
        }
        try {
            val image = scene.render()
            if (tap != null) {
                scene.sendPointerEvent(PointerEventType.Press, tap)
                scene.sendPointerEvent(PointerEventType.Release, tap)
                scene.render()
            }
            return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }

    @Test fun anotherDevicesAudioShowsWhereItPlaysAndTheWayBack() {
        System.getProperty("auralis.renderDir")?.let { dir ->
            render(ui(phone), 420).let { File(dir, "connect-remote.png").writeBytes(it) }
            render(ui(phone.copy(playing = false)), 420).let { File(dir, "connect-paused.png").writeBytes(it) }
            render(ui(phone), 330).let { File(dir, "connect-narrow.png").writeBytes(it) }
        }
        val remote = brightPixels(render(ui(phone), 420))
        assertTrue(remote > 50, "The row must draw text")
    }

    @Test fun thisDevicesOwnAudioShowsNothingToSwitch() {
        assertEquals(0, brightPixels(render(ui(phone.copy(isThis = true), isOutput = true), 420)))
        assertEquals(0, brightPixels(render(ui(null), 420)))
    }

    @Test fun narrowBarsKeepTheWayBackVisible() {
        var requested: String? = null
        // The switch sits after the label; a long name must shrink, not push it out of the bar.
        val long = phone.copy(label = "A very long device name that cannot possibly fit in the bar at all")
        render(ui(long), 330, tap = Offset(286f, 30f)) { requested = it }
        assertEquals("pc", requested)
    }

    @Test fun nothingOffersTheSwitchWhileItIsAlreadyHappening() {
        val moving = ui(phone).copy(transfer = com.auralis.music.data.connect.ConnectTransferUi("pc", "PC", failed = false))
        assertFalse(moving.canPlayHere)
    }
}
