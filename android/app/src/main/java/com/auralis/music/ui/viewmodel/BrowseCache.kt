package com.auralis.music.ui.viewmodel

import android.content.Context
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.Track
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The last version of each album and artist page, kept on disk so a page opens at once while the
 * fresh copy loads behind it. Lives in the cache folder, so Android can clear it at any time.
 */
internal class BrowseCache(context: Context) {
    private val folder = File(context.cacheDir, "browse_pages")
    private val json = Json { ignoreUnknownKeys = true }
    private val tracks = ListSerializer(Track.serializer())

    suspend fun album(id: String): List<Track>? = read("album:$id") { json.decodeFromString(tracks, it) }

    suspend fun saveAlbum(id: String, value: List<Track>) = write("album:$id", json.encodeToString(tracks, value))

    suspend fun artist(name: String): ArtistPage? = read("artist:${name.lowercase()}") { json.decodeFromString<ArtistPage>(it) }

    suspend fun saveArtist(name: String, value: ArtistPage) = write("artist:${name.lowercase()}", json.encodeToString(value))

    private suspend fun <T> read(key: String, decode: (String) -> T): T? = withContext(Dispatchers.IO) {
        runCatching { fileFor(key).takeIf(File::exists)?.readText()?.let(decode) }.getOrNull()
    }

    private suspend fun write(key: String, text: String) = withContext(Dispatchers.IO) {
        runCatching {
            folder.mkdirs()
            fileFor(key).writeText(text)
            val pages = folder.listFiles().orEmpty()
            pages.sortedBy(File::lastModified).take((pages.size - MAX_PAGES).coerceAtLeast(0)).forEach(File::delete)
        }
        Unit
    }

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("MD5").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(folder, "$hash.json")
    }

    private companion object {
        const val MAX_PAGES = 400
    }
}
