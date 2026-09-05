package com.auralis.music

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.LyricsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class Test2AudioStreamAuditTest {

    companion object {
        private const val TAG = "TEST2_STREAM_AUDIT"
    }

    private fun loadAssetString(name: String): String {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }
    }

    @Test
    fun testResolveAndAuditPlaybackStreams() = runBlocking {
        Log.e(TAG, "==================================================================")
        Log.e(TAG, ">>> TEST 2: RESOLVING EXACT AUDIO STREAMS PLAYED BY AURALIS <<<")
        Log.e(TAG, "==================================================================")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AudioStreamResolver.init(context)

        val tracks = listOf(
            Triple("Radiohead - Creep", "XFkzRNyygfk", "creep_raw.ttml"),
            Triple("Ravyn Lenae - Love Me Not", "HfpR4tAmI7E", "love_me_not.ttml"),
            Triple("The Weeknd - Blinding Lights", "4NRXx6U8ABQ", "blinding_lights.ttml")
        )

        for ((songName, videoId, ttmlAsset) in tracks) {
            Log.e(TAG, "\n--------------------------------------------------------------")
            Log.e(TAG, "Resolving Stream for: $songName (YouTube ID: $videoId)")
            val streamUrl = AudioStreamResolver.resolveAudioStream(videoId, title = songName, context = context)
            Log.e(TAG, "Resolved Stream URL for $songName: ${streamUrl?.take(100)}...")

            if (!streamUrl.isNullOrBlank()) {
                val itag = Regex("itag=([0-9]+)").find(streamUrl)?.groupValues?.get(1) ?: "unknown"
                val mime = Regex("mime=([^&]+)").find(streamUrl)?.groupValues?.get(1)?.replace("%2F", "/") ?: "unknown"
                Log.e(TAG, "Stream Metadata: itag=$itag, mime=$mime")

                // Download first 1MB of audio to verify header and length
                val destFile = File(context.cacheDir, "${videoId}_sample.audio")
                try {
                    val conn = URL(streamUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.connect()
                    val contentLength = conn.contentLengthLong
                    Log.e(TAG, "HTTP Content-Length: $contentLength bytes (~${contentLength / 1024 / 1024} MB)")

                    val inStream = conn.inputStream
                    val outStream = FileOutputStream(destFile)
                    val buffer = ByteArray(8192)
                    var totalRead = 0
                    while (totalRead < 5 * 1024 * 1024) { // download up to 5MB
                        val r = inStream.read(buffer)
                        if (r <= 0) break
                        outStream.write(buffer, 0, r)
                        totalRead += r
                    }
                    inStream.close()
                    outStream.close()
                    Log.e(TAG, "Saved ${destFile.length()} bytes to ${destFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Download error: ${e.message}")
                }
            } else {
                Log.e(TAG, "Stream resolution returned NULL for $videoId!")
            }

            // Inspect TTML
            val ttmlContent = loadAssetString(ttmlAsset)
            val parsed = TtmlParser.parse(ttmlContent, LyricsProvider.BETTER_LYRICS)
            val durMatch = Regex("dur=\"([^\"]+)\"").find(ttmlContent)?.groupValues?.get(1)
            val leadMatch = Regex("leadingSilence=\"([^\"]+)\"").find(ttmlContent)?.groupValues?.get(1)
            Log.e(TAG, "TTML Header: body dur=$durMatch, leadingSilence=$leadMatch | Lines count=${parsed.lines.size}")
        }
    }
}
