package com.auralis.music

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.datastore.QueueDataStore
import com.auralis.music.data.datastore.SettingsDataStore
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.Track
import com.auralis.music.service.AuralisMediaService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerPreferencesRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun persistenceSwitchClearsDiskWithoutClearingLiveQueue() = runBlocking {
        val settings = SettingsDataStore(context)
        val queues = QueueDataStore(context)
        val originalSettings = settings.settingsFlow.first()
        val originalQueue = queues.persistedQueueFlow.first()
        val player = withContext(Dispatchers.Main) { AuralisAudioPlayer.getInstance(context) }
        try {
            settings.setAutoLoadMore(false)
            settings.setPersistentQueue(true)
            delay(500)
            val tracks = listOf(Track("preference_test_one", "One"), Track("preference_test_two", "Two"))
            withContext(Dispatchers.Main) {
                player.queueManager.setQueue(tracks, 1, isUserQueue = true)
                player.persistQueue()
            }
            withTimeout(5000) { queues.persistedQueueFlow.first { it.tracks == tracks } }
            settings.setPersistentQueue(false)
            withTimeout(5000) { queues.persistedQueueFlow.first { it.tracks.isEmpty() } }
            assertEquals(tracks, withContext(Dispatchers.Main) { player.queueManager.state.queue })
            withContext(Dispatchers.Main) { player.persistQueue() }
            delay(200)
            assertTrue(queues.persistedQueueFlow.first().tracks.isEmpty())
            settings.setPersistentQueue(true)
            val saved = withTimeout(5000) { queues.persistedQueueFlow.first { it.tracks == tracks } }
            assertEquals(1, saved.currentIndex)
        } finally {
            withContext(Dispatchers.Main) {
                player.stop()
                player.queueManager.setQueue(originalQueue.tracks, originalQueue.currentIndex, isUserQueue = originalQueue.isUserQueue)
            }
            settings.updateSettings(originalSettings)
            delay(300)
            queues.saveQueue(originalQueue.tracks, originalQueue.currentIndex, originalQueue.lastPositionMs,
                originalQueue.isUserQueue, originalQueue.isShuffled, originalQueue.repeatMode)
        }
    }

    @Test fun mediaMutePausesOnlyWhenEnabled() = runBlocking {
        val settings = SettingsDataStore(context)
        val originalSettings = settings.settingsFlow.first()
        val queues = QueueDataStore(context)
        val originalQueue = queues.persistedQueueFlow.first()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val player = withContext(Dispatchers.Main) { AuralisAudioPlayer.getInstance(context) }
        val file = context.cacheDir.resolve("preference-test-silence.wav")
        // Real local PCM playback avoids network or resolver dependencies in the mute test.
        val dataSize = 8000 * 2 * 30
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        file.writeBytes(buffer.array())
        try {
            settings.setAutoLoadMore(false)
            settings.setPauseOnMediaMute(false)
            delay(300)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
            withContext(Dispatchers.Main) {
                player.stop()
                // Select the native engine without involving streaming resolution.
                AuralisAudioPlayer::class.java.getDeclaredField("isUsingExoPlayer").apply { isAccessible = true }.setBoolean(player, true)
                player.exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.exoPlayer.prepare()
                player.exoPlayer.play()
            }
            withTimeout(5000) { player.isPlaying.first { it } }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            delay(500)
            assertTrue(player.isPlaying.value)
            settings.setPauseOnMediaMute(true)
            withTimeout(5000) { player.isPlaying.first { !it } }
            assertFalse(withContext(Dispatchers.Main) { player.exoPlayer.playWhenReady })
            // Raising volume must not undo the pause or a user's subsequent intent.
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
            delay(300)
            assertFalse(player.isPlaying.value)
        } finally {
            withContext(Dispatchers.Main) { player.stop() }
            context.stopService(Intent(context, AuralisMediaService::class.java))
            settings.updateSettings(originalSettings)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            delay(300)
            queues.saveQueue(originalQueue.tracks, originalQueue.currentIndex, originalQueue.lastPositionMs,
                originalQueue.isUserQueue, originalQueue.isShuffled, originalQueue.repeatMode)
            file.delete()
        }
    }
}
