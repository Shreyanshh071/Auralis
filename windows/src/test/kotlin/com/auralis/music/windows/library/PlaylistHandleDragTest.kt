package com.auralis.music.windows.library

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.library.PlaylistTrackRow
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class PlaylistHandleDragTest {
    @Test fun unlockedMouseHandleStartsWithoutLongPressAndDoesNotPlay() {
        var starts = 0
        var ends = 0
        var moved = 0f
        var played = false
        val scene = ImageComposeScene(900, 200, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { LazyColumn {
                item {
                    PlaylistTrackRow(Track(id = "test", title = "Drag this song", artist = "Artist"),
                        wide = true, isCurrent = false, isPlaying = false, isCustomSort = true,
                        isItemBeingDragged = false, isDragging = false, onPlayTrack = { played = true },
                        onMenuClick = {}, onPlayNext = null, onAddToQueue = null, listTopInRoot = 0f,
                        onReorderDragStart = { starts++ }, onReorderDrag = { moved += it },
                        onReorderDragEnd = { ends++ }, onReorderDragCancel = {}, onRemoveFromPlaylist = {})
                }
            } }
        }
        try {
            repeat(5) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(818f, 30f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, Offset(818f, 55f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move, Offset(818f, 100f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(818f, 100f), type = PointerType.Mouse)
            assertEquals(1, starts, "A normal mouse drag must start immediately")
            assertTrue(moved > 35f, "Handle must deliver vertical movement")
            assertEquals(1, ends, "Release must commit the drag")
            assertFalse(played, "Dragging must not play the row")
        } finally { scene.close() }
    }
}
