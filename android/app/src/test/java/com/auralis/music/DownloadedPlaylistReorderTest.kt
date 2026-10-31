package com.auralis.music

import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import android.content.SharedPreferences.Editor
import com.auralis.music.data.network.CompleteYouTubePlaylistImporter
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.ui.viewmodel.LibraryViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedPlaylistReorderTest {
    @Test fun `reorder persists through view model recreation without writing source playlist`() {
        Dispatchers.setMain(StandardTestDispatcher())
        val models = ViewModelStore()
        try {
            val repository = mockk<LibraryRepository>(relaxed = true)
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<Editor>()
            var saved: String? = null
            every { prefs.getString("track_orders", null) } answers { saved }
            every { prefs.edit() } returns editor
            every { editor.putString("track_orders", any()) } answers {
                saved = secondArg()
                editor
            }
            every { editor.apply() } just Runs
            fun model() = LibraryViewModel(repository, mockk<CompleteYouTubePlaylistImporter>(),
                mockk<SpotifyPlaylistImporter>(), downloadOrderPrefs = prefs)
            val original = Playlist("smart_downloaded_folder_job-123", "Offline",
                tracks = listOf("a", "b", "c").map { Track(id = it, title = it) })
            val first = model()
            models.put("first", first)
            first.selectPlaylist(original.id, original)
            first.reorderPlaylistTracks(original.id, 0, 2)
            assertEquals(listOf("b", "c", "a"), first.uiState.value.selectedPlaylist!!.tracks.map { it.id })

            val reopened = model()
            models.put("reopened", reopened)
            reopened.selectPlaylist(original.id, original)
            assertEquals(listOf("b", "c", "a"), reopened.uiState.value.selectedPlaylist!!.tracks.map { it.id })
            reopened.reorderPlaylistTracks(original.id, -1, 2)
            assertEquals(listOf("b", "c", "a"), reopened.uiState.value.selectedPlaylist!!.tracks.map { it.id })
            coVerify(exactly = 0) { repository.reorderPlaylist(any(), any()) }
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
