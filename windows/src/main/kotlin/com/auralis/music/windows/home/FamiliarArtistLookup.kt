package com.auralis.music.windows.home

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.search.SearchQueryMatcher
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Use the matched YouTube Music artist image; never substitute an unrelated portrait source. */
internal class FamiliarArtistLookup(
    private val cache: KeyValueStore,
    private val search: suspend (String) -> List<Artist>,
    private val validatePhoto: suspend (String) -> String? = { it }
) {
    private fun key(name: String) = "artist-yt-v4:" + SearchQueryMatcher.normalize(name)
    fun cached(name: String): Artist? = runCatching {
        val json = JSONObject(cache.getString(key(name), "").orEmpty())
        val photo = json.optString("photo").ifBlank { null }?.takeIf {
            !it.startsWith("file:") || runCatching { java.io.File(java.net.URI(it)).isFile }.getOrDefault(false)
        }
        Artist(json.optString("id"), name, photo, json.optString("subscribers").ifBlank { null })
    }.getOrNull()

    suspend fun resolve(name: String, publish: (Artist) -> Unit) = coroutineScope {
        var current = cached(name) ?: Artist("", name)
        if (current.id.isNotBlank() || current.thumbnail != null) publish(current)
        fun update(found: Artist) {
            current = current.copy(id = found.id.ifBlank { current.id },
                thumbnail = found.thumbnail?.takeIf { it.isNotBlank() } ?: current.thumbnail,
                subscribers = found.subscribers?.takeIf { it.isNotBlank() } ?: current.subscribers)
            if (current.id.isBlank() && current.thumbnail == null) return
            cache.putStrings(mapOf(key(name) to JSONObject().put("id", current.id).put("photo", current.thumbnail.orEmpty())
                .put("subscribers", current.subscribers.orEmpty()).toString()))
            publish(current)
        }
        if (current.id.isBlank() || current.thumbnail == null) launch {
            val match = safely { search(name) }?.firstOrNull {
                it.id.startsWith("UC") && SearchQueryMatcher.normalize(it.name) == SearchQueryMatcher.normalize(name)
            }
            if (match != null) {
                val photo = match.thumbnail?.let { safely { validatePhoto(it) } }
                update(match.copy(thumbnail = photo.takeIf { current.thumbnail == null }))
            }
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
}

/** Imported artist credits can be all caps; Wikipedia titles are case-sensitive after the first letter. */
internal fun artistPortraitQuery(name: String): String {
    val trimmed = name.trim()
    return if (trimmed.any { it.isLetter() } && trimmed == trimmed.uppercase())
        trimmed.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { letter -> letter.titlecase() } }
    else trimmed
}
