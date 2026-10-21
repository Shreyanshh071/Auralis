package com.auralis.music.windows.dev

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.player.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/** Isolated visual previews of the production tabs; no playback, network or user settings. */
fun main() {
    val out = File("windows/build/screens/player-tabs").apply { mkdirs() }
    for (width in listOf(400,900)) for (panel in PlayerPanel.entries) {
        val scene = ImageComposeScene(width,540) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF34140F),Color(0xFF130C10))))
                    .padding(top=24.dp)) {
                    PlayerPanelTabs(panel,{})
                    Spacer(Modifier.height(12.dp))
                    if (panel == PlayerPanel.LYRICS) Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=110.dp),
                        verticalArrangement=Arrangement.spacedBy(22.dp)) {
                        Text("Let the music fill the room",color=Color.White,fontSize=32.sp,fontWeight=FontWeight.Bold)
                        Text("And carry us through the night",color=Color.White.copy(alpha=.36f),fontSize=32.sp,fontWeight=FontWeight.Bold)
                    } else QueueList(DesktopPlaybackController.Queue(listOf(
                        Track("preview-one","Evening light","Preview artist"),
                        Track("preview-two","After hours","Preview artist"),
                        Track("preview-three","The long way home","Preview artist")),0),true,{},{},{},{},Modifier.padding(horizontal=16.dp))
                }
            }
        }
        try {
            repeat(16) { scene.render(it * 20_000_000L).close() }
            scene.render(400_000_000L).use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use {
                File(out,"${panel.name.lowercase()}-$width.png").writeBytes(it.bytes)
            } }
            println("Rendered ${panel.name} at $width px")
        } finally { scene.close() }
    }
}
