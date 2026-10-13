package com.auralis.music.windows.home

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.search.SearchQueryMatcher
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Portraits load independently of the paced YouTube channel lookup. Only successes are saved. */
internal class FamiliarArtistLookup(
    private val cache: KeyValueStore,
    private val search: suspend (String) -> List<Artist>,
    private val portrait: suspend (String) -> String?
) {
    private fun key(name: String) = "artist:" + SearchQueryMatcher.normalize(name)
    fun cached(name: String): Artist? = runCatching {
        val json = JSONObject(cache.getString(key(name), "").orEmpty())
        Artist(json.optString("id"), name, json.optString("photo").ifBlank { null }, json.optString("subscribers").ifBlank { null })
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
        if (current.thumbnail == null) launch {
            val photo = safely { portrait(artistPortraitQuery(name)) }?.takeIf { it.isNotBlank() }
            // A YouTube result that arrived meanwhile already has the preferred portrait.
            if (photo != null && current.thumbnail == null) update(Artist("", name, photo))
        }
        if (current.id.isBlank() || current.thumbnail == null) launch {
            val match = safely { search(name) }?.firstOrNull {
                SearchQueryMatcher.normalize(it.name) == SearchQueryMatcher.normalize(name)
            }
            if (match != null) update(match)
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
