package com.auralis.music.desktop.playback

import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single persisted volume drives both UI controls and the audio output. */
class DesktopVolumeState(private val store: KeyValueStore? = null, initialVolume: Float = 1f,
    private val apply: (Float) -> Unit) {
    private fun valid(value: Float?, fallback: Float) = value?.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: fallback
    private val mutable = MutableStateFlow(valid(store?.getString("volume", "")?.toFloatOrNull(), valid(initialVolume,1f)))
    val volume = mutable.asStateFlow()
    private var beforeMute = valid(store?.getString("volume_before_mute", "")?.toFloatOrNull(),
        mutable.value.takeIf { it > 0f } ?: 1f)
    init { apply(mutable.value) }
    fun set(value: Float) {
        if (!value.isFinite()) return
        val normalized = value.coerceIn(0f,1f)
        mutable.value = normalized
        if (normalized > 0f) beforeMute = normalized
        store?.putStrings(mapOf("volume" to normalized.toString(), "volume_before_mute" to beforeMute.toString()))
        apply(normalized)
    }
    fun toggleMute() = set(if (mutable.value > 0f) 0f else beforeMute.takeIf { it > 0f } ?: 1f)
}
