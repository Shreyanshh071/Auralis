package com.auralis.music.windows.dev

import com.auralis.music.data.network.*
import com.auralis.music.data.repository.*
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.windows.profile.ProtectedYouTubeStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.io.File

/** Read-only check of a Home history album through the production track loader. */
fun main(args: Array<String>) = runBlocking {
    YouTubeSession.init(ProtectedYouTubeStore(File(com.auralis.music.util.desktopAppDataDir(), "youtube-session.properties")))
    val lookupStore = com.auralis.music.util.PropertiesFileKeyValueStore(
        File(com.auralis.music.util.desktopAppDataDir(), "search-lookups.properties"))
    InnerTubeClient.persistReleaseTypesTo(lookupStore)
    AlbumMetadataResolver.persistTo(lookupStore)
    // Reproduce the installed app's background-search pause, not only a fresh process.
    YouTubeBackoff.trip()
    val history = object : SearchHistoryStore {
        override fun recentQueries() = flowOf(emptyList<String>())
        override suspend fun add(query: String, timestampMs: Long) {}
        override suspend fun remove(query: String) {}
        override suspend fun clear() {}
    }
    val parts = args.flatMap { it.split("::") }.map { it.replace('+', ' ') }
    val albums = if (parts.firstOrNull() == "shelf") {
        val file = File(com.auralis.music.util.desktopAppDataDir(), "cache/taste-releases.json")
        val root = org.json.JSONObject(file.readText())
        listOf("tame impala", "sidney gish", "the police", "tv girl", "radiohead", "ravyn lenae", "billy idol").mapNotNull { artist ->
            val item = root.optJSONArray(artist)?.optJSONObject(0) ?: return@mapNotNull null
            PlaylistResult(item.getString("id"), item.getString("title"), item.optString("thumbnail"), item.optString("author"))
        }
    } else listOf(PlaylistResult(parts.getOrElse(0) { "album:5tpQaCAq6Qc" },
        parts.getOrElse(1) { "Who Really Cares" }, author = parts.getOrElse(2) { "TV Girl" }))
    val repository = SearchRepositoryImpl(InnerTubeClient(), SearchSuggestionsClient(), history)
    val failures = mutableListOf<String>()
    for (album in albums) {
        val canonical = com.auralis.music.windows.home.historyAlbumIdentity(album)
        val tracks = LocalizedContent.run { repository.getAlbumTracks(canonical) }
        println("ALBUM ${canonical.title} by ${canonical.author}: ${tracks.size} tracks")
        if (tracks.isEmpty()) failures += album.title
        kotlinx.coroutines.delay(800)
    }
    check(failures.isEmpty()) { "Albums failed: $failures (background cooldown=${YouTubeBackoff.active})" }
}
