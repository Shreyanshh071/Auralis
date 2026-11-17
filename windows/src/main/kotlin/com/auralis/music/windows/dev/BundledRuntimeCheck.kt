package com.auralis.music.windows.dev

import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.windows.profile.YouTubeBrowserSignIn
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Packaged-launcher check: no accounts, no app preferences, no installed VLC/browser, no audible audio. */
internal fun verifyBundledRuntime(): Int = runCatching {
    check(System.getProperty("os.name").orEmpty().lowercase().contains("mac"))
    System.setProperty("java.awt.headless", "true")
    val sample = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
    sample.createGraphics().apply {
        font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12)
        drawString("Auralis", 0, 6)
        dispose()
    }
    java.awt.image.ColorConvertOp(java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY), null)
        .filter(sample, null)
    java.io.ByteArrayOutputStream().use { out ->
        check(javax.imageio.ImageIO.write(sample, "jpg", out) && out.size() > 0) { "Bundled JPEG writer unavailable" }
    }
    System.loadLibrary("splashscreen")
    println("PASS: bundled JVM AWT, fontmanager, color management, JPEG and splashscreen libraries load")
    val resources = File(checkNotNull(System.getProperty("compose.application.resources.dir"))).canonicalFile
    val vlc = File(resources, "vlc")
    val audio = checkNotNull(VlcAudioPlayer.createOrNull(vlc)) { "Bundled libVLC failed to load" }
    check(VlcAudioPlayer.loadedFrom == "bundled (${vlc.canonicalPath})")
    val temp = Files.createTempDirectory("auralis-runtime-check-").toFile()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    fun await(label: String, timeoutMs: Long = 8000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        check(condition()) { "Runtime check failed: $label" }
    }
    try {
        audio.setVolume(0f)
        val wave = ByteArray(44 + 48_000 * 2 * 3)
        ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(wave.size - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(48_000); putInt(96_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(wave.size - 44)
        }
        val local = File(temp, "silence.wav").apply { writeBytes(wave) }
        audio.play(local.absolutePath)
        await("PCM playback") { audio.state.value == VlcAudioPlayer.State.PLAYING && audio.positionMs.value > 100 }
        audio.pause()
        await("pause") { audio.state.value == VlcAudioPlayer.State.PAUSED }
        audio.seekTo(1000)
        audio.resume()
        await("seek and resume") { audio.state.value == VlcAudioPlayer.State.PLAYING && audio.positionMs.value >= 1000 }
        println("PASS: bundled audio engine, PCM, pause, seeking and resume")
        for ((file, mime) in listOf("silence.webm" to "audio/webm", "silence.m4a" to "audio/mp4", "silence.mp4" to "video/mp4")) {
            val bytes = checkNotNull(object {}.javaClass.getResourceAsStream("/runtime-check/$file")).use { it.readBytes() }
            server.createContext("/$file") { exchange ->
                exchange.responseHeaders.add("Content-Type", mime)
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(exchange.requestHeaders.getFirst("Range").orEmpty())
                val start = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val end = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                if (start > end || start > bytes.lastIndex) {
                    exchange.sendResponseHeaders(416, -1); exchange.close()
                } else {
                    if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (end - start + 1).toLong())
                    exchange.responseBody.use { it.write(bytes, start, end - start + 1) }
                }
            }
        }
        server.start()
        for (file in listOf("silence.webm", "silence.m4a", "silence.mp4")) {
            audio.play("http://127.0.0.1:${server.address.port}/$file")
            await("HTTP $file decoding") { audio.state.value == VlcAudioPlayer.State.PLAYING && audio.positionMs.value > 100 && audio.lengthMs.value > 0 }
            println("PASS: HTTP decoding $file")
        }
        val images = Native.load("System.B", DyldImages::class.java)
        val loadedImages = (0 until images._dyld_image_count()).mapNotNull {
            images._dyld_get_image_name(it)?.getString(0)
        }
        val externalImages = loadedImages.filter { it.startsWith("/opt/homebrew/") || it.startsWith("/usr/local/") }
        check(externalImages.isEmpty()) { "Bundled runtime loaded external host libraries: $externalImages" }
        val vlcImages = loadedImages.filter { it.contains("libvlc") || it.endsWith("_plugin.dylib") }
        check(vlcImages.size >= 3 && vlcImages.all { File(it).canonicalPath.startsWith(vlc.canonicalPath + "/") }) {
            "Playback used libraries outside the app: $vlcImages"
        }
        println("PASS: all ${vlcImages.size} loaded VLC libraries/plugins are inside this app")
        val browser = checkNotNull(YouTubeBrowserSignIn.bundledMacBrowser(resources, development = false))
        val profile = File(temp, "browser")
        val log = File(temp, "browser.log")
        val process = ProcessBuilder(browser.exe.absolutePath, "--headless=new", "--remote-debugging-port=0",
            "--user-data-dir=${profile.absolutePath}", "--no-first-run", "--no-default-browser-check",
            "--disable-background-networking", "about:blank").redirectErrorStream(true).redirectOutput(log).start()
        try {
            val portFile = File(profile, "DevToolsActivePort")
            await("bundled browser startup: ${log.absolutePath}", 20_000) { portFile.isFile || !process.isAlive }
            check(process.isAlive && portFile.isFile) { "Bundled browser failed: ${log.readText().takeLast(4000)}" }
            val port = portFile.readLines().first().toInt()
            val connection = URI("http://127.0.0.1:$port/json/version").toURL().openConnection().apply {
                connectTimeout = 3000; readTimeout = 3000
            }
            val version = JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
            check(version.getString("webSocketDebuggerUrl").startsWith("ws://127.0.0.1:"))
            println("PASS: private bundled sign-in browser starts with sandbox enabled and responds over localhost")
        } finally {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
        }
        check(!File(vlc, "plugins/plugins.dat").exists()) { "VLC modified its signed plugin directory" }
    } finally {
        audio.release()
        server.stop(0)
        temp.deleteRecursively()
    }
}.fold(onSuccess = { 0 }, onFailure = { it.printStackTrace(); 1 })

private interface DyldImages : Library {
    fun _dyld_image_count(): Int
    fun _dyld_get_image_name(index: Int): Pointer?
}
