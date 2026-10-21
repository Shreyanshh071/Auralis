package com.auralis.music.data.repository

import com.auralis.music.data.datastore.CatalogSettingsStore
import com.auralis.music.data.datastore.CatalogSource
import com.auralis.music.data.network.*
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CancellationException

/** Keeps Android's optional catalog separate from the shared YouTube repository and playback. */
internal class CatalogSearchRepository(
    private val youtube: SearchRepository,
    private val spotify: SpotifyCatalog = SpotifyCatalogClient,
    private val selected: () -> CatalogSource = { CatalogSettingsStore.source.value },
    private val signedIn: () -> Boolean = { SpotifySession.isSignedIn },
    private val online: () -> Boolean = { ConnectivityMonitor.isOnline.value },
    private val report: (String?) -> Unit = CatalogSettingsStore::report
) : SearchRepository by youtube {
    private fun useSpotify() = selected() == CatalogSource.SPOTIFY && signedIn()

    override fun cachedSearch(query: String): SearchResults? =
        if (!online() || selected() == CatalogSource.SPOTIFY) null else youtube.cachedSearch(query)

    override suspend fun search(query: String): SearchResults = search(query) {}

    override suspend fun search(query: String, onResults: (SearchResults) -> Unit): SearchResults {
        if (!online()) return SearchResults().also(onResults)
        if (useSpotify()) {
            try {
                val results = spotify.search(query)
                report("Spotify catalog · Audio from YouTube")
                onResults(results)
                return results
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { report("Spotify unavailable · Showing YouTube Music results") }
        } else {
            report(if (selected() == CatalogSource.SPOTIFY) "Sign in to Spotify · Showing YouTube Music results" else null)
        }
        return youtube.search(query, onResults)
    }

    override suspend fun searchSongs(query: String): List<Track> =
        if (selected() == CatalogSource.SPOTIFY) search(query).songs else if (online()) youtube.searchSongs(query) else emptyList()
    override suspend fun searchLiveSongs(query: String, onResults: (List<Track>) -> Unit) {
        if (!online()) onResults(emptyList())
        else if (selected() == CatalogSource.SPOTIFY) onResults(search(query).songs)
        else youtube.searchLiveSongs(query, onResults)
    }
    override suspend fun searchAlbums(query: String) = if (!online()) emptyList() else if (selected() == CatalogSource.SPOTIFY) search(query).albums else youtube.searchAlbums(query)
    override suspend fun searchArtists(query: String) = if (!online()) emptyList() else if (selected() == CatalogSource.SPOTIFY) search(query).artists else youtube.searchArtists(query)
    override suspend fun searchPlaylists(query: String) = if (!online()) emptyList() else if (selected() == CatalogSource.SPOTIFY) search(query).playlists else youtube.searchPlaylists(query)

    override suspend fun getArtistPage(artist: Artist): ArtistPage? {
        if (!online()) return null
        if (!artist.id.startsWith("spotify:artist:")) return youtube.getArtistPage(artist)
        return try { spotify.artist(artist) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            report("Spotify unavailable · Showing YouTube Music artist details")
            youtube.getArtistPage(artist.copy(id = "yt:${artist.name}"))
        }
    }

    override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> {
        if (!online()) return emptyList()
        if (!album.id.startsWith("spotify:")) return youtube.getAlbumTracks(album)
        return try { spotify.tracks(album) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            report("Spotify release unavailable · Try again when connected")
            emptyList()
        }
    }
}
