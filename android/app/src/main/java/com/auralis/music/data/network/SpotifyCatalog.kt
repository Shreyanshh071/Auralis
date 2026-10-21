package com.auralis.music.data.network

import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal interface SpotifyCatalog {
    suspend fun search(query: String): SearchResults
    suspend fun artist(artist: Artist): ArtistPage
    suspend fun tracks(release: PlaylistResult): List<Track>
}

/** Catalog data only. sp_ tracks are resolved by the existing YouTube audio matcher on play. */
internal object SpotifyCatalogClient : SpotifyCatalog {
    private suspend fun request(operation: String, variables: JSONObject): JSONObject {
        val token = SpotifySession.getValidAccessToken() ?: throw IOException("Sign in to Spotify to browse its catalog")
        return SpotifyWebApi.query(token, operation, variables)
    }

    override suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        val vars = JSONObject().put("searchTerm", query).put("offset", 0).put("limit", 20)
            .put("numberOfTopResults", 5).put("includeAudiobooks", false)
            .put("includeArtistHasConcertsField", false).put("includePreReleases", false)
            .put("includeLocalConcertsField", false).put("includeAuthors", false)
        parseSearch(request("searchDesktop", vars))
    }

    override suspend fun artist(artist: Artist): ArtistPage = withContext(Dispatchers.IO) {
        val root = request("queryArtistOverview", JSONObject().put("uri", artist.id).put("locale", ""))
            .optJSONObject("data")?.optJSONObject("artistUnion") ?: throw IOException("Spotify artist unavailable")
        val profile = root.optJSONObject("profile")
        val visuals = root.optJSONObject("visuals")
        val discography = root.optJSONObject("discography")
        fun releases(name: String): List<PlaylistResult> = discography?.optJSONObject(name)?.optJSONArray("items").objects().flatMap { group ->
            val nested = group.optJSONObject("releases")?.optJSONArray("items")
            if (nested == null) listOfNotNull(parseRelease(group, "album"))
            else nested.objects().mapNotNull { parseRelease(it, "album") }
        }.distinctBy { it.id }
        ArtistPage(
            artist = artist.copy(name = profile?.optString("name").orEmpty().ifBlank { artist.name },
                thumbnail = image(visuals?.optJSONObject("avatarImage")) ?: artist.thumbnail),
            bannerUrl = image(visuals?.optJSONObject("headerImage")),
            description = profile?.optJSONObject("biography")?.optString("text")?.takeIf(String::isNotBlank),
            monthlyAudience = root.optJSONObject("stats")?.optLong("monthlyListeners", 0)?.takeIf { it > 0 }?.let { "${java.text.NumberFormat.getIntegerInstance().format(it)} monthly listeners" },
            topSongs = discography?.optJSONObject("topTracks")?.optJSONArray("items").objects().mapNotNull { parseTrack(it.optJSONObject("track") ?: it) },
            albums = releases("albums"), singles = releases("singles"),
            similarArtists = root.optJSONObject("relatedContent")?.optJSONObject("relatedArtists")?.optJSONArray("items").objects().mapNotNull(::parseArtist)
        )
    }

    override suspend fun tracks(release: PlaylistResult): List<Track> = withContext(Dispatchers.IO) {
        if (release.id.startsWith("spotify:playlist:")) {
            return@withContext SpotifyLibrary.fetchPlaylist(release.id.substringAfterLast(':'), SpotifyPlaylistImporter(), matchTracks = false)?.tracks
                ?: throw IOException("Spotify playlist unavailable")
        }
        val tracks = mutableListOf<Track>()
        var offset = 0
        repeat(100) {
            val root = request("getAlbum", JSONObject().put("uri", release.id).put("locale", "").put("offset", offset).put("limit", 50))
                .optJSONObject("data")?.optJSONObject("albumUnion") ?: throw IOException("Spotify album unavailable")
            val page = root.optJSONObject("tracksV2") ?: root.optJSONObject("tracks") ?: throw IOException("Spotify album has no track page")
            val items = page.optJSONArray("items") ?: throw IOException("Spotify album has no track list")
            tracks += items.objects().mapNotNull { item ->
                val node = item.optJSONObject("track") ?: item
                parseTrack(node.optJSONObject("data") ?: node, root)
            }
            offset += items.length()
            if (!SpotifyLibrary.hasMorePages(items.length(), offset, page.optInt("totalCount", -1))) return@withContext tracks.distinctBy { it.id }
        }
        throw IOException("Spotify album pagination exceeded its safety limit")
    }

    internal fun parseSearch(response: JSONObject): SearchResults {
        val data = response.optJSONObject("data")?.optJSONObject("searchV2") ?: throw IOException("Spotify search response unavailable")
        fun section(name: String) = data.optJSONObject(name)?.optJSONArray("items").objects().map { row ->
            val wrapper = row.optJSONObject("item") ?: row
            val item = wrapper.optJSONObject("data") ?: wrapper
            if (item.optString("uri").isBlank()) {
                val uri = wrapper.optString("_uri").ifBlank { wrapper.optString("uri") }
                if (uri.isNotBlank()) JSONObject(item.toString()).put("uri", uri) else item
            } else item
        }
        val songs = section("tracksV2").mapNotNull { parseTrack(it) }.distinctBy { it.id }
        val albums = section("albumsV2").mapNotNull { parseRelease(it, "album") }.distinctBy { it.id }
        val artists = section("artists").mapNotNull(::parseArtist).distinctBy { it.id }
        val playlists = section("playlists").mapNotNull { parseRelease(it, "playlist") }.distinctBy { it.id }
        return SearchResults(songs = songs, albums = albums, artists = artists, playlists = playlists,
            rankedMatches = songs.map { SearchTopResult.SongResult(it) })
    }

    internal fun parseTrack(data: JSONObject, fallbackAlbum: JSONObject? = null): Track? {
        val uri = data.optString("uri")
        val name = data.optString("name")
        if (!validUri(uri, "track") || name.isBlank() || data.optString("__typename") == "NotFound") return null
        val album = data.optJSONObject("albumOfTrack") ?: data.optJSONObject("album") ?: fallbackAlbum
        val duration = data.optJSONObject("duration")?.optLong("totalMilliseconds", 0)?.takeIf { it > 0 }
            ?: data.optJSONObject("trackDuration")?.optLong("totalMilliseconds", 0)?.takeIf { it > 0 } ?: data.optLong("durationMs", 0)
        return Track(id = "sp_${uri.substringAfterLast(':')}", title = name, artist = artists(data).ifBlank { fallbackAlbum?.let(::artists).orEmpty() },
            album = album?.optString("name")?.takeIf(String::isNotBlank),
            albumId = album?.optString("uri")?.takeIf { validUri(it, "album") },
            duration = duration / 1000L, thumbnail = image(album?.optJSONObject("coverArt")).orEmpty(), source = TrackSource.YOUTUBE)
    }

    private fun parseArtist(data: JSONObject): Artist? {
        val uri = data.optString("uri")
        val name = data.optJSONObject("profile")?.optString("name").orEmpty().ifBlank { data.optString("name") }
        if (!validUri(uri, "artist") || name.isBlank()) return null
        return Artist(uri, name, image(data.optJSONObject("visuals")?.optJSONObject("avatarImage")))
    }

    private fun parseRelease(data: JSONObject, kind: String): PlaylistResult? {
        val uri = data.optString("uri")
        val name = data.optString("name")
        if (!validUri(uri, kind) || name.isBlank()) return null
        return PlaylistResult(uri, name, image(data.optJSONObject("coverArt")) ?: SpotifyLibrary.playlistCover(data),
            artists(data).ifBlank { data.optJSONObject("ownerV2")?.optJSONObject("data")?.optString("name").orEmpty() },
            data.optJSONObject("tracks")?.optInt("totalCount", -1)?.takeIf { it >= 0 },
            if (kind == "album") data.optString("type", "album").lowercase() else null)
    }

    private fun artists(data: JSONObject) = data.optJSONObject("artists")?.optJSONArray("items").objects()
        .mapNotNull { it.optJSONObject("profile")?.optString("name")?.takeIf(String::isNotBlank) ?: it.optString("name").takeIf(String::isNotBlank) }.joinToString(", ")
    private fun image(node: JSONObject?): String? = node?.optJSONArray("sources").objects()
        .filter { it.optString("url").startsWith("https://") }.maxByOrNull { it.optInt("width", 0) }?.optString("url")
    private fun validUri(uri: String, kind: String) = Regex("spotify:$kind:[A-Za-z0-9]{22}").matches(uri)
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
}
