package com.auralis.music.windows.dev

import com.auralis.music.windows.home.DesktopArtistPortraits
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.*
import java.io.File

fun main(args: Array<String>) = runBlocking {
    args.getOrNull(1)?.let { profile ->
        val source = com.auralis.music.windows.profile.ProtectedYouTubeStore(File(profile, "youtube-session.properties"))
        com.auralis.music.data.network.YouTubeSession.init(object : com.auralis.music.util.KeyValueStore {
            override fun getString(key: String, default: String) = source.getString(key, default)
            override fun putStrings(values: Map<String, String>) {}
            override fun clear() {}
        })
    }
    val directory = File(args.firstOrNull() ?: "build/portrait-verification")
    val cache = DesktopArtistPortraits(directory)
    val names = args.drop(2).ifEmpty { listOf("Tame Impala", "Sidney Gish", "The Police", "TV Girl", "Ravyn Lenae", "Radiohead") }
    val photos = org.json.JSONObject()
    for (name in names) {
        val candidates = runCatching { cache.musicArtists(name) }.onFailure { println("$name: ${it.message}") }.getOrDefault(emptyList())
        val artist = candidates.firstOrNull {
            it.id.startsWith("UC") && SearchQueryMatcher.normalize(it.name) == SearchQueryMatcher.normalize(name)
        }
        val photo = artist?.thumbnail?.let { cache.localPhoto(it) }
        println("$name: ${if (photo != null) "YouTube Music artist image" else "unavailable"}")
        if (photo == null) println("Music artist matches: " + candidates.joinToString { it.name })
        photos.put(name, org.json.JSONObject().put("id", artist?.id.orEmpty()).put("photo", photo.orEmpty()))
    }
    directory.mkdirs()
    File(directory, "youtube-artists.json").writeText(photos.toString(2))
    check(names.all { photos.getJSONObject(it).getString("photo").isNotBlank() }) { "Some artist images could not be retrieved" }
}
