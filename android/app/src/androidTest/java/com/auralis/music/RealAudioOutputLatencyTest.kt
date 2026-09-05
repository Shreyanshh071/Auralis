package com.auralis.music

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RealAudioOutputLatencyTest {

    companion object {
        private const val TAG = "REAL_AUDIO_LATENCY"
        private const val SAMPLE_RATE = 48000
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

    @Test
    fun testMeasurePhysicalSpeakerAudibleLatency() {
        Log.e(TAG, "==================================================================")
        Log.e(TAG, ">>> TEST 1: MEASURING PHYSICAL SPEAKER AUDIBLE LATENCY (EXOPLAYER) <<<")
        Log.e(TAG, "==================================================================")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Grant RECORD_AUDIO permission via UiAutomation
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.RECORD_AUDIO"
            )
            Thread.sleep(200)
        } catch (e: Exception) {
            Log.e(TAG, "Could not grant RECORD_AUDIO via UiAutomation", e)
        }

        // Ensure phone speaker volume is at maximum for clear transient capture
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

        val clickAudioFile = File(context.cacheDir, "click_track.wav")
        copyAssetToFile("click_track.wav", clickAudioFile)
        assertTrue("click_track.wav must exist", clickAudioFile.exists() && clickAudioFile.length() > 0)

        // Setup AudioRecord at 48kHz mono 16-bit
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recBufferSize = minBufferSize.coerceAtLeast(SAMPLE_RATE * 2)

        var recorder: AudioRecord? = null
        try {
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(recBufferSize)
                .build()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating AudioRecord: ${e.message}")
        }

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord could not be initialized! State: ${recorder?.state}")
            return
        }

        val safeRecorder = recorder

        // Target click timestamps in click_track.wav (in milliseconds)
        val expectedClicksMs = listOf(1000L, 2500L, 4000L, 5500L, 7000L, 8500L)
        val clickExoNanos = LongArray(expectedClicksMs.size) { 0L }
        val clickExoReported = BooleanArray(expectedClicksMs.size) { false }

        // We record 10 seconds of mic PCM
        val maxRecordSamples = SAMPLE_RATE * 10
        val recordedPcm = ShortArray(maxRecordSamples)
        var totalRecordedSamples = 0

        val recordingRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val recordStartNano = LongArray(1) { 0L }

        // Start background recording thread
        val recordThread = Thread {
            val shortBuf = ShortArray(1024)
            safeRecorder.startRecording()
            recordStartNano[0] = SystemClock.elapsedRealtimeNanos()

            while (recordingRunning.get() && totalRecordedSamples < maxRecordSamples - 1024) {
                val read = safeRecorder.read(shortBuf, 0, shortBuf.size)
                if (read > 0) {
                    System.arraycopy(shortBuf, 0, recordedPcm, totalRecordedSamples, read)
                    totalRecordedSamples += read
                }
            }
        }
        recordThread.start()

        Thread.sleep(150)

        val latch = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(clickAudioFile))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            val handler = Handler(Looper.getMainLooper())
            var step = 0
            val maxSteps = 950

            val trackerRunnable = object : Runnable {
                override fun run() {
                    try {
                        if (step >= maxSteps) {
                            player.stop()
                            player.release()
                            recordingRunning.set(false)
                            latch.countDown()
                            return
                        }

                        val nowNanos = SystemClock.elapsedRealtimeNanos()
                        val currentPos = player.currentPosition

                        for (i in expectedClicksMs.indices) {
                            if (!clickExoReported[i] && currentPos >= expectedClicksMs[i]) {
                                clickExoReported[i] = true
                                clickExoNanos[i] = nowNanos
                                Log.e(TAG, "ExoPlayer reached scheduled Click #${i + 1} (${expectedClicksMs[i]}ms) at tNanos=$nowNanos (pos=$currentPos ms)")
                            }
                        }

                        step++
                        handler.postDelayed(this, 10)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in trackerRunnable", e)
                        player.stop()
                        player.release()
                        recordingRunning.set(false)
                        latch.countDown()
                    }
                }
            }
            handler.post(trackerRunnable)
        }

        assertTrue("Click test must finish within 15s", latch.await(15, TimeUnit.SECONDS))
        recordingRunning.set(false)
        recordThread.join(2000)
        safeRecorder.stop()
        safeRecorder.release()

        Log.e(TAG, "Finished recording ${totalRecordedSamples} samples (${totalRecordedSamples.toDouble() / SAMPLE_RATE}s)")

        // Detect click transients in recorded PCM
        val clickDeltasMs = mutableListOf<Double>()

        for (i in expectedClicksMs.indices) {
            val expectedMs = expectedClicksMs[i]
            val exoNano = clickExoNanos[i]
            if (exoNano == 0L) continue

            // The click is in the PCM recording at roughly expectedMs + recordStartOffset
            val elapsedFromStartMs = (exoNano - recordStartNano[0]).toDouble() / 1_000_000.0
            val searchCenterSample = (elapsedFromStartMs * SAMPLE_RATE / 1000.0).toInt()
            val searchWindowStart = (searchCenterSample - SAMPLE_RATE / 10).coerceAtLeast(0)
            val searchWindowEnd = (searchCenterSample + (SAMPLE_RATE * 0.6).toInt()).coerceAtMost(totalRecordedSamples - 1)

            val frameSize = 240 // 5ms
            var maxEnergy = 0.0
            var peakSample = -1

            var s = searchWindowStart
            while (s < searchWindowEnd - frameSize) {
                var sumSq = 0.0
                for (k in 0 until frameSize) {
                    val v = recordedPcm[s + k].toDouble()
                    sumSq += v * v
                }
                val energy = sumSq / frameSize
                if (energy > maxEnergy) {
                    maxEnergy = energy
                    peakSample = s
                }
                s += 24
            }

            if (peakSample > 0) {
                val peakNanos = recordStartNano[0] + ((peakSample.toDouble() / SAMPLE_RATE) * 1_000_000_000L).toLong()
                val latencyNanos = peakNanos - exoNano
                val latencyMs = latencyNanos.toDouble() / 1_000_000.0
                clickDeltasMs.add(latencyMs)
                Log.e(TAG, String.format("Click #%d (%5dms): PeakSample=%6d | MaxEnergy=%.1f | ExoReportedAt=%d | AudibleAt=%d | Latency=%.2f ms",
                    i + 1, expectedMs, peakSample, maxEnergy, exoNano, peakNanos, latencyMs))
            }
        }

        if (clickDeltasMs.isNotEmpty()) {
            val sorted = clickDeltasMs.sorted()
            val median = sorted[sorted.size / 2]
            val min = sorted.first()
            val max = sorted.last()
            val avg = sorted.average()
            val variance = sorted.map { (it - avg) * (it - avg) }.average()

            Log.e(TAG, "==================================================================")
            Log.e(TAG, ">>> PHYSICAL AUDIBLE LATENCY RESULTS (${clickDeltasMs.size} TRIALS) <<<")
            Log.e(TAG, String.format("Median Latency:  %.2f ms", median))
            Log.e(TAG, String.format("Min Latency:     %.2f ms", min))
            Log.e(TAG, String.format("Max Latency:     %.2f ms", max))
            Log.e(TAG, String.format("Variance:        %.2f ms^2 (std-dev: %.2f ms)", variance, Math.sqrt(variance)))
            Log.e(TAG, "==================================================================")
        }
    }
}
