package com.auralis.music.desktop.browse

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auralis.music.data.network.LocalizedContent
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** A page opened on top of the main pages (the phone's detail stack). */
sealed interface BrowsePage {
    data class Album(val album: PlaylistResult, val tracks: List<Track> = emptyList(), val isLoading: Boolean = true,
        val loadFailed: Boolean = false) : BrowsePage
    data class Artist(val page: ArtistPage, val isLoading: Boolean = true, val loadFailed: Boolean = false) : BrowsePage
}

/**
 * Album and artist pages, loaded the way the phone's search screen loads them (SearchViewModel's
 * openAlbum / openArtist), kept as a stack so back returns to the previous page.
 */
class DesktopBrowseState(
    private val repository: SearchRepository,
    private val scope: CoroutineScope,
    private val resolveArtistPhoto: suspend (ArtistPage) -> String? = DesktopArtistPhotos::resolve
) {
    val stack = mutableStateListOf<BrowsePage>()
    val top: BrowsePage? get() = stack.lastOrNull()

    private val artistPages = ConcurrentHashMap<String, ArtistPage>()
    private val artistPhotos = mutableMapOf<String, String>()
    private val photoRequests = mutableMapOf<String, Deferred<String?>>()

    private fun ArtistPage.withKnownPhoto(): ArtistPage {
        val photo = artistPhotos[artist.name.lowercase()] ?: return this
        return copy(artist = artist.copy(thumbnail = photo), bannerUrl = photo)
    }

    fun openAlbum(album: PlaylistResult, fromArtist: ArtistPage? = null) {
        val initialTracks = if (album.id.startsWith("artist_top_songs:")) fromArtist?.topSongs.orEmpty() else emptyList()
        if (initialTracks.isNotEmpty()) {
            stack += BrowsePage.Album(album, initialTracks, isLoading = false)
            return
        }
        stack += BrowsePage.Album(album, initialTracks, isLoading = true)
        scope.launch {
            val tracks = runCatching { LocalizedContent.run { repository.getAlbumTracks(album) } }.getOrDefault(emptyList())
            val finalTracks = tracks.ifEmpty { initialTracks }
            replace({ it is BrowsePage.Album && it.album.id == album.id }) { page ->
                page as BrowsePage.Album
                val first = finalTracks.firstOrNull()
                val enriched = if (first != null && (page.album.thumbnail.isNullOrBlank() || page.album.thumbnail!!.contains("default"))) {
                    page.album.copy(thumbnail = first.thumbnail.takeIf { it.isNotBlank() } ?: page.album.thumbnail)
                } else page.album
                page.copy(album = enriched, tracks = finalTracks, isLoading = false, loadFailed = finalTracks.isEmpty())
            }
        }
    }

    fun retryAlbum() {
        val page = top as? BrowsePage.Album ?: return
        stack.removeAt(stack.lastIndex)
        openAlbum(page.album)
    }

    /** A song's album: by its id when known, otherwise the best album search match for its name. */
    fun openTrackAlbum(track: com.auralis.music.domain.model.Track, onMissing: () -> Unit = {}) {
        val title = track.album?.takeIf { it.isNotBlank() } ?: return
        val id = track.albumId
        if (!id.isNullOrBlank()) {
            openAlbum(PlaylistResult(id, title, author = track.artist))
            return
        }
        scope.launch {
            val key = { s: String -> s.lowercase().replace(Regex("""[^\p{L}\p{Nd}]"""), "") }
            val results = runCatching { repository.searchAlbums("$title ${track.artist}") }.getOrDefault(emptyList())
            val match = results.firstOrNull { key(it.title) == key(title) } ?: results.firstOrNull()
            if (match != null) openAlbum(match) else onMissing()
        }
    }

    fun openArtist(artist: Artist) {
        val cached = artistPages[artist.name.lowercase()]
        var current = BrowsePage.Artist((cached ?: ArtistPage(artist = artist)).withKnownPhoto(), isLoading = cached == null)
        stack += current
        var upgradeStarted = false
        fun upgradePhoto(page: ArtistPage) {
            val key = page.artist.name.lowercase()
            // An overview with no catalogue cannot establish the identity of a same-named artist.
            if (upgradeStarted || artistPhotos.containsKey(key) ||
                (page.topSongs.isEmpty() && page.albums.isEmpty() && page.singles.isEmpty())) return
            upgradeStarted = true
            val request = photoRequests.getOrPut(key) { scope.async {
                try { resolveArtistPhoto(page) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            } }
            scope.launch {
                val photo = try { request.await() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                if (photo == null) {
                    if (photoRequests[key] === request) photoRequests.remove(key)
                    return@launch
                }
                artistPhotos[key] = photo
                replace({ it === current }) {
                    current.copy(page = current.page.withKnownPhoto()).also { current = it }
                }
            }
        }
        if (cached != null) {
            upgradePhoto(cached)
            return
        }
        scope.launch {
            val page = runCatching { LocalizedContent.run { repository.getArtistPage(artist) { preview ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    replace({ it === current }) {
                        BrowsePage.Artist(preview.withKnownPhoto(), isLoading = true).also { current = it }
                    }
                    upgradePhoto(preview)
                }
            } } }.getOrNull()
            if (page != null && page.topSongs.isNotEmpty() && !page.isFallback && !page.catalogueIncomplete) artistPages[artist.name.lowercase()] = page
            replace({ it === current }) {
                BrowsePage.Artist((page ?: current.page).withKnownPhoto(), isLoading = false,
                    loadFailed = page == null || page.topSongs.isEmpty() || page.isFallback).also { current = it }
            }
            if (page != null) upgradePhoto(page)
        }
    }

    fun retryArtist() {
        val top = top as? BrowsePage.Artist ?: return
        artistPages.remove(top.page.artist.name.lowercase())
        stack.removeAt(stack.lastIndex)
        openArtist(top.page.artist)
    }

    /** Back: closes the top page; false when there is none. */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun clear() = stack.clear()

    fun clearCache() {
        artistPages.clear()
        artistPhotos.clear()
        photoRequests.values.forEach { it.cancel() }
        photoRequests.clear()
    }

    private fun replace(match: (BrowsePage) -> Boolean, update: (BrowsePage) -> BrowsePage) {
        val i = stack.indexOfLast(match)
        if (i >= 0) stack[i] = update(stack[i])
    }
}
