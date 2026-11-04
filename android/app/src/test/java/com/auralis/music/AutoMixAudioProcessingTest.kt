package com.auralis.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.auralis.music.data.service.automix.AutoMixFilterProcessor
import com.auralis.music.data.service.automix.Fft
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AutoMixAudioProcessingTest {
    @Test fun fftFindsTheKnownFrequencyOfASyntheticTone() {
        val size = 512
        val real = FloatArray(size) { sin(2.0 * PI * 32 * it / size).toFloat() }
        val spectrum = FloatArray(size / 2)
        Fft(size).magnitudeSpectrum(real, FloatArray(size), spectrum)
        assertEquals(32, spectrum.indices.maxBy { spectrum[it] })
        assertEquals(size / 2f, spectrum[32], .01f)
    }

    @Test fun inactiveFilterPreservesEveryPcmSample() {
        val processor = AutoMixFilterProcessor()
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val samples = ShortArray(2048) { (it * 71).toShort() }
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        val actual = ShortArray(output.remaining())
        output.get(actual)
        assertArrayEquals(samples, actual)
        assertFalse(input.hasRemaining())
    }

    @Test fun bassFilterAttenuatesLowToneAndPreservesUpperTone() {
        fun rms(hz: Double, highPass: Double): Double {
            val processor = AutoMixFilterProcessor()
            processor.setCutoffs(20_000.0, highPass)
            processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
            processor.flush()
            val input = ByteBuffer.allocateDirect(8192 * 2).order(ByteOrder.nativeOrder())
            repeat(8192) { input.putShort((sin(2.0 * PI * hz * it / 48_000) * 16_000).toInt().toShort()) }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.nativeOrder()).asShortBuffer()
            var sum = 0.0
            var count = 0
            while (output.hasRemaining()) {
                val v = output.get().toDouble()
                if (count++ > 1024) sum += v * v
            }
            return sqrt(sum / (count - 1025))
        }
        assertTrue(rms(60.0, 220.0) < rms(60.0, 0.0) * .5)
        assertTrue(rms(2000.0, 220.0) > rms(2000.0, 0.0) * .7)
    }
}
