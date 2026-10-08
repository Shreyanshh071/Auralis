package com.auralis.music.windows

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.auralis.music.util.Log
import javax.imageio.ImageIO

private const val TAG = "AuralisWindows"

fun main() {
    Log.i(TAG, "Starting Auralis for Windows")
    val icon = Thread.currentThread().contextClassLoader.getResourceAsStream("auralis_logo.png")
        ?.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Auralis",
            icon = icon,
            state = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            AuralisDesktopApp()
        }
    }
}
