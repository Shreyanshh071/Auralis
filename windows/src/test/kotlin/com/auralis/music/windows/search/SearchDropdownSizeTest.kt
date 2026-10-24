package com.auralis.music.windows.search

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.search.SearchSuggestionsList
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class SearchDropdownSizeTest {
    @Test fun suggestionsGrowShrinkAndScrollAtTheHeightLimit() {
        val recent = mutableStateOf(listOf("One result"))
        var height = 0
        val scene = ImageComposeScene(700, 600, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box {
                SearchDropdownSurface(false, null, modifier = Modifier.width(620.dp).heightIn(max = 460.dp)
                    .onGloballyPositioned { height = it.size.height }) {
                SearchSuggestionsList(query = "", suggestions = emptyList(), liveSongs = emptyList(),
                    recentQueries = recent.value, currentTrackId = null, isPlaying = false,
                    contentPadding = PaddingValues(16.dp), onSearch = {}, onInsert = {}, onRemoveRecent = {},
                    onTrackClick = { _, _ -> }, onPlayNext = {}, onAddToQueue = {}, onMenuClick = {},
                    fillAvailableHeight = false,
                    modifier = Modifier.fillMaxWidth())
                }
            } }
        }
        try {
            var frame = 0L
            fun settle(): Int {
                repeat(60) { scene.render(frame++ * 16_666_667L).close() }
                return height
            }
            val one = settle()
            recent.value = (1..3).map { "Result $it" }
            val expansion = (0 until 12).map { scene.render(frame++ * 16_666_667L).close(); height }
            val three = settle()
            assertTrue(one in 50..150, "One row should not leave a tall empty tray: $one")
            assertTrue(three > one && three < 460, "Tray must grow with rows: $one -> $three")
            assertTrue(expansion.distinct().size > 4 && expansion.any { it > one && it < three },
                "The glass surface itself must grow through intermediate heights: $expansion")
            recent.value = (1..20).map { "Result $it" }
            assertEquals(460, settle(), "Many rows should stay in the bounded scrolling list")
            recent.value = listOf("One result")
            assertEquals(one, settle(), "Removing results must shrink the tray again")
        } finally { scene.close() }
    }
}
