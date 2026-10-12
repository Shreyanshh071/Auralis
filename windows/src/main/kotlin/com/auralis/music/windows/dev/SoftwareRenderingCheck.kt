package com.auralis.music.windows.dev

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.auralis.music.ui.theme.DefaultArtworkPalette
import com.auralis.music.windows.*
import com.auralis.music.windows.player.*
import com.sun.jna.platform.win32.Kernel32
import kotlinx.coroutines.delay
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Own disposable window, synthetic local cover, no account or app data. */
fun main() {
    val requested = GraphicsApi.valueOf(System.getProperty("skiko.renderApi"))
    check(System.getProperty("sun.java2d.d3d") == "false")
    val cover = File("build/software-render-cover.png")
    val image = java.awt.image.BufferedImage(128,128,java.awt.image.BufferedImage.TYPE_INT_RGB)
    image.createGraphics().apply {
        paint = java.awt.GradientPaint(0f,0f,java.awt.Color.BLUE,128f,128f,java.awt.Color.MAGENTA)
        fillRect(0,0,128,128); dispose()
    }
    javax.imageio.ImageIO.write(image,"png",cover)
    val frames = AtomicInteger()
    var passed = false
    application(exitProcessOnExit = false) {
        Window(onCloseRequest = ::exitApplication, title = "Auralis software glass check",
            state = rememberWindowState(size = DpSize(1280.dp,800.dp))) {
            MaterialTheme {
                var pulse by remember { mutableFloatStateOf(0f) }
                LaunchedEffect(Unit) { while(true) withFrameNanos { pulse = (it % 1_000_000_000L) / 1_000_000_000f } }
                if (System.getProperty("auralis.check.basic") == "true") {
                    var phase by remember { mutableFloatStateOf(0f) }
                    LaunchedEffect(Unit) { while(true) withFrameNanos { phase = (it % 1_000_000_000L) / 1_000_000_000f } }
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawRect(androidx.compose.ui.graphics.Color.DarkGray)
                        drawCircle(androidx.compose.ui.graphics.Color.Cyan, 30f,
                            androidx.compose.ui.geometry.Offset(size.width * phase, size.height / 2))
                    }
                } else if (System.getProperty("auralis.check.part") == "aura") {
                    ArtworkAura(DefaultArtworkPalette, Modifier.fillMaxSize(), cover.absolutePath)
                } else if (System.getProperty("auralis.check.part") == "blur") {
                    ArtworkAppBackground(cover.absolutePath, DefaultArtworkPalette) { }
                } else {
                val source = rememberPopupGlassSource()
                CompositionLocalProvider(LocalPopupGlassSource provides source) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().recordPopupGlass(source)) {
                            ArtworkAppBackground(cover.absolutePath, DefaultArtworkPalette) {
                                ArtworkAura(DefaultArtworkPalette, Modifier.fillMaxSize(), cover.absolutePath)
                            }
                        }
                        LiquidPopupSurface(Modifier.align(Alignment.Center).size(340.dp,220.dp)) {
                            Text("Auralis liquid glass", Modifier.padding(24.dp))
                        }
                    }
                }
                }
                androidx.compose.foundation.Canvas(Modifier.size(80.dp)) {
                    drawCircle(androidx.compose.ui.graphics.Color.White, 3f, androidx.compose.ui.geometry.Offset(10f + pulse * 50f,10f))
                }
            }
            LaunchedEffect(Unit) {
                try {
                    delay(3000)
                    fun find(component: java.awt.Component): SkiaLayer? =
                        if (component is SkiaLayer) component else
                            (component as? java.awt.Container)?.components?.firstNotNullOfOrNull { find(it) }
                    val layer = checkNotNull(find(window))
                    val delegate = checkNotNull(layer.renderDelegate)
                    frames.set(0)
                    layer.renderDelegate = object : SkikoRenderDelegate {
                        override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
                            frames.incrementAndGet(); delegate.onRender(canvas,width,height,nanoTime)
                        }
                    }
                    delay(5000)
                    check(window.renderApi == requested) { "Unexpected backend: ${window.renderApi}" }
                    check(Kernel32.INSTANCE.GetModuleHandle("nvspcap64.dll") == null) { "NVIDIA capture loaded" }
                    check(frames.get() >= 50) { "Glass animation too slow: ${frames.get()} frames in 5 seconds" }
                    println("PASS: $requested; NVIDIA capture absent; part=${System.getProperty("auralis.check.part", if(System.getProperty("auralis.check.basic")=="true") "basic" else "glass")}; ${frames.get()} frames in 5 seconds")
                    passed = true
                } finally { exitApplication() }
            }
        }
    }
    cover.delete()
    check(passed) { "Software rendering diagnostic failed" }
}
