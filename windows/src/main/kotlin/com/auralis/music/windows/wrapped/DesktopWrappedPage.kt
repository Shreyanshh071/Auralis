package com.auralis.music.windows.wrapped

import androidx.compose.runtime.*
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

internal fun wrappedState(events: List<DesktopListeningStats.Listen>, year: Int, zone: ZoneId = ZoneId.systemDefault()): WrappedState {
    val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
    val selected = events.filter { it.startedAt in start until end }
    fun artist(track: Track) = track.artist.trim().removeSuffix(" - Topic")
    val songs = selected.groupBy { DesktopListeningStats.songKey(it.track) }.values.map { list ->
        val track = list.groupBy { it.track.id }.values.maxBy { group -> group.sumOf { it.timeMs } }.first().track
        SongStat(track, list.count { it.countsAsPlay }, list.sumOf { it.timeMs })
    }.sortedWith(compareByDescending<SongStat> { it.playCount }.thenByDescending { it.timeListenedMs })
    val albums = selected.filter { !it.track.album.isNullOrBlank() }
        .groupBy { artist(it.track) to it.track.album!! }.map { (key, list) ->
            AlbumStat(key.second, key.first, list.first().track.thumbnail, list.sumOf { it.timeMs })
        }.sortedByDescending { it.timeListenedMs }
    val artists = selected.groupBy { artist(it.track) }.map { (name, list) ->
        // An album cover is not an artist portrait; resolve portraits separately when available.
        ArtistStat(name, null, list.distinctBy { DesktopListeningStats.songKey(it.track) }.size, list.sumOf { it.timeMs })
    }.sortedByDescending { it.timeListenedMs }
    return WrappedState(year, false, selected.sumOf { it.timeMs } / 60_000,
        songs.size, albums.size, artists.size, songs.take(100), albums.take(5), artists.take(5))
}

@Composable
internal fun DesktopWrappedPage(stats: DesktopListeningStats, library: LibraryRepository,
    search: com.auralis.music.domain.repository.SearchRepository?, onClose: () -> Unit) {
    val events by stats.listens.collectAsState()
    val year = LocalDate.now().year
    val summary = remember(events, year) { wrappedState(events, year) }
    val photos = remember { mutableStateMapOf<String, String?>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(summary.topArtists.map { it.name }) {
        summary.topArtists.forEach { artist ->
            if (search != null && !photos.containsKey(artist.name)) photos[artist.name] = runCatching {
                search.searchArtists(artist.name).firstOrNull { it.name.equals(artist.name, true) }?.thumbnail
            }.getOrNull()
        }
    }
    WrappedScreen(summary.copy(topArtists = summary.topArtists.map { it.copy(thumbnailUrl = photos[it.name]) }), onClose) { title, tracks, cover, saved ->
        scope.launch {
            val playlist = library.createPlaylist(title, "Your $year listening highlights", cover)
            tracks.forEach { library.addTrackToPlaylist(playlist.id, it) }
            saved()
        }
    }
}
