package com.auralis.music.windows.home

import com.auralis.music.windows.profile.YouTubeMusicClient
import com.auralis.music.windows.profile.youtubeNodes
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal data class RemoteHomeState(val shelves: List<DesktopHomeShelf> = emptyList(), val recents: List<Track> = emptyList(),
    val releases: DesktopHomeShelf? = null, val loading: Boolean = false, val loadingMore: Boolean = false,
    val token: String? = null, val error: String? = null, val signedIn: Boolean = false)

/** The account's actual Home feed, history and new releases load independently. */
internal class DesktopRemoteHome(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(RemoteHomeState())
    val state = mutable.asStateFlow()
    private var client: YouTubeMusicClient? = null
    private var job: Job? = null
    private var generation = 0
    private val consumed = mutableSetOf<String>()
    private val cache = YouTubeHomeCache(java.io.File(com.auralis.music.util.desktopAppDataDir(), "home"))
    private var accountKey: String? = null
    suspend fun playlistTracks(playlist: PlaylistResult): List<Track> {
        val version = generation
        val result = (client ?: error("YouTube Home is still loading.")).tracks(playlist) {}
        check(version == generation) { "The account changed." }
        return result
    }
    fun refresh() {
        job?.cancel(); val version = ++generation
        val headers = YouTubeSession.authHeaders(YouTubeMusicClient.ORIGIN)
        val key = if (YouTubeSession.isSignedIn) com.auralis.music.windows.profile.youtubeHomeAccountKey(
            headers["Cookie"].orEmpty(), headers["X-Goog-AuthUser"] ?: "0") else null
        val previous = mutable.value.takeIf { key != null && key == accountKey }
        accountKey = key
        client = null; consumed.clear()
        mutable.value = previous?.copy(loading = true, loadingMore = false, error = null)
            ?: RemoteHomeState(loading = true, signedIn = YouTubeSession.isSignedIn)
        if (!YouTubeSession.isSignedIn) {
            job = scope.launch { loadPublicHome(version) }
            return
        }
        job = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { key?.let(cache::read) }
                if (version != generation) return@launch
                if (saved != null && mutable.value.shelves.isEmpty()) {
                    val cached = withContext(Dispatchers.Default) {
                        val home = saved.optJSONObject("home")?.let(::parseRemoteHome).orEmpty()
                        val recents = saved.optJSONObject("history")?.let(::parseRemoteHistory).orEmpty()
                        home to recents
                    }
                    if (version == generation) mutable.value = mutable.value.copy(shelves = cached.first, recents = cached.second)
                }
                val api = YouTubeMusicClient(headers["Cookie"].orEmpty(), headers["X-Goog-AuthUser"] ?: "0")
                api.resolveScope(); ensureActive()
                if (version != generation) return@launch
                client = api
                supervisorScope {
                    launch {
                        try {
                            val root = api.browseHome()
                            withContext(Dispatchers.IO) { if (version == generation && key != null) cache.write(key, "home", root) }
                            // Parsing a whole feed took ~100ms; keep it off the UI thread at startup.
                            val (shelves, token) = withContext(Dispatchers.Default) { parseRemoteHome(root) to remoteHomeContinuation(root) }
                            if (version == generation) mutable.value = mutable.value.copy(shelves = shelves, token = token, loading = false)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { if (version == generation) mutable.value = mutable.value.copy(loading = false, error = "Couldn't load your YouTube Home. Try again.") }
                    }
                    launch {
                        try {
                            val root = api.browseHome("FEmusic_history")
                            val tracks = withContext(Dispatchers.Default) { parseRemoteHistory(root) }
                            if (version == generation) {
                                mutable.value = mutable.value.copy(recents = tracks)
                                withContext(Dispatchers.IO) { if (key != null) cache.write(key, "history", root) }
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Keep the cached history on a transient failure. */ }
                    }
                    launch {
                        val shelves = runCatching { api.browseHome("FEmusic_new_releases").let { withContext(Dispatchers.Default) { parseRemoteHome(it) } } }.getOrDefault(emptyList())
                        val albums = shelves.flatMap { it.albums }.filterNot { it.id.startsWith("UC") }.distinctBy { it.id }
                        if (version == generation && albums.isNotEmpty()) mutable.value = mutable.value.copy(
                            releases = DesktopHomeShelf("new-releases", "Albums & singles", albums = albums))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (version == generation) mutable.value = mutable.value.copy(loading = false, error = "Couldn't load your YouTube Home. Try again.") }
        }
    }
    private suspend fun loadPublicHome(version: Int) = supervisorScope {
        val api = YouTubeMusicClient(cookie = "")
        try { api.resolvePublicScope() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Browse can still work with the default visitor context. */ }
        if (version != generation) return@supervisorScope
        client = api
        val pages = listOf("FEmusic_home", "FEmusic_explore", "FEmusic_new_releases")
        val results = arrayOfNulls<List<DesktopHomeShelf>>(pages.size)
        pages.mapIndexed { index, browseId -> launch {
            try {
                val root = api.browseHome(browseId)
                val shelves = withContext(Dispatchers.Default) { parseRemoteHome(root) }
                if (version != generation) return@launch
                results[index] = shelves
                if (index == 0) mutable.value = mutable.value.copy(token = remoteHomeContinuation(root))
                mutable.value = mutable.value.copy(shelves = results.filterNotNull().flatten()
                    .distinctBy { it.title.lowercase() })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Other public pages and local discovery remain available. */ }
        } }.joinAll()
        if (version == generation) mutable.value = mutable.value.copy(loading = false,
            error = if (mutable.value.shelves.isEmpty()) "Couldn't refresh discovery. Try again." else null)
    }
    suspend fun loadMore() {
        val next = mutable.value.token ?: return
        val api = client ?: return
        if (mutable.value.loadingMore || next in consumed) return
        val version = generation
        mutable.value = mutable.value.copy(loadingMore = true, error = null)
        try {
            val root = api.browseHome(continuation = next)
            if (version != generation) return
            consumed += next
            val page = withContext(Dispatchers.Default) { parseRemoteHome(root) }
            val known = mutable.value.shelves.map { it.id }.toSet()
            val fresh = page.filter { it.id !in known }
            mutable.value = mutable.value.copy(shelves = mutable.value.shelves + fresh, loadingMore = false,
                token = remoteHomeContinuation(root)?.takeUnless { it in consumed || fresh.isEmpty() })
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (version == generation) mutable.value = mutable.value.copy(loadingMore = false, error = "Couldn't load more music. Try again.") }
    }
}

internal fun remoteHomeContinuation(root: JSONObject): String? {
    val section = youtubeNodes(root, "sectionListRenderer").firstOrNull()
        ?: youtubeNodes(root, "sectionListContinuation").firstOrNull() ?: return null
    val continuations = section.optJSONArray("continuations")
    return youtubeNodes(continuations, "nextContinuationData").firstOrNull()?.optString("continuation")?.takeIf(String::isNotBlank)
        ?: section.optJSONArray("contents")?.let { contents ->
            (0 until contents.length()).firstNotNullOfOrNull { i -> contents.optJSONObject(i)?.optJSONObject("continuationItemRenderer")
                ?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank) }
        }
}
private fun homeText(node: JSONObject?): String = node?.optString("simpleText")?.takeIf(String::isNotBlank)
    ?: node?.optJSONArray("runs")?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()

internal fun parseRemoteHistory(root: JSONObject): List<Track> {
    val tracks = mutableListOf<Track>()
    youtubeNodes(root, "musicResponsiveListItemRenderer").forEach {
        InnerTubeClient().parseMusicListItem(it, tracks, mutableListOf(), mutableListOf(), mutableListOf())
    }
    return com.auralis.music.domain.recommendations.TrackDeduplicator.deduplicateTracks(
        tracks.filterNot { com.auralis.music.domain.recommendations.TrackDeduplicator.isJunkOrNoiseTrack(it) }
    ).take(40)
}
private fun homeRenderers(root: Any?): List<JSONObject> = buildList {
    fun visit(node: Any?) {
        when (node) {
            is JSONObject -> node.keys().forEach { key ->
                val child = node.opt(key)
                if (key in setOf("musicCarouselShelfRenderer", "musicShelfRenderer", "gridRenderer") && child is JSONObject) add(child)
                else visit(child)
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
        }
    }
    visit(root)
}
internal fun parseRemoteHome(root: JSONObject): List<DesktopHomeShelf> = homeRenderers(root).mapNotNull { shelf ->
    val header = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
    val title = homeText(header?.optJSONObject("title")).ifBlank { homeText(shelf.optJSONObject("title")) }
        .ifBlank { homeText(shelf.optJSONObject("header")?.optJSONObject("gridHeaderRenderer")?.optJSONObject("title")) }
    if (title.isBlank()) return@mapNotNull null
    val tracks = mutableListOf<Track>(); val albums = mutableListOf<PlaylistResult>(); val order = mutableListOf<String>()
    val contents = shelf.optJSONArray("contents") ?: shelf.optJSONArray("items") ?: return@mapNotNull null
    val parser = InnerTubeClient()
    for (i in 0 until contents.length()) {
        val row = contents.optJSONObject(i) ?: continue
        val beforeTracks = tracks.size; val beforeAlbums = albums.size
        row.optJSONObject("musicResponsiveListItemRenderer")?.let { parser.parseMusicListItem(it, tracks, mutableListOf(), albums, albums) }
        row.optJSONObject("musicTwoRowItemRenderer")?.let { two ->
            val name = homeText(two.optJSONObject("title")); val subtitle = homeText(two.optJSONObject("subtitle"))
            val subtitleRuns = two.optJSONObject("subtitle")?.optJSONArray("runs")
            val artist = subtitleRuns?.let { runs -> (0 until runs.length()).firstNotNullOfOrNull { j ->
                runs.optJSONObject(j)?.takeIf { run -> run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")?.startsWith("UC") == true }?.optString("text")
            } } ?: subtitle.substringAfter("•", subtitle).substringBefore("•").trim()
            val endpoint = two.optJSONObject("navigationEndpoint")
            val video = endpoint?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf(String::isNotBlank)
            val browse = endpoint?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf(String::isNotBlank)
            val thumbs = youtubeNodes(two.opt("thumbnailRenderer"), "musicThumbnailRenderer").firstOrNull()
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val image = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")
            if (name.isNotBlank() && video != null) tracks += Track(id = video, title = name, artist = artist, thumbnail = image.orEmpty())
            else if (name.isNotBlank() && browse != null) albums += PlaylistResult(browse, name, image, subtitle)
        }
        tracks.drop(beforeTracks).forEach { order += "track:${it.id}" }
        albums.drop(beforeAlbums).forEach { order += "album:${it.id}" }
    }
    if (order.isEmpty()) null else DesktopHomeShelf("$title:${order.joinToString("|")}", title,
        homeText(header?.optJSONObject("strapline")).takeIf(String::isNotBlank), tracks.distinctBy { it.id }, albums.distinctBy { it.id }, order.distinct())
}
