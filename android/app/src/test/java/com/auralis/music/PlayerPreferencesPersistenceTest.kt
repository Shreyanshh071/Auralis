package com.auralis.music

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.auralis.music.data.datastore.SettingsDataStore
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.domain.model.PlayerSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayerPreferencesPersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `new preferences preserve existing playback defaults`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.root.resolve("defaults.preferences_pb")
            })
            assertEquals(PlayerSettings(), store.settingsFlow.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun `all six switches survive closing and reopening the store in both positions`() = runBlocking {
        val file = temporaryFolder.root.resolve("roundtrip.preferences_pb")
        for (enabled in listOf(false, true)) {
            val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val writer = SettingsDataStore(PreferenceDataStoreFactory.create(scope = writerScope) { file })
            writer.setPersistentQueue(enabled)
            writer.setAutoLoadMore(enabled)
            writer.setStopMusicOnTaskClear(enabled)
            writer.setPauseOnMediaMute(enabled)
            writer.setResumeOnBluetoothConnect(enabled)
            writer.setKeepScreenOn(enabled)
            writer.setAudioQuality(AudioQuality.LOW)
            writerScope.coroutineContext[Job]!!.cancelAndJoin()
            val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val reader = SettingsDataStore(PreferenceDataStoreFactory.create(scope = readerScope) { file })
                val settings = reader.settingsFlow.first()
                assertEquals(enabled, settings.persistentQueue)
                assertEquals(enabled, settings.autoLoadMore)
                assertEquals(enabled, settings.stopMusicOnTaskClear)
                assertEquals(enabled, settings.pauseOnMediaMute)
                assertEquals(enabled, settings.resumeOnBluetoothConnect)
                assertEquals(enabled, settings.keepScreenOn)
                assertEquals(AudioQuality.LOW, settings.audioQuality)
            } finally { readerScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun `concurrent independent changes retain each preference and legacy updates retain new fields`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.root.resolve("concurrent.preferences_pb")
            })
            coroutineScope {
                launch { store.setPersistentQueue(false) }
                launch { store.setAutoLoadMore(false) }
                launch { store.setPauseOnMediaMute(true) }
                launch { store.setKeepScreenOn(true) }
            }
            val saved = store.settingsFlow.first()
            assertFalse(saved.persistentQueue)
            assertFalse(saved.autoLoadMore)
            assertTrue(saved.pauseOnMediaMute)
            assertTrue(saved.keepScreenOn)
            store.updateSettings(saved.copy(skipSilence = true))
            assertEquals(saved.copy(skipSilence = true), store.settingsFlow.first())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
