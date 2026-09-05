package com.auralis.music

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EndToEndTimingAuditTest {

    companion object {
        private const val TAG = "TIMING_CHAIN_AUDIT"
    }

    private fun carriedPositionMs(
        rawMs: Long,
        anchorRawMs: Long,
        anchorWallMs: Long,
        nowWallMs: Long,
        speed: Float,
        isPlaying: Boolean
    ): Long {
        if (!isPlaying || speed <= 0f) return rawMs
        val elapsedWallMs = (nowWallMs - anchorWallMs).coerceAtLeast(0L)
        val extrapolatedMs = anchorRawMs + (elapsedWallMs * speed).toLong()
        return extrapolatedMs.coerceIn(rawMs, rawMs + 1000L)
    }

    private fun loadAssetString(name: String): String {
        return InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }
    }

    private fun copyAssetToFile(name: String, destFile: File) {
        if (!destFile.exists() || destFile.length() == 0L) {
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun getFieldDeep(target: Any, fieldName: String): Any? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null) {
            try {
                val f = clazz.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(target)
            } catch (e: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        return null
    }

    // ── 1. HARDWARE & AUDIOTRACK / HAL LATENCY INVESTIGATION ──────────────────

    @Test
    fun testMeasureDeviceAudioOutputLatency() {
        Log.e(TAG, "================================================================")
        Log.e(TAG, ">>> STEP 1: MEASURING PHYSICAL AUDIO OUTPUT & HAL LATENCY <<<")
        Log.e(TAG, "================================================================")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val framesPerBufferStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val sampleRate = sampleRateStr?.toIntOrNull() ?: 48000
        val framesPerBuffer = framesPerBufferStr?.toIntOrNull() ?: 256
        val bufferDurationMs = (framesPerBuffer.toDouble() / sampleRate.toDouble()) * 1000.0

        Log.e(TAG, "AudioManager outputSampleRate: $sampleRate Hz")
        Log.e(TAG, "AudioManager framesPerBuffer: $framesPerBuffer frames (~${String.format("%.2f", bufferDurationMs)} ms)")

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        Log.e(TAG, "Connected Output Devices (${devices.size}):")
        for (dev in devices) {
            val typeName = when (dev.type) {
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
                android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
                else -> "TYPE_${dev.type}"
            }
            Log.e(TAG, "  - Device: ${dev.productName} | Type: $typeName")
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        var halLatencyMs = -1
        try {
            val getLatencyMethod = AudioTrack::class.java.getMethod("getLatency")
            halLatencyMs = getLatencyMethod.invoke(audioTrack) as Int
            Log.e(TAG, "AudioTrack.getLatency() reported HAL/driver latency: ${halLatencyMs} ms")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack.getLatency() reflection failed: ${e.message}")
        }

        val bufferSizeFrames = audioTrack.bufferSizeInFrames
        val trackBufferMs = (bufferSizeFrames.toDouble() / sampleRate.toDouble()) * 1000.0
        Log.e(TAG, "AudioTrack bufferSizeInFrames: $bufferSizeFrames frames (~${String.format("%.2f", trackBufferMs)} ms)")

        audioTrack.release()

        Log.e(TAG, "SUMMARY AUDIOTRACK LATENCY: HAL=${halLatencyMs}ms, Buffer=${String.format("%.2f", trackBufferMs)}ms")
    }

    // ── 1B. EXOPLAYER AUDIOSINK & POSITION TRACKER INTERNALS AUDIT ────────────

    @Test
    fun testInspectExoPlayerAudioSinkInternals() {
        Log.e(TAG, "================================================================")
        Log.e(TAG, ">>> STEP 1B: EXOPLAYER AUDIOSINK & POSITION TRACKER INTERNALS <<<")
        Log.e(TAG, "================================================================")

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val audioFile = File(targetContext.cacheDir, "creep_audio.webm")
        copyAssetToFile("creep_audio.webm", audioFile)

        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(targetContext).build()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.seekTo(20000L)
            player.play()

            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try {
                    val rawPosMs = player.currentPosition

                    // Query ExoPlayer renderers
                    val renderersField = getFieldDeep(player, "renderers") as? Array<*>
                    Log.e(TAG, "ExoPlayer Renderers Count: ${renderersField?.size ?: 0}")

                    var audioSink: Any? = null
                    var audioTrackPositionTracker: Any? = null
                    var latencyUs: Long = -1L

                    if (renderersField != null) {
                        for (renderer in renderersField) {
                            if (renderer != null && renderer.javaClass.simpleName.contains("Audio")) {
                                Log.e(TAG, "Found Audio Renderer: ${renderer.javaClass.name}")
                                audioSink = getFieldDeep(renderer, "audioSink")
                                Log.e(TAG, "AudioSink: ${audioSink?.javaClass?.name}")
                                if (audioSink != null) {
                                    audioTrackPositionTracker = getFieldDeep(audioSink, "audioTrackPositionTracker")
                                    Log.e(TAG, "AudioTrackPositionTracker: ${audioTrackPositionTracker?.javaClass?.name}")
                                    if (audioTrackPositionTracker != null) {
                                        val latVal = getFieldDeep(audioTrackPositionTracker, "latencyUs")
                                        if (latVal is Long) latencyUs = latVal
                                    }
                                }
                            }
                        }
                    }

                    Log.e(TAG, ">>> EXOPLAYER POSITION AUDIT RESULTS <<<")
                    Log.e(TAG, "Player currentPosition: $rawPosMs ms")
                    Log.e(TAG, "AudioTrackPositionTracker latencyUs: ${latencyUs} us (${latencyUs / 1000} ms)")
                    Log.e(TAG, "Does ExoPlayer currentPosition compensate for latency? (See latencyUs value)")

                } catch (e: Exception) {
                    Log.e(TAG, "Reflection error inspecting AudioSink", e)
                } finally {
                    player.stop()
                    player.release()
                    latch.countDown()
                }
            }, 1000)
        }

        assertTrue("AudioSink inspection must finish within 10s", latch.await(10, TimeUnit.SECONDS))
    }

    // ── 2. 25MS CONTINUOUS CORRELATION LOOP ON MOTOROLA EDGE 50 FUSION ────────

    @Test
    fun testEndToEndTimingCorrelationDuringPlayback() {
        Log.e(TAG, "================================================================")
        Log.e(TAG, ">>> STEP 2: 25MS CONTINUOUS CORRELATION LOOP DURING PLAYBACK <<<")
        Log.e(TAG, "================================================================")

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val audioFile = File(targetContext.cacheDir, "creep_audio.webm")
        copyAssetToFile("creep_audio.webm", audioFile)
        assertTrue("Audio file must exist in cache", audioFile.exists() && audioFile.length() > 0)

        val creepTtmlContent = loadAssetString("creep_raw.ttml")
        val lyrics = TtmlParser.parse(creepTtmlContent, LyricsProvider.BETTER_LYRICS)
        val lines = lyrics.lines

        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(targetContext).build()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(audioFile))
            player.setMediaItem(mediaItem)
            player.prepare()
            // Seek to 18500ms right before Line 1 ("When you were here before" at 19764ms)
            player.seekTo(18500L)
            player.play()

            val handler = Handler(Looper.getMainLooper())
            var step = 0
            val maxSteps = 300 // 300 * 25ms = 7.5s (18.5s to 26s)
            var anchorRawMs = player.currentPosition
            var anchorWallMs = System.currentTimeMillis()
            val sampleStartWall = anchorWallMs
            var prevActiveWord: LyricWord? = null

            val sampleRunnable = object : Runnable {
                override fun run() {
                    try {
                        if (step >= maxSteps) {
                            player.stop()
                            player.release()
                            latch.countDown()
                            return
                        }

                        val nowWallMs = System.currentTimeMillis()
                        val rawMs = player.currentPosition
                        val isPlaying = player.isPlaying

                        val carriedMs = carriedPositionMs(
                            rawMs = rawMs,
                            anchorRawMs = anchorRawMs,
                            anchorWallMs = anchorWallMs,
                            nowWallMs = nowWallMs,
                            speed = 1.0f,
                            isPlaying = isPlaying
                        )

                        if (rawMs != anchorRawMs) {
                            anchorRawMs = rawMs
                            anchorWallMs = nowWallMs
                        }

                        // Match active line & active word
                        val currentLine = lines.firstOrNull {
                            val lineEnd = it.wordTimingEndMs ?: (it.time + 4000L)
                            carriedMs >= it.time && carriedMs < lineEnd
                        }

                        val activeWord = currentLine?.words?.firstOrNull {
                            val dur = it.duration ?: 0L
                            carriedMs >= it.time && carriedMs < (it.time + dur)
                        }

                        val progress = if (activeWord != null) {
                            LyricsEngine.calculateWordProgress(activeWord, carriedMs, 0L)
                        } else -1f

                        val isDrawing = activeWord != null && progress in 0.0f..1.0f
                        val elapsedFromStart = nowWallMs - sampleStartWall
                        val driftMs = (carriedMs - rawMs)

                        val wordChanged = activeWord != prevActiveWord
                        prevActiveWord = activeWord

                        if (wordChanged || (step % 6 == 0 && activeWord != null)) {
                            Log.e(
                                TAG,
                                String.format(
                                    "tWall=+%5dms | ExoRaw=%6dms | CarriedClock=%6dms | Drift=%+3dms | Line='%s' | Word='%s' [provStart=%5d, provDur=%4d] | Progress=%.3f | Drawing=%s",
                                    elapsedFromStart,
                                    rawMs,
                                    carriedMs,
                                    driftMs,
                                    currentLine?.text?.take(18) ?: "---",
                                    activeWord?.word?.trim() ?: "---",
                                    activeWord?.time ?: 0L,
                                    activeWord?.duration ?: 0L,
                                    progress,
                                    if (isDrawing) "YES" else "NO"
                                )
                            )
                        }

                        step++
                        handler.postDelayed(this, 25)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in sampleRunnable", e)
                        player.stop()
                        player.release()
                        latch.countDown()
                    }
                }
            }

            handler.post(sampleRunnable)
        }

        assertTrue("Correlation loop must finish within 25s", latch.await(25, TimeUnit.SECONDS))
    }

    // ── 3. DETAILED AUDIT: CORRECT LINE VS BAD LINE (3 SONGS) ─────────────────

    @Test
    fun testAuditCorrectLineVsBadLineTiming() {
        Log.e(TAG, "================================================================")
        Log.e(TAG, ">>> STEP 3: COMPARING CORRECT LINE VS BAD LINE (3 SONGS) <<<")
        Log.e(TAG, "================================================================")

        // Song 1: Radiohead - Creep
        Log.e(TAG, "\n--- SONG 1: RADIOHEAD - CREEP ---")
        val creepTtml = loadAssetString("creep_raw.ttml")
        val creepLyrics = TtmlParser.parse(creepTtml, LyricsProvider.BETTER_LYRICS)
        auditSongLines("Creep", creepLyrics.lines, listOf(0, 1, 2, 5, 8))

        // Song 2: Ravyn Lenae - Love Me Not
        Log.e(TAG, "\n--- SONG 2: RAVYN LENAE - LOVE ME NOT ---")
        val lmnTtml = loadAssetString("love_me_not.ttml")
        val lmnLyrics = TtmlParser.parse(lmnTtml, LyricsProvider.BETTER_LYRICS)
        auditSongLines("Love Me Not", lmnLyrics.lines, (0 until lmnLyrics.lines.size.coerceAtMost(6)).toList())

        // Song 3: The Weeknd - Blinding Lights
        Log.e(TAG, "\n--- SONG 3: THE WEEKND - BLINDING LIGHTS ---")
        val blTtml = loadAssetString("blinding_lights.ttml")
        val blLyrics = TtmlParser.parse(blTtml, LyricsProvider.BETTER_LYRICS)
        auditSongLines("Blinding Lights", blLyrics.lines, (0 until blLyrics.lines.size).toList())
    }

    private fun auditSongLines(songName: String, lines: List<com.auralis.music.domain.model.LyricLine>, indicesToAudit: List<Int>) {
        for (idx in indicesToAudit) {
            if (idx >= lines.size) continue
            val line = lines[idx]
            val words = line.words ?: emptyList()
            val lineDur = if (words.isNotEmpty()) {
                val last = words.last()
                (last.time + (last.duration ?: 0L)) - words.first().time
            } else (line.wordTimingEndMs?.let { it - line.time } ?: 0L)

            val totalWordDur = words.sumOf { it.duration ?: 0L }
            val interWordGaps = if (words.size > 1) {
                (0 until words.size - 1).map { i -> words[i + 1].time - (words[i].time + (words[i].duration ?: 0L)) }
            } else emptyList()

            Log.e(TAG, "[$songName] Line $idx: \"${line.text}\" | Start=${line.time}ms | SpanDur=${lineDur}ms | SumWordsDur=${totalWordDur}ms | Gaps=$interWordGaps")
            for (w in words) {
                val wEnd = w.time + (w.duration ?: 0L)
                Log.e(TAG, "   Word: \"${w.word}\" | interval=[${w.time}..$wEnd] | dur=${w.duration}ms")
            }
        }
    }
}
