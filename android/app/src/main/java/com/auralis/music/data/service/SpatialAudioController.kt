package com.auralis.music.data.service

import android.media.audiofx.Virtualizer
import android.util.Log

/**
 * Controller for Native Android Spatial Audio / 3D Soundstage Virtualization.
 * Connects directly to ExoPlayer's audioSessionId.
 */
class SpatialAudioController {

    private var virtualizer: Virtualizer? = null
    private var currentSessionId: Int = 0
    private var isEnabled: Boolean = false

    companion object {
        private const val TAG = "SpatialAudio"
    }

    /**
     * Attaches to an audio session.
     */
    fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == currentSessionId) return
        release()
        currentSessionId = audioSessionId
        try {
            virtualizer = Virtualizer(0, audioSessionId).apply {
                if (strengthSupported) {
                    setStrength(1000.toShort()) // Maximum wide 3D soundstage
                }
                enabled = isEnabled
            }
            Log.d(TAG, "Attached Virtualizer to audioSession=$audioSessionId, enabled=$isEnabled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach Virtualizer: ${e.message}")
        }
    }

    /**
     * Toggles 3D Spatial Audio Virtualization on/off.
     */
    fun setSpatialAudioEnabled(enabled: Boolean) {
        isEnabled = enabled
        try {
            virtualizer?.let { v ->
                v.enabled = enabled
                if (enabled && v.strengthSupported) {
                    v.setStrength(1000.toShort())
                }
                Log.d(TAG, "Spatial Audio set to $enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle Spatial Audio: ${e.message}")
        }
    }

    fun isSpatialAudioEnabled(): Boolean = isEnabled

    fun release() {
        try {
            virtualizer?.release()
        } catch (_: Exception) {}
        virtualizer = null
        currentSessionId = 0
    }
}
