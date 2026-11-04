package com.auralis.music

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.library.rememberPlaylistForExit
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlaylistTransitionSnapshotTest {
    @Test fun outgoingDownloadedPlaylistKeepsItsLatestContentAfterBackClearsSelection() = runTest {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val initial = Playlist("smart_downloaded_folder_a", "Downloaded A", tracks = listOf(Track("one")))
        val selected = mutableStateOf<Playlist?>(initial)
        val route = mutableStateOf(initial.id)
        var shown: Playlist? = null
        var nanos = 0L
        fun frame() {
            nanos += 16_666_667L
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(nanos)
            runCurrent()
        }
        try {
            // Downloaded folders are virtual playlists and are absent from the saved library.
            composition.setContent { shown = rememberPlaylistForExit(route.value, selected.value, emptyList()) }
            frame()
            assertEquals(initial, shown)

            val latest = initial.copy(title = "Updated", tracks = listOf(Track("two"), Track("one")))
            selected.value = latest
            frame()
            selected.value = null
            repeat(16) { frame(); assertEquals(latest, shown) }

            // A quick reopen must not replace the still-exiting page with a different folder.
            val next = Playlist("smart_downloaded_folder_b", "Downloaded B")
            selected.value = next
            frame()
            assertEquals(latest, shown)
            route.value = next.id
            frame()
            assertEquals(next, shown)
            selected.value = null
            frame()
            assertEquals(next, shown)
            route.value = "missing"
            frame()
            assertNull(shown)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancel()
        }
    }
}
