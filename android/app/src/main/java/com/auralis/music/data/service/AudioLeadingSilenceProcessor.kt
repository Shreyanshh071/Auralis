package com.auralis.music.data.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * High-performance, zero-copy pass-through audio processor extending Media3's [BaseAudioProcessor].
 *
 * Measures initial stream leading silence directly from decoded audio samples in ExoPlayer,
 * supporting both standard 16-bit PCM ([C.ENCODING_PCM_16BIT]) and 32-bit Float PCM ([C.ENCODING_PCM_FLOAT]).
 *
 * It scans the first 5 seconds of decoded audio to detect the exact moment audible sound begins (-37dB threshold),
 * calculating `audioLeadingSilenceMs = (audibleFrameIndex * 1000L) / sampleRate`.
 *
 * This provides genuine, empirically-measured container/stream pre-roll silence for dynamic
 * lyrics synchronization (e.g. compensating for YouTube streams with intro padding against album cuts).
 */
@UnstableApi
class AudioLeadingSilenceProcessor : BaseAudioProcessor() {

    companion object {
        // Silence amplitude threshold: ~ -37dB
        // In 16-bit PCM: 450 out of 32767 (20 * log10(450 / 32768) = -37.2dB)
        // In Float PCM: 0.0138f out of 1.0f (20 * log10(0.0138) = -37.2dB)
        private const val SILENCE_16BIT_THRESHOLD = 450
        private const val SILENCE_FLOAT_THRESHOLD = 0.0138f
        private const val MAX_DETECTION_WINDOW_MS = 5000L
    }

    private var totalBytesExamined: Long = 0L
    private var isDetectionFinalized: Boolean = false

    var onLeadingSilenceDetected: ((Long) -> Unit)? = null

    fun resetDetection() {
        totalBytesExamined = 0L
        isDetectionFinalized = false
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!isDetectionFinalized) {
            val sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 44100
            val channelCount = inputAudioFormat.channelCount.takeIf { it > 0 } ?: 2
            val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
            val bytesPerSample = if (isFloat) 4 else 2
            val bytesPerFrame = bytesPerSample * channelCount
            val maxBytesToExamine = (MAX_DETECTION_WINDOW_MS * sampleRate * bytesPerFrame) / 1000L

            val startPos = inputBuffer.position()
            val limit = inputBuffer.limit()

            var pos = startPos
            if (isFloat) {
                while (pos + 3 < limit && totalBytesExamined + (pos - startPos) < maxBytesToExamine) {
                    val sample = inputBuffer.getFloat(pos)
                    if (abs(sample) > SILENCE_FLOAT_THRESHOLD) {
                        val frameIndex = (totalBytesExamined + (pos - startPos)) / bytesPerFrame
                        val silenceMs = (frameIndex * 1000L) / sampleRate
                        isDetectionFinalized = true
                        onLeadingSilenceDetected?.invoke(silenceMs)
                        break
                    }
                    pos += 4
                }
            } else {
                while (pos + 1 < limit && totalBytesExamined + (pos - startPos) < maxBytesToExamine) {
                    val sample = inputBuffer.getShort(pos)
                    if (abs(sample.toInt()) > SILENCE_16BIT_THRESHOLD) {
                        val frameIndex = (totalBytesExamined + (pos - startPos)) / bytesPerFrame
                        val silenceMs = (frameIndex * 1000L) / sampleRate
                        isDetectionFinalized = true
                        onLeadingSilenceDetected?.invoke(silenceMs)
                        break
                    }
                    pos += 2
                }
            }

            val processedInThisBuffer = (limit - startPos).toLong()
            totalBytesExamined += processedInThisBuffer

            if (!isDetectionFinalized && totalBytesExamined >= maxBytesToExamine) {
                isDetectionFinalized = true
                onLeadingSilenceDetected?.invoke(0L)
            }
        }

        // Pass-through: copy buffer directly using Media3's memory-safe buffer manager
        val buffer = replaceOutputBuffer(remaining)
        buffer.put(inputBuffer)
        buffer.flip()
    }

    override fun onFlush() {
        // Retain detection status across minor pauses/re-buffers within the same track
    }

    override fun onReset() {
        resetDetection()
    }
}

