package com.auralis.music.data.network

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.TrackArtist
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The channel YouTube Music itself links for an artist name. Every song subtitle links each
 * artist to their channel (see [ArtistRuns]), so "De La Soul" on Feel Good Inc. points straight at
 * the real artist page. Opening an artist by name used to search the name instead and could land on
 * a tiny account with the exact same name (19 subscribers) rather than the real one, whose channel
 * may be called something else entirely ("WeAreDeLaSoul").
 *
 * Learned passively from every parsed song; when two channels share a name, the one YouTube links
 * most often wins. Saved, so it survives restarts.
 */
object ArtistChannels {
    private const val STORE_KEY = "artist_channels_v1"
    private const val MAX_NAMES = 5_000
    private const val SAVE_DELAY_MS = 4_000L

    // name key -> (channel id -> times linked)
    private val seen = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Int>>()
    @Volatile private var store: com.auralis.music.util.KeyValueStore? = null
    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun key(name: String): String? = SearchQueryMatcher.normalize(name).takeIf { it.isNotBlank() }

    private fun isChannel(id: String?) = id != null && id.startsWith("UC") && id.length >= 20

    /** Loads saved links and keeps saving new ones to [kv]. Call once at startup. */
    fun persistTo(kv: com.auralis.music.util.KeyValueStore) {
        store = kv
        try {
            val saved = org.json.JSONObject(kv.getString(STORE_KEY, "{}").orEmpty().ifBlank { "{}" })
            for (name in saved.keys()) {
                val id = saved.optString(name)
                // Saved links count once: anything YouTube links this session outweighs them on a tie.
                if (isChannel(id)) seen.getOrPut(name) { java.util.concurrent.ConcurrentHashMap() }.putIfAbsent(id, 1)
            }
        } catch (_: Exception) {
            // Unreadable links are learned again from the next songs.
        }
    }

    /** Records that YouTube linked [name] to [channelId]. */
    fun learn(name: String, channelId: String?) {
        if (!isChannel(channelId)) return
        val k = key(name) ?: return
        if (seen.size >= MAX_NAMES && !seen.containsKey(k)) seen.keys.firstOrNull()?.let { seen.remove(it) }
        val counts = seen.getOrPut(k) { java.util.concurrent.ConcurrentHashMap() }
        val before = counts.merge(channelId!!, 1, Int::plus)
        if (before == 1) scheduleSave()
    }

    fun learn(artists: List<TrackArtist>) = artists.forEach { learn(it.name, it.channelId) }

    /** The channel YouTube links for [name], or null when no song has linked it yet. */
    fun channelFor(name: String): String? {
        val counts = seen[key(name) ?: return null] ?: return null
        return counts.entries.maxByOrNull { it.value }?.key
    }

    /** [artist] with its real channel id when it was only known by name. */
    fun resolve(artist: Artist): Artist {
        if (isChannel(artist.id)) return artist
        val id = channelFor(artist.name) ?: return artist
        return artist.copy(id = id)
    }

    /**
     * The artist channel for [name] among search [candidates], for when no song has linked it.
     * "Juice WRLD" also matches "BABY juice WRLD" (66 subscribers) by containment, so a channel
     * named exactly like the artist normally wins, the most subscribed among equal names. But an
     * exact-name account is often a fan or reupload channel while the real artist's channel carries
     * a longer name ("WeAreDeLaSoul" for De La Soul): a containing name with at least 50 times the
     * subscribers is the artist.
     */
    fun pick(name: String, candidates: List<Artist>): Artist? {
        val target = key(name) ?: return null
        val squashedTarget = target.replace(" ", "")
        val channels = candidates.filter { it.id.startsWith("UC") }.distinctBy { it.id }
        val exact = channels.filter { key(it.name) == target }.maxByOrNull { followers(it) }
        val containing = channels.filter { c ->
            val squashed = key(c.name)?.replace(" ", "").orEmpty()
            squashedTarget.length >= 4 && squashed != squashedTarget && squashed.contains(squashedTarget)
        }.maxByOrNull { followers(it) }
        val loose = channels.filter { SearchQueryMatcher.isAuthorMatch(it.name, name) }.maxByOrNull { followers(it) }
        if (exact == null) return containing?.takeIf { loose == null || followers(it) >= followers(loose) } ?: loose
        if (containing != null && followers(containing) >= 50 * maxOf(1L, followers(exact))) return containing
        return exact
    }

    private fun followers(artist: Artist): Long = SearchQueryMatcher.parsePlayCount(
        artist.subscribers?.let { Regex("""[\d.,]+\s*[KkMmBb]?""").find(it)?.value?.replace(" ", "") })

    /** Test hook. */
    fun clear() = seen.clear()

    private fun scheduleSave() {
        val kv = store ?: return
        if (!savePending.compareAndSet(false, true)) return
        scope.launch {
            delay(SAVE_DELAY_MS)
            savePending.set(false)
            val snapshot = org.json.JSONObject()
            for ((name, counts) in seen) counts.entries.maxByOrNull { it.value }?.let { snapshot.put(name, it.key) }
            runCatching { kv.putStrings(mapOf(STORE_KEY to snapshot.toString())) }
        }
    }
}
