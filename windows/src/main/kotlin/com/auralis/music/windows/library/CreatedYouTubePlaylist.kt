package com.auralis.music.windows.library

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.repository.LibraryRepository

/** Creation is an explicit Library choice, just like importing one selected playlist. */
internal suspend fun retainCreatedYouTubePlaylist(library: LibraryRepository, created: PlaylistResult): Playlist =
    library.upsertImportedPlaylist(
        source = "youtube_music", remoteId = created.id.removePrefix("VL"),
        title = created.title, description = "Imported from YouTube Music",
        coverUrl = created.thumbnail
    )
