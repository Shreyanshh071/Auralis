package com.auralis.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchComposeInteractionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAnimatedContentTransitionDispatchesOnlyActiveStateItem() {
        var activeSearchQuery by mutableStateOf("Search A")
        var clickedTrackId: String? = null

        composeTestRule.setContent {
            AnimatedContent(
                targetState = activeSearchQuery,
                label = "SearchTransition"
            ) { targetQuery ->
                val isActive = targetQuery == activeSearchQuery
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (!isActive) {
                                Modifier.pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            } else Modifier
                        )
                ) {
                    if (targetQuery == "Search A") {
                        Text(
                            text = "Song A - Top Result",
                            modifier = Modifier.clickable {
                                clickedTrackId = "track_a"
                            }
                        )
                    } else if (targetQuery == "Search B") {
                        Text(
                            text = "Song B - Top Result",
                            modifier = Modifier.clickable {
                                clickedTrackId = "track_b"
                            }
                        )
                    }
                }
            }
        }

        // Initially Search A is displayed
        composeTestRule.onNodeWithText("Song A - Top Result").assertExists()

        // Transition to Search B
        activeSearchQuery = "Search B"
        composeTestRule.waitForIdle()

        // Click on Song B
        composeTestRule.onNodeWithText("Song B - Top Result").performClick()

        // Verify clicked track is track_b, never track_a
        assertEquals("track_b", clickedTrackId)
        assertNotEquals("track_a", clickedTrackId)
    }
}
