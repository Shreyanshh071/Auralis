package com.auralis.music.windows.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.LocalTypingFocusTracker
import com.auralis.music.windows.ShortcutActions
import com.auralis.music.windows.handleShortcut
import com.auralis.music.windows.isShortcutTypingActive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsShortcutsTest {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key, command: Boolean = false): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = command,
        isMetaPressed = command && isMac,
    )

    @Test
    fun typingMAndIInSettingsSearchSuppressesPlaybackAndImmersiveShortcuts() {
        val activeInputs = mutableSetOf<Any>()
        val tracker: (Any, Boolean) -> Unit = { token, focused ->
            if (focused) activeInputs.add(token) else activeInputs.remove(token)
        }
        var mutes = 0
        var immersives = 0
        var opensSearch = 0
        val actions = object : ShortcutActions {
            override fun togglePlayPause() {}
            override fun next() {}
            override fun previous() {}
            override fun seekBy(deltaMs: Long) {}
            override fun volumeBy(delta: Float) {}
            override fun toggleMute() { mutes++ }
            override fun focusSearch() { opensSearch++ }
            override fun togglePlayer() {}
            override fun showLyrics() {}
            override fun showQueue() {}
            override fun closePlayer(): Boolean = false
            override fun openImmersive() { immersives++ }
        }

        var showSettings by mutableStateOf(true)
        var showSecondaryField by mutableStateOf(false)
        var playerOpen by mutableStateOf(false)
        fun windowShortcutHandler(event: KeyEvent): Boolean = handleShortcut(
            event = event,
            typing = isShortcutTypingActive(
                searchTyping = false,
                playerOpen = playerOpen,
                trackedInputActive = activeInputs.isNotEmpty(),
                windowTextInputActive = false,
            ),
            actions = actions,
        )

        val scene = ImageComposeScene(width = 400, height = 240, density = Density(1f)) {
            MaterialTheme {
                CompositionLocalProvider(LocalTypingFocusTracker provides tracker) {
                    var query by remember { mutableStateOf("") }
                    val boxFocus = remember { FocusRequester() }
                    Column(
                        Modifier.fillMaxSize().onPreviewKeyEvent(::windowShortcutHandler),
                    ) {
                        if (showSettings) {
                            SettingsSearchField(value = query, onValueChange = { query = it })
                        }
                        if (showSecondaryField) {
                            SettingTextField(
                                title = "Proxy",
                                description = null,
                                value = "",
                                onValue = {},
                            )
                        }
                        Box(
                            Modifier
                                .size(80.dp)
                                .focusRequester(boxFocus)
                                .clickable { boxFocus.requestFocus() }
                                .focusable(),
                        )
                    }
                }
            }
        }

        try {
            scene.render()
            assertFalse(isShortcutTypingActive(searchTyping = false, playerOpen = false, trackedInputActive = activeInputs.isNotEmpty(), windowTextInputActive = false))

            // Click into SettingsSearchField (height = 40dp at top) to focus it.
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 20f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 20f))
            scene.render()

            assertTrue(activeInputs.isNotEmpty(), "Focused SettingsSearchField must register typing focus")
            playerOpen = true
            assertTrue(
                isShortcutTypingActive(
                    searchTyping = false,
                    playerOpen = playerOpen,
                    trackedInputActive = activeInputs.isNotEmpty(),
                    windowTextInputActive = false,
                ),
                "Settings search typing must stay active even over the player sheet",
            )

            assertFalse(windowShortcutHandler(keyDown(Key.M)))
            assertFalse(scene.sendKeyEvent(keyDown(Key.M)))
            assertFalse(windowShortcutHandler(keyDown(Key.I)))
            assertFalse(scene.sendKeyEvent(keyDown(Key.I)))
            assertEquals(0, mutes, "Typing 'm' in Settings search must not toggle mute")
            assertEquals(0, immersives, "Typing 'i' in Settings search must not open screensaver/immersive")

            // Command/Ctrl shortcuts (e.g. Find) still work while typing.
            assertTrue(scene.sendKeyEvent(keyDown(Key.F, command = true)))
            assertEquals(1, opensSearch)

            // Moving focus to another tracked field keeps typing suppressed without gaps.
            showSecondaryField = true
            scene.render()
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 110f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 110f))
            scene.render()
            assertTrue(activeInputs.isNotEmpty(), "Moving focus to another text field must keep typing focus active")
            assertFalse(windowShortcutHandler(keyDown(Key.M)))
            assertEquals(0, mutes)

            // Moving focus to a non-text focusable control re-enables M and I shortcuts.
            showSecondaryField = false
            scene.render()
            scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 70f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 70f))
            scene.render()
            assertTrue(activeInputs.isEmpty(), "Moving focus to non-text control must clear typing focus")
            assertTrue(scene.sendKeyEvent(keyDown(Key.M)))
            assertTrue(scene.sendKeyEvent(keyDown(Key.I)))
            assertEquals(1, mutes)
            assertEquals(1, immersives)

            // Focus SettingsSearchField again, then close Settings directly without unfocusing first.
            scene.sendPointerEvent(PointerEventType.Press, Offset(100f, 20f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(100f, 20f))
            scene.render()
            assertTrue(activeInputs.isNotEmpty())
            assertFalse(windowShortcutHandler(keyDown(Key.M)))

            showSettings = false
            playerOpen = false
            scene.render()
            assertTrue(activeInputs.isEmpty(), "Closing Settings fields must clear typing focus")

            assertTrue(windowShortcutHandler(keyDown(Key.M)))
            assertTrue(windowShortcutHandler(keyDown(Key.I)))
            assertEquals(2, mutes)
            assertEquals(2, immersives)
        } finally {
            scene.close()
        }
    }
}
