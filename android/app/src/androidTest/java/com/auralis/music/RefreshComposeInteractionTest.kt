package com.auralis.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.ui.components.AuralisRefreshBox
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RefreshComposeInteractionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun immediateRefreshReturnsContentToOriginalPositionAndCanRepeat() {
        assertRefreshSettles {}
    }

    @Test
    fun suspendingRefreshReturnsContentToOriginalPositionAndCanRepeat() {
        assertRefreshSettles { delay(100) }
    }

    private fun assertRefreshSettles(refresh: suspend () -> Unit) {
        var refreshCalls = 0
        composeTestRule.setContent {
            MaterialTheme {
                AuralisRefreshBox(refresh = {
                    refreshCalls++
                    refresh()
                }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("refreshContent")) {
                        items(40) { Text("Recent search $it") }
                    }
                }
            }
        }
        val content = composeTestRule.onNodeWithTag("refreshContent")
        val originalTop = content.fetchSemanticsNode().boundsInRoot.top
        repeat(2) { attempt ->
            content.performTouchInput { swipeDown() }
            composeTestRule.waitUntil(timeoutMillis = 5_000) { refreshCalls == attempt + 1 }
            composeTestRule.waitForIdle()
            assertEquals(attempt + 1, refreshCalls)
            assertEquals(originalTop, content.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
    }
}
