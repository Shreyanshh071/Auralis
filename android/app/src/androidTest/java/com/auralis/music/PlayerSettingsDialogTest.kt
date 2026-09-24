package com.auralis.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.datastore.SettingsDataStore
import com.auralis.music.domain.model.PlayerSettings
import com.auralis.music.ui.screens.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSettingsDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun modalScrollsToAllSettingsAndPersistsSwitchChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsDataStore(context)
        val original = runBlocking { store.settingsFlow.first() }
        try {
            compose.runOnUiThread { compose.activity.setContent {
                val settings by store.settingsFlow.collectAsState(initial = original)
                MaterialTheme {
                    SettingsScreen(settings, {}, {}, {}, {}, {}, {}, {}, {})
                }
            } }
            compose.onNodeWithText("Player and audio").performClick()
            compose.onNodeWithText("Player", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Streaming Quality").assertIsDisplayed()
            val checks: List<Pair<String, (PlayerSettings) -> Boolean>> = listOf(
                "Persistent queue" to { it.persistentQueue },
                "Auto load more songs" to { it.autoLoadMore },
                "Stop music on task clear" to { it.stopMusicOnTaskClear },
                "Pause music when media is muted" to { it.pauseOnMediaMute },
                "Resume on Bluetooth connect" to { it.resumeOnBluetoothConnect },
                "Keep screen on when player is expanded" to { it.keepScreenOn }
            )
            for ((label, read) in checks) {
                android.util.Log.i("PlayerSettingsDialogTest", "Toggling $label from ${read(original)}")
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
                compose.waitForIdle()
                compose.waitUntil(timeoutMillis = 5000) {
                    runBlocking { read(store.settingsFlow.first()) != read(original) }
                }
            }
            compose.onNodeWithText("Done").assertIsDisplayed().performClick()
            compose.onNodeWithText("Settings").assertIsDisplayed()
        } finally {
            runBlocking { store.updateSettings(original) }
        }
    }
}
