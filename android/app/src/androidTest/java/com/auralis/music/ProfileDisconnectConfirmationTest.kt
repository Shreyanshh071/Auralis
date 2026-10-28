package com.auralis.music

import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.domain.auth.UserProfile
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.i18n.AppLanguage
import com.auralis.music.ui.profile.ProfileSheet
import com.auralis.music.ui.viewmodel.AuthUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
/** Use -PmotionArtworkTests to isolate this UI test from playback and account startup. */
class ProfileDisconnectConfirmationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cancellingKeepsAccountAndConfirmingDisconnectsExactlyOnce() {
        // A fake callback verifies the gate without signing out the device's real account.
        val disconnects = AtomicInteger()
        compose.runOnUiThread {
            AppLanguage.init(compose.activity.applicationContext)
            AppLanguage.attach(compose.activity)
            compose.activity.setContent {
                MaterialTheme {
                    ProfileSheet(
                        authUiState = AuthUiState(profile = UserProfile(
                            uid = "confirmation-test", displayName = "Test Listener",
                            email = "listener@example.com", isGoogleConnected = true
                        )),
                        onOpenPlaylistSelector = {}, onSyncLikedMusic = {},
                        onDisconnect = { disconnects.incrementAndGet() },
                        onClosePlaylistSelector = {}, onTogglePlaylistSelection = {},
                        onSelectAllPlaylists = {}, onDismiss = {}
                    )
                }
            }
        }
        val disconnect = str(R.string.disconnect_account)
        val title = str(R.string.account_ui_disconnect_confirmation_title)

        compose.onNodeWithText(disconnect).performScrollTo().performClick()
        compose.onAllNodesWithText(title).assertCountEquals(1)
        assertEquals(0, disconnects.get())

        compose.onNodeWithText(str(R.string.cancel)).performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.profile_account)).assertIsDisplayed()
        assertEquals(0, disconnects.get())

        compose.onNodeWithText(disconnect).performScrollTo().performClick()
        compose.onNode(hasText(disconnect) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(1, disconnects.get())
    }
}
