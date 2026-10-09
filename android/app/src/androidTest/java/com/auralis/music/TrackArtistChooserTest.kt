package com.auralis.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.TrackOptionsMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackArtistChooserTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun collaborativeSongWaitsForChoiceThenOpensSelectedArtist() {
        var selected: String? = null
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                TrackOptionsMenu(
                    track = Track(id = "graduation", title = "Graduation", artist = "benny blanco, Juice WRLD",
                        album = "FRIENDS KEEP SECRETS", albumId = "MPREtest"),
                    isFavorite = false, userPlaylists = emptyList(),
                    onPlayNext = {}, onAddToQueue = {}, onToggleFavorite = {},
                    onAddToPlaylist = {}, onCreatePlaylistAndAdd = {},
                    onGoToArtist = { selected = it }, onDismiss = { dismissals++ }
                )
            }
        }
        compose.onNodeWithText("View artist").performScrollTo().performClick()
        compose.onNodeWithText("Choose artist").assertIsDisplayed()
        compose.onNodeWithText("benny blanco").assertIsDisplayed()
        compose.onNodeWithText("Juice WRLD").assertIsDisplayed()
        compose.runOnIdle { assertNull(selected); assertEquals(0, dismissals) }
        compose.onNodeWithText("Juice WRLD").performClick()
        compose.runOnIdle { assertEquals("Juice WRLD", selected); assertEquals(1, dismissals) }
    }
}
