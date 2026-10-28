package com.auralis.music.data.playlistsync

import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class SyncService(val label: String) { YOUTUBE("YouTube Music"), SPOTIFY("Spotify") }
/** The transport rejected a stale proposal before sending any playlist edit. */
class PlaylistSyncStalePreviewException(message: String) : IllegalStateException(message)
data class SyncSnapshot(val title: String, val description: String, val ids: List<String>)
data class RemoteSyncPlaylist(val id: String, val account: String, val snapshot: SyncSnapshot,
    val tracks: List<Track>, val revision: String, val editable: Boolean)
interface PlaylistSyncRemote {
    val service: SyncService
    suspend fun account(): String
    suspend fun read(id: String): RemoteSyncPlaylist
    suspend fun create(title: String, description: String): String
    suspend fun identify(track: Track): String?
    /** Re-read and validate account, edit access and revision immediately before the first edit. */
    suspend fun write(before: RemoteSyncPlaylist, target: SyncSnapshot)
}
data class PlaylistSyncLink(val localId: String, val service: SyncService, val remoteId: String,
    val account: String, val base: SyncSnapshot, val mapping: Map<String, String>,
    val enabled: Boolean = true, val syncedAt: Long = 0, val interrupted: Boolean = false,
    val pendingTarget: SyncSnapshot? = null, val pendingLocal: SyncSnapshot? = null, val failure: String? = null)
data class PlaylistSyncPreview(val local: Playlist, val remote: RemoteSyncPlaylist?, val service: SyncService,
    val account: String, val localSnapshot: SyncSnapshot, val mapping: Map<String, String>, val merged: SyncSnapshot?)
enum class SyncChoice { MERGE, AURALIS, REMOTE }

/** Occurrence keys preserve intentional duplicate tracks, rather than deduplicating by recording. */
object PlaylistSyncMerge {
    private fun occurrences(ids: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return ids.map { id -> "$id#${counts.getOrDefault(id, 0).also { counts[id] = it + 1 }}" }
    }
    private fun field(base: String, local: String, remote: String): String? = when {
        local == remote -> local
        local == base -> remote
        remote == base -> local
        else -> null
    }
    fun merge(base: SyncSnapshot, local: SyncSnapshot, remote: SyncSnapshot): SyncSnapshot? {
        val title = field(base.title, local.title, remote.title) ?: return null
        val description = field(base.description, local.description, remote.description) ?: return null
        if (local.ids == remote.ids) return SyncSnapshot(title, description, local.ids)
        if (local.ids == base.ids) return SyncSnapshot(title, description, remote.ids)
        if (remote.ids == base.ids) return SyncSnapshot(title, description, local.ids)
        val b = occurrences(base.ids); val l = occurrences(local.ids); val r = occurrences(remote.ids)
        val original = b.toSet()
        val survivors = original.intersect(l.toSet()).intersect(r.toSet())
        val baseOrder = b.filter { it in survivors }
        val localOrder = l.filter { it in survivors }; val remoteOrder = r.filter { it in survivors }
        // Conflicting reorders need a human choice; independent adds/deletes can merge.
        if (localOrder != baseOrder && remoteOrder != baseOrder && localOrder != remoteOrder) return null
        // Removing an item while the other side reorders it is also a conflict.
        if ((b.any { it !in l } && r.filter { it in original } != b.filter { it in r }) ||
            (b.any { it !in r } && l.filter { it in original } != b.filter { it in l })) return null
        val remoteReordered = remoteOrder != baseOrder && localOrder == baseOrder
        val chosen = if (remoteReordered) r else l
        val other = if (remoteReordered) l else r
        val result = chosen.filter { it !in original || it in survivors }.toMutableList()
        for ((index, item) in other.withIndex()) if (item !in original && item !in result) {
            val previous = other.take(index).lastOrNull { it in result }
            val next = other.drop(index + 1).firstOrNull { it in result }
            val at = if (next == null) result.size else previous?.let { result.indexOf(it) + 1 } ?: result.indexOf(next)
            result.add(at, item)
        }
        return SyncSnapshot(title, description, result.map { it.substringBeforeLast('#') })
    }
}

/** A device-local link journal contains playlist metadata, never account cookies or tokens. */
class PlaylistSyncStore(private val store: KeyValueStore) {
    private val key = "playlist_sync_v1"
    val links = MutableStateFlow(load())
    private fun load(): List<PlaylistSyncLink> = runCatching {
        val a = JSONArray(store.getString(key, "[]"))
        (0 until a.length()).map { index -> val o = a.getJSONObject(index)
            val map = o.getJSONObject("mapping")
            PlaylistSyncLink(o.getString("local"), SyncService.valueOf(o.getString("service")), o.getString("remote"),
                o.getString("account"), decode(o.getJSONObject("base")), map.keys().asSequence().associateWith { map.getString(it) },
                o.optBoolean("enabled", true), o.optLong("at"), o.optBoolean("interrupted"),
                o.optJSONObject("pendingTarget")?.let(::decode), o.optJSONObject("pendingLocal")?.let(::decode),
                o.optString("failure").takeIf(String::isNotBlank)) }
    }.getOrDefault(emptyList())
    @Synchronized fun put(link: PlaylistSyncLink) {
        val next = links.value.filterNot { it.localId == link.localId } + link
        persist(next); links.value = next
    }
    @Synchronized fun unlink(local: String) {
        val next = links.value.filterNot { it.localId == local }; persist(next); links.value = next
    }
    private fun persist(next: List<PlaylistSyncLink>) { store.putStrings(mapOf(key to JSONArray().apply {
        next.forEach { link -> put(JSONObject().put("local", link.localId).put("service", link.service.name)
            .put("remote", link.remoteId).put("account", link.account).put("base", encode(link.base))
            .put("mapping", JSONObject(link.mapping)).put("enabled", link.enabled).put("at", link.syncedAt)
            .put("interrupted", link.interrupted).put("pendingTarget", link.pendingTarget?.let(::encode))
            .put("pendingLocal", link.pendingLocal?.let(::encode)).put("failure", link.failure)) }
    }.toString())) }
    companion object {
        fun encode(s: SyncSnapshot) = JSONObject().put("title", s.title).put("description", s.description).put("ids", JSONArray(s.ids))
        fun decode(o: JSONObject): SyncSnapshot = SyncSnapshot(o.getString("title"), o.optString("description"),
            o.getJSONArray("ids").let { a -> (0 until a.length()).map(a::getString) })
        fun imported(id: String): Pair<SyncService, String>? {
            val parts = id.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "imported") return null
            val service = when (parts[1].lowercase()) { "spotify" -> SyncService.SPOTIFY
                "youtube", "youtube_music", "ytmusic" -> SyncService.YOUTUBE; else -> return null }
            return service to parts[2].removePrefix("VL")
        }
    }
}

class PlaylistSyncEngine(private val library: LibraryRepository, val store: PlaylistSyncStore,
    private val remotes: Map<SyncService, PlaylistSyncRemote>,
    private val pollIntervalMs: Long = 5_000, private val localDebounceMs: Long = 150,
    private val retryDelayMs: Long = 1_000) {
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    private val lock = Mutex()
    private var worker: Job? = null
    private val epochs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun start(scope: CoroutineScope) {
        if (worker != null) return
        worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val requests = Channel<Unit>(Channel.CONFLATED)
            // Artwork refreshes must not keep postponing a real playlist edit.
            launch(start = CoroutineStart.UNDISPATCHED) {
                library.getPlaylists().map { playlists -> playlists.associate { p ->
                    p.id to SyncSnapshot(p.title, p.description.orEmpty(), p.tracks.map { it.id })
                } }.distinctUntilChanged().debounce(localDebounceMs).collect { requests.send(Unit) }
            }
            launch { while (isActive) { requests.send(Unit); delay(pollIntervalMs) } }
            var retry: Job? = null
            var retryWait = retryDelayMs
            for (request in requests) {
                retry?.cancel()
                if (syncAll()) {
                    val wait = retryWait
                    retryWait = (retryWait * 3).coerceAtMost(pollIntervalMs)
                    retry = launch { delay(wait); requests.send(Unit) }
                } else retryWait = retryDelayMs
            }
        }
    }
    fun source(localId: String): Pair<SyncService, String>? =
        PlaylistSyncStore.imported(localId) ?: store.links.value.firstOrNull { it.localId == localId }?.let { it.service to it.remoteId }

    private fun matchesImport(link: PlaylistSyncLink): Boolean = PlaylistSyncStore.imported(link.localId)?.let {
        it == (link.service to link.remoteId)
    } ?: true

    /** The toggle always reconnects to this playlist's original service/list.
     * Initial linking merges additions from both copies; it never creates a new
     * playlist or asks the user to choose which complete copy to overwrite. */
    suspend fun enable(localId: String) {
        val source = source(localId) ?: error("This playlist has no saved import source.")
        val local = library.getPlaylist(localId).first() ?: error("The playlist no longer exists.")
        check(store.links.value.none { it.localId == localId && !matchesImport(it) }) {
            "The saved link differs from this playlist's import source. Turn sync off and on to reconnect the original playlist."
        }
        check(store.links.value.none { it.localId == localId && it.interrupted }) {
            "The previous sync was interrupted. Turn sync off and on to reconnect both copies without deleting songs."
        }
        val preview = preview(local, source.first, source.second)
        check(preview.merged != null) { "Sync is paused because both copies have conflicting changes. Neither copy was overwritten." }
        apply(preview, SyncChoice.MERGE)
    }
    /** Creation explicitly chooses a destination. Retries reuse a journaled remote
     * ID if creation succeeded before a later request failed. */
    suspend fun connectCreated(localId: String, service: SyncService) {
        val local = library.getPlaylist(localId).first() ?: error("The playlist no longer exists.")
        val existing = store.links.value.firstOrNull { it.localId == localId }
        check(existing == null || existing.service == service) { "This playlist is already linked to another service." }
        val p = preview(local, service, existing?.remoteId)
        apply(p, if (p.remote == null) SyncChoice.AURALIS else SyncChoice.MERGE)
    }
    private fun message(id: String, text: String) { status.update { it + (id to text) } }
    private suspend fun syncAll(): Boolean {
        var retry = false
        for (link in store.links.value.filter { it.enabled }) try { sync(link.localId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            retry = true
            message(link.localId, e.message ?: "Sync pending. Retrying automatically.")
            com.auralis.music.util.Log.w("PlaylistSync", "${link.service} sync pending: ${e.message}")
        }
        return retry
    }
    private fun remote(service: SyncService) = remotes[service] ?: error("${service.label} sync is unavailable.")
    private fun sameContent(a: Playlist?, b: Playlist): Boolean = a != null && a.title == b.title &&
        a.description.orEmpty() == b.description.orEmpty() && a.tracks.map { it.id } == b.tracks.map { it.id }
    suspend fun preview(local: Playlist, service: SyncService, remoteId: String?): PlaylistSyncPreview = withContext(Dispatchers.IO) {
        val api = remote(service)
        val account = api.account()
        val existing = store.links.value.firstOrNull { it.localId == local.id }
        if (existing != null) check(existing.account == account && existing.service == service) {
            "Reconnect the account linked to this playlist, or turn sync off before linking another account." }
        val target = remoteId?.takeIf { it.isNotBlank() }?.let { api.read(it) }
        check(target == null || target.editable) { "This account cannot edit that playlist. Choose a playlist you own or can edit." }
        val mapping = existing?.mapping.orEmpty().toMutableMap()
        target?.tracks?.zip(target.snapshot.ids)?.forEach { (track, id) -> mapping[track.id] = id }
        val ids = local.tracks.map { track -> mapping[track.id] ?: api.identify(track)?.also { mapping[track.id] = it }
            ?: error("Cannot confidently match ‘${track.title}’ on ${service.label}. Sync is paused; neither playlist was changed.") }
        val snapshot = SyncSnapshot(local.title, local.description.orEmpty(), ids)
        val merged = if (existing != null && target != null && !existing.interrupted)
            PlaylistSyncMerge.merge(existing.base, snapshot, target.snapshot) else if (target != null)
                PlaylistSyncMerge.merge(SyncSnapshot(snapshot.title, snapshot.description, emptyList()), snapshot, target.snapshot) else null
        PlaylistSyncPreview(local, target, service, account, snapshot, mapping, merged)
    }
    suspend fun apply(preview: PlaylistSyncPreview, choice: SyncChoice) = lock.withLock {
        withContext(Dispatchers.IO) {
            val epoch = epochs.getOrDefault(preview.local.id, 0L)
            fun stillEnabled() { check(epochs.getOrDefault(preview.local.id, 0L) == epoch) { "Sync was turned off." } }
            val api = remote(preview.service)
            check(api.account() == preview.account) { "The connected account changed. Review sync again." }
            val local = library.getPlaylist(preview.local.id).first() ?: error("The local playlist no longer exists.")
            check(sameContent(local, preview.local)) { "Auralis changed since the preview. Review the latest changes." }
            val target = when (choice) { SyncChoice.AURALIS -> preview.localSnapshot
                SyncChoice.REMOTE -> preview.remote?.snapshot ?: error("No remote playlist selected.")
                SyncChoice.MERGE -> preview.merged ?: error("Review the conflicting changes first.") }
            var verifiedRead: RemoteSyncPlaylist? = null
            val remoteBefore = preview.remote?.let { old ->
                // Writes validate freshness inside their transport, directly before
                // editing. Pure pulls validate here and reuse that complete response.
                if (old.snapshot != target) old else api.read(old.id).also {
                    check(it.revision == old.revision && it.snapshot == old.snapshot) {
                        "The remote playlist changed since the preview. Review again." }
                    verifiedRead = it
                }
            } ?: run {
                stillEnabled()
                val id = api.create(target.title, target.description)
                store.put(PlaylistSyncLink(local.id, preview.service, id, preview.account,
                    SyncSnapshot(target.title, target.description, emptyList()), preview.mapping, interrupted = true))
                api.read(id)
            }
            check(remoteBefore.editable) { "This account cannot edit the linked playlist." }
            val link = PlaylistSyncLink(local.id, preview.service, remoteBefore.id, preview.account,
                preview.remote?.snapshot ?: remoteBefore.snapshot, preview.mapping, interrupted = true,
                pendingTarget = target, pendingLocal = preview.localSnapshot)
            // An interrupted write never becomes an automatic overwrite on retry.
            val previousLink = store.links.value.firstOrNull { it.localId == local.id }
            stillEnabled(); store.put(link)
            try {
            if (remoteBefore.snapshot != target) api.write(remoteBefore, target)
            val verified = verifiedRead ?: api.read(remoteBefore.id)
            stillEnabled()
            check(verified.snapshot == target) { "Remote update was incomplete. Review Playlist sync before retrying." }
            check(api.account() == preview.account) { "The account changed during sync. Review before retrying." }
            val latest = library.getPlaylist(local.id).first()
            check(sameContent(latest, local)) { "Auralis changed during sync. Review both copies before retrying." }
            val localByRemote = latest!!.tracks.mapNotNull { t -> preview.mapping[t.id]?.let { it to t } }.toMap()
            val remoteTracks = verified.tracks.zip(verified.snapshot.ids).associate { (t, id) -> id to t }
            val tracks = target.ids.map { localByRemote[it] ?: remoteTracks[it] ?: error("Missing track metadata; review sync.") }
            library.updatePlaylist(local.id, target.title, target.description.ifBlank { null }, latest.coverUrl)
            library.replacePlaylistTracks(local.id, tracks)
            val mapping = preview.mapping + tracks.zip(target.ids).associate { (t, id) -> t.id to id }
            store.put(link.copy(base = target, mapping = mapping, syncedAt = System.currentTimeMillis(), interrupted = false,
                pendingTarget = null, pendingLocal = null, failure = null))
            message(local.id, "Up to date")
            com.auralis.music.util.Log.i("PlaylistSync", "${link.service} sync verified: ${target.ids.size} tracks")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is PlaylistSyncStalePreviewException && epochs.getOrDefault(local.id, 0L) == epoch && preview.remote != null) {
                    // No edit was sent: retry from the last successful baseline,
                    // rather than incorrectly treating a simultaneous edit as a partial upload.
                    if (previousLink != null) store.put(previousLink) else store.unlink(local.id)
                } else store.links.value.firstOrNull { it.localId == local.id && it.remoteId == link.remoteId && it.interrupted }
                    ?.let { store.put(it.copy(failure = e.message?.take(500))) }
                message(local.id, e.message ?: "Sync pending. Your local edits are kept.")
                throw e
            }
        }
    }
    suspend fun sync(id: String) {
        // A local edit arriving during an upload must wait, not lose its request.
        lock.withLock { }
        val link = store.links.value.firstOrNull { it.localId == id && it.enabled } ?: return
        if (!matchesImport(link)) { message(id, "Sync paused: the saved link differs from the import source. Turn sync off and on to reconnect the original playlist."); return }
        val local = library.getPlaylist(id).first() ?: run { store.unlink(id); return }
        message(id, "Checking changes…")
        val p = preview(local, link.service, link.remoteId)
        val merged = if (link.interrupted) {
            val online = p.remote!!.snapshot
            if (link.pendingTarget != null && online != link.base && online != link.pendingTarget) {
                message(id, "Sync paused: the remote playlist changed during an interrupted upload. Both copies are kept."); return
            }
            if (link.pendingTarget != null && link.pendingLocal != null) {
                PlaylistSyncMerge.merge(link.pendingLocal, p.localSnapshot, link.pendingTarget)
            } else {
                // Older journals have no intended target. If both copies still
                // retain the baseline in order, merge additions without removing
                // anything (including a first song already uploaded before failure).
                fun additionsOnly(ids: List<String>): Boolean {
                    var next = 0
                    for (value in ids) if (next < link.base.ids.size && value == link.base.ids[next]) next++
                    return next == link.base.ids.size
                }
                if (!additionsOnly(p.localSnapshot.ids) || !additionsOnly(online.ids)) {
                    message(id, "Sync paused: the old upload needs review. Both copies are kept."); return
                }
                PlaylistSyncMerge.merge(link.base, p.localSnapshot, online)
            }
        } else p.merged
        if (merged == null) { message(id, "Both copies changed. Sync is paused; both playlists are kept."); return }
        if (!link.interrupted && merged == p.localSnapshot && merged == p.remote?.snapshot) {
            message(id, "Up to date"); return
        }
        apply(p.copy(merged = merged), SyncChoice.MERGE)
    }
    fun disable(id: String) { epochs.compute(id) { _, previous -> (previous ?: 0L) + 1 }; store.unlink(id); message(id, "Sync off. Both playlists are kept.") }
}
