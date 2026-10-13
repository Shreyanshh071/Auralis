package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.DesktopVolumeState
import com.auralis.music.util.KeyValueStore
import kotlin.test.*

class DesktopVolumeStateTest {
    private class Store : KeyValueStore {
        val data = mutableMapOf<String,String>()
        override fun getString(key: String, default: String) = data[key] ?: default
        override fun putStrings(values: Map<String,String>) { data.putAll(values) }
        override fun clear() { data.clear() }
    }
    @Test fun savedHalfVolumeDrivesSliderAndAudioAfterRestartThenIncreasesNormally() {
        val store = Store()
        var native = -1f
        val first = DesktopVolumeState(store) { native = it }
        first.set(.5f)
        val reopened = DesktopVolumeState(store, 1f) { native = it }
        assertEquals(.5f,reopened.volume.value)
        assertEquals(.5f,native)
        reopened.set(.75f)
        assertEquals(.75f,reopened.volume.value)
        assertEquals(.75f,native)
    }
    @Test fun restartWhileMutedRestoresPreviousLevelAndRejectsInvalidValues() {
        val store = Store()
        val first = DesktopVolumeState(store) {}
        first.set(.4f); first.toggleMute()
        val reopened = DesktopVolumeState(store) {}
        assertEquals(0f,reopened.volume.value)
        reopened.toggleMute()
        assertEquals(.4f,reopened.volume.value)
        reopened.set(Float.NaN)
        assertEquals(.4f,reopened.volume.value)
        reopened.set(2f)
        assertEquals(1f,reopened.volume.value)
    }
    @Test fun adoptsInitialNativeVolumeWhenNoPreferenceHasBeenSaved() {
        var native = -1f
        val state = DesktopVolumeState(Store(),.5f) { native = it }
        assertEquals(.5f,state.volume.value)
        assertEquals(.5f,native)
    }
}
