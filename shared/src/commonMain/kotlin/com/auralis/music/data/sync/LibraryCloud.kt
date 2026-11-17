package com.auralis.music.data.sync

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

// The account library on Cloudflare (docs/library-sync/spec.md). The server is an ordered store;
// what a difference means is still decided here by AccountLibraryMerge, against a local mirror of
// the server's copy. Nothing in this file runs unless a build names a library endpoint.

/** Which system holds an account's library. */
enum class LibraryAuthority {
    LEGACY, MIGRATING, CLOUDFLARE;

    companion object {
        fun of(name: String?): LibraryAuthority? = when (name) {
            "legacy" -> LEGACY; "migrating" -> MIGRATING; "cloudflare" -> CLOUDFLARE; else -> null
        }
    }
}

/** What discovery last said about an account, and when. */
data class AuthorityAnswer(val state: LibraryAuthority, val epoch: Int, val checkedAt: Long) {
    fun encode(): String = JSONObject().put("state", state.name.lowercase()).put("epoch", epoch).put("at", checkedAt).toString()

    companion object {
        fun decode(raw: String?): AuthorityAnswer? = runCatching {
            val json = JSONObject(raw?.takeIf { it.isNotBlank() } ?: return null)
            AuthorityAnswer(LibraryAuthority.of(json.getString("state")) ?: return null, json.getInt("epoch"), json.getLong("at"))
        }.getOrNull()
    }
}

/** Where this device may sync an account's library right now. */
sealed interface LibraryRoute {
    /** Today's Firestore sync, unchanged. */
    data object Firestore : LibraryRoute
    data class Cloudflare(val epoch: Int) : LibraryRoute
    /** Neither: edits stay on the device and nothing is uploaded anywhere. */
    data class Wait(val migrating: Boolean) : LibraryRoute
}

object LibraryAuthorityPolicy {
    /** How long a "legacy" answer is acted on without discovery; the security-rule fence is the real guard. */
    const val LEGACY_TRUST_MS = 6 * 3_600_000L

    /** A lower epoch is an older answer arriving late, never a step back. */
    fun remember(cached: AuthorityAnswer?, fresh: AuthorityAnswer?): AuthorityAnswer? = when {
        fresh == null -> cached
        cached != null && fresh.epoch < cached.epoch -> cached
        else -> fresh
    }

    /**
     * @param enabled false when the build names no library endpoint: discovery is never consulted.
     * @param reachable whether [known] was confirmed just now.
     */
    fun route(enabled: Boolean, known: AuthorityAnswer?, reachable: Boolean, now: Long): LibraryRoute = when {
        !enabled -> LibraryRoute.Firestore
        // Not reaching discovery never means "legacy": it means not knowing.
        known == null -> LibraryRoute.Wait(migrating = false)
        known.state == LibraryAuthority.CLOUDFLARE -> LibraryRoute.Cloudflare(known.epoch)
        known.state == LibraryAuthority.MIGRATING -> LibraryRoute.Wait(migrating = true)
        reachable || now - known.checkedAt in 0 until LEGACY_TRUST_MS -> LibraryRoute.Firestore
        else -> LibraryRoute.Wait(migrating = false)
    }
}

/** The account moved to another state or epoch: ask discovery again before doing anything else. */
class LibraryAuthorityChanged(val state: LibraryAuthority?, val epoch: Int) : IOException("Library authority changed")

/** The server no longer speaks this app's protocol: only an update fixes it. */
class LibraryUpdateRequired : IOException("This version of Auralis can no longer sync your library. Please update.")

/** One frame to the account's library and its answer. Fails with [IOException] when unreachable. */
fun interface LibraryTransport {
    suspend fun call(frame: JSONObject): JSONObject
}

/** Per-account files: the mirror, the batch in flight and the cached authority. */
interface LibraryCloudStore {
    fun read(uid: String, name: String): String?
    fun write(uid: String, name: String, value: String?)
}

class FileLibraryCloudStore(private val dir: File) : LibraryCloudStore {
    private fun file(uid: String, name: String) = File(dir, uid.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + name + ".json")

    override fun read(uid: String, name: String): String? =
        runCatching { file(uid, name).takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    @Synchronized override fun write(uid: String, name: String, value: String?) {
        val target = file(uid, name)
        if (value == null) { Files.deleteIfExists(target.toPath()); return }
        dir.mkdirs()
        val temporary = File(dir, target.name + ".tmp")
        temporary.outputStream().use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal object LibraryJson {
    private fun text(o: JSONObject, key: String) = o.optString(key).takeIf { !o.isNull(key) && it.isNotBlank() && it != "null" }
    private fun JSONObject.putOrNull(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)

    fun track(t: Track): JSONObject = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist)
        .putOrNull("album", t.album).put("thumbnail", t.thumbnail).put("duration", t.duration)

    fun track(o: JSONObject) = Track(id = o.optString("id"), title = o.optString("title").ifBlank { "Unknown Track" },
        artist = o.optString("artist").ifBlank { "Unknown Artist" }, album = text(o, "album"),
        thumbnail = text(o, "thumbnail").orEmpty(), duration = o.optLong("duration"))

    fun tracks(list: List<Track>) = JSONArray(list.map(::track))
    fun tracks(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }.map(::track).filter { it.id.isNotBlank() }

    fun album(a: SavedAlbum): JSONObject = JSONObject().put("id", a.id).put("title", a.title).putOrNull("artist", a.artist)
        .putOrNull("thumbnail", a.thumbnail).putOrNull("trackCount", a.trackCount).put("savedAt", a.savedAt)

    fun album(o: JSONObject) = SavedAlbum(o.optString("id"), o.optString("title"), text(o, "artist"), text(o, "thumbnail"),
        o.optInt("trackCount").takeIf { it > 0 }, o.optLong("savedAt"))

    fun artist(a: SavedArtist): JSONObject = JSONObject().put("id", a.id).put("name", a.name).putOrNull("thumbnail", a.thumbnail)
        .putOrNull("subscribers", a.subscribers)

    fun artist(o: JSONObject) = SavedArtist(o.optString("id"), o.optString("name"), text(o, "thumbnail"), text(o, "subscribers"))

    /** A playlist's own fields, as the server stores them next to its tracks. */
    fun playlistFields(p: Playlist): JSONObject = JSONObject().put("id", p.id).put("title", p.title).putOrNull("description", p.description)
        .putOrNull("coverUrl", p.coverUrl).put("createdAt", p.createdAt).put("isCustom", p.isCustom)

    fun playlist(o: JSONObject, tracks: List<Track>) = Playlist(o.getString("id"), o.optString("title").ifBlank { "Restored Playlist" },
        text(o, "description"), text(o, "coverUrl"), tracks, createdAt = o.optLong("createdAt"), isCustom = o.optBoolean("isCustom", true))

    fun strings(array: JSONArray?) = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf(String::isNotBlank) }
}

/**
 * This device's copy of what the server holds for one account, with each entry's revision. It is
 * the "cloud" side of the merge, so syncing needs no download when nothing changed.
 */
class LibraryMirror {
    class Item(val pos: Long, val rev: Long, val body: JSONObject)

    var cursor = 0L
    var epoch = 0
    val playlists = LinkedHashMap<String, Pair<Long, Playlist>>()
    /** Playlist id to (deleted at, revision). */
    val deleted = HashMap<String, Pair<Long, Long>>()
    val items: Map<String, HashMap<String, Item>> = mapOf("f" to HashMap(), "a" to HashMap(), "r" to HashMap())
    var pins: List<String> = emptyList()
    var pinsAt = 0L

    fun clear() {
        cursor = 0L; playlists.clear(); deleted.clear(); items.values.forEach { it.clear() }; pins = emptyList(); pinsAt = 0L
    }

    /** The revision to quote when changing a playlist: 0 for one the server has never held. */
    fun revisionOf(playlistId: String): Long = playlists[playlistId]?.first ?: deleted[playlistId]?.second ?: 0L

    private fun ordered(kind: String) = items.getValue(kind).values.sortedWith(compareBy({ it.pos }, { it.body.optString("id") }))

    fun library() = SyncedLibrary(
        playlists = playlists.values.map { it.second },
        favorites = ordered("f").map { LibraryJson.track(it.body) },
        albums = ordered("a").map { LibraryJson.album(it.body) },
        artists = ordered("r").map { LibraryJson.artist(it.body) },
        pins = pins, pinsAt = pinsAt, deleted = deleted.mapValues { it.value.first })

    /** Applies entries from a pull or a pushed change. An entry older than what is held is ignored. */
    fun apply(delta: JSONObject) {
        val lists = delta.optJSONArray("playlists")
        for (i in 0 until (lists?.length() ?: 0)) {
            val p = lists!!.getJSONObject(i)
            val id = p.getString("id"); val rev = p.optLong("rev")
            if (rev < revisionOf(id)) continue
            if (p.has("deletedAt")) { playlists.remove(id); deleted[id] = p.optLong("deletedAt") to rev; continue }
            // No tracks in the entry: only the playlist's own fields changed.
            val tracks = if (p.has("tracks")) LibraryJson.tracks(p.optJSONArray("tracks")) else playlists[id]?.second?.tracks.orEmpty()
            playlists[id] = rev to LibraryJson.playlist(p, tracks)
            deleted.remove(id)
        }
        val entries = delta.optJSONArray("items")
        for (i in 0 until (entries?.length() ?: 0)) {
            val e = entries!!.getJSONObject(i)
            val kind = items[e.optString("k")] ?: continue
            val id = e.getString("id"); val rev = e.optLong("rev")
            if (rev < (kind[id]?.rev ?: 0L)) continue
            if (e.has("del")) kind.remove(id) else kind[id] = Item(e.optLong("pos"), rev, e.getJSONObject("v"))
        }
        delta.optJSONObject("pins")?.let { pins = LibraryJson.strings(it.optJSONArray("pins")); pinsAt = it.optLong("at") }
    }

    /** One of this device's own operations, once the server accepted it at [rev]. */
    fun applyOwn(op: JSONObject, rev: Long) {
        when (val type = op.getString("t")) {
            "pl.put" -> {
                val fields = op.getJSONObject("v"); val id = fields.getString("id")
                if (rev < revisionOf(id)) return // A resent batch must not undo a newer version already held.
                val tracks = if (op.has("tracks")) LibraryJson.tracks(op.optJSONArray("tracks")) else playlists[id]?.second?.tracks.orEmpty()
                playlists[id] = rev to LibraryJson.playlist(fields, tracks); deleted.remove(id)
            }
            "pl.del" -> {
                val id = op.getString("key")
                if (rev < revisionOf(id)) return
                playlists.remove(id); deleted[id] = op.optLong("at") to rev
            }
            "pins.set" -> { pins = LibraryJson.strings(op.optJSONArray("pins")); pinsAt = op.optLong("at") }
            else -> {
                val kind = items[when (type.substringBefore('.')) { "fav" -> "f"; "alb" -> "a"; "art" -> "r"; else -> return }] ?: return
                if (type.endsWith(".del")) kind.remove(op.getString("key"))
                else {
                    val body = op.getJSONObject("v"); val id = body.getString("id")
                    kind[id] = Item(kind[id]?.pos ?: ((items.values.maxOfOrNull { k -> k.values.maxOfOrNull { it.pos } ?: 0L } ?: 0L) + 1), rev, body)
                }
            }
        }
    }

    fun encode(): String {
        val lists = JSONArray()
        for ((id, entry) in playlists) lists.put(LibraryJson.playlistFields(entry.second).put("id", id).put("rev", entry.first)
            .put("tracks", LibraryJson.tracks(entry.second.tracks)))
        for ((id, entry) in deleted) lists.put(JSONObject().put("id", id).put("rev", entry.second).put("deletedAt", entry.first))
        val entries = JSONArray()
        for ((kind, map) in items) for ((id, item) in map) entries.put(JSONObject().put("k", kind).put("id", id).put("rev", item.rev)
            .put("pos", item.pos).put("v", item.body))
        return JSONObject().put("cursor", cursor).put("epoch", epoch).put("playlists", lists).put("items", entries)
            .put("pins", JSONObject().put("pins", JSONArray(pins)).put("at", pinsAt)).toString()
    }

    companion object {
        /** A file that can't be read is treated as no mirror: the next sync fetches everything again. */
        fun decode(raw: String?): LibraryMirror = LibraryMirror().also { mirror ->
            runCatching {
                val json = JSONObject(raw?.takeIf { it.isNotBlank() } ?: return@also)
                mirror.apply(json)
                mirror.cursor = json.optLong("cursor"); mirror.epoch = json.optInt("epoch")
            }.onFailure { mirror.clear(); mirror.epoch = 0 }
        }
    }
}

/** The difference between the server's copy and what the merge wants it to be, as operations. */
object LibraryOps {
    private fun sameFields(a: Playlist, b: Playlist) =
        a.title == b.title && a.description == b.description && a.coverUrl == b.coverUrl && a.createdAt == b.createdAt && a.isCustom == b.isCustom

    fun diff(mirror: LibraryMirror, target: SyncedLibrary, newId: () -> String = { UUID.randomUUID().toString() }): List<JSONObject> {
        val ops = mutableListOf<JSONObject>()
        fun op(type: String) = JSONObject().put("id", newId()).put("t", type).also { ops += it }
        val wanted = target.playlists.associateBy { it.id }
        for (p in target.playlists) {
            val held = mirror.playlists[p.id]?.second
            // Order and repeats are part of a playlist, so the whole id sequence is compared.
            val sameTracks = held != null && held.tracks.map { it.id } == p.tracks.map { it.id }
            if (held != null && sameTracks && sameFields(held, p)) continue
            val put = op("pl.put").put("ifRev", mirror.revisionOf(p.id)).put("v", LibraryJson.playlistFields(p))
            if (!sameTracks) put.put("tracks", LibraryJson.tracks(p.tracks))
        }
        // Only a recorded deletion removes a playlist from the account: a playlist that is merely
        // absent from the merge result is left alone.
        for ((id, at) in target.deleted) {
            if (id in mirror.playlists && id !in wanted) op("pl.del").put("key", id).put("ifRev", mirror.revisionOf(id)).put("at", at)
        }
        fun <T> set(prefix: String, kind: String, list: List<T>, id: (T) -> String, json: (T) -> JSONObject) {
            val held = mirror.items.getValue(kind)
            val ids = HashSet<String>()
            for (item in list) if (ids.add(id(item)) && id(item) !in held) op("$prefix.put").put("v", json(item))
            for (key in held.keys) if (key !in ids) op("$prefix.del").put("key", key)
        }
        set("fav", "f", target.favorites, { it.id }, LibraryJson::track)
        set("alb", "a", target.albums, { it.id }, LibraryJson::album)
        set("art", "r", target.artists, { it.id }, LibraryJson::artist)
        if (target.pinsAt > mirror.pinsAt && (target.pins != mirror.pins || target.pinsAt != mirror.pinsAt)) {
            op("pins.set").put("pins", JSONArray(target.pins)).put("at", target.pinsAt)
        }
        return ops
    }
}

/**
 * One account library sync against the server, in the order the spec gives: resend what may not
 * have been acknowledged, pull, merge, push the difference. The caller applies [Result.plan] to
 * the device and keeps [SyncPlan.baseline] only when [Result.saved].
 */
class LibraryCloudSync(
    private val transport: LibraryTransport,
    private val store: LibraryCloudStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    /** @param refused operations the server would not take (over a limit, or malformed). */
    class Result(val plan: SyncPlan, val saved: Boolean, val refused: Int)

    private val lock = Mutex()
    private var loadedFor: String? = null
    private var loaded = LibraryMirror()

    private fun mirror(uid: String): LibraryMirror {
        if (loadedFor != uid) { loaded = LibraryMirror.decode(store.read(uid, MIRROR)); loadedFor = uid }
        return loaded
    }

    private fun persist(uid: String, mirror: LibraryMirror) = store.write(uid, MIRROR, mirror.encode())

    private suspend fun call(frame: JSONObject): JSONObject {
        val reply = transport.call(frame)
        if (reply.optString("t") != "err") return reply
        when (reply.optString("code")) {
            "authority", "epoch" -> throw LibraryAuthorityChanged(LibraryAuthority.of(reply.optString("state")), reply.optInt("epoch"))
            "version" -> throw LibraryUpdateRequired()
            else -> throw IOException("Library server refused: ${reply.optString("code")}")
        }
    }

    private suspend fun pull(mirror: LibraryMirror) {
        var full = false
        var frame = call(JSONObject().put("t", "pull").put("cursor", mirror.cursor))
        var guard = 0
        while (true) {
            if (frame.optString("t") == "reset") {
                // The server can no longer say what changed since this copy, even part-way through
                // the pages: start again from everything.
                if (++guard > 3) throw IOException("The library kept changing while it was being fetched")
                mirror.clear(); full = true
                frame = call(JSONObject().put("t", "pull").put("full", true))
                continue
            }
            if (frame.optString("t") != "delta") throw IOException("Unexpected answer to a pull: ${frame.optString("t")}")
            mirror.apply(frame)
            val more = frame.optJSONObject("more") ?: break
            frame = call(JSONObject().put("t", "pull").put("cursor", mirror.cursor).put("full", full).put("page", more))
        }
        mirror.cursor = frame.optLong("rev", mirror.cursor)
    }

    /** What a push did: whether anything was stale, and the operations the server will never take. */
    private class Pushed(val stale: Boolean, val refused: List<JSONObject>)

    /** Sends [ops] and records what the server accepted in the mirror. */
    private suspend fun push(mirror: LibraryMirror, epoch: Int, ops: List<JSONObject>): Pushed {
        val ack = call(JSONObject().put("t", "push").put("v", PROTOCOL).put("epoch", epoch).put("ops", JSONArray(ops)))
        val results = ack.optJSONArray("results") ?: JSONArray()
        val byId = ops.associateBy { it.getString("id") }
        var stale = false
        val refused = mutableListOf<JSONObject>()
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            when (result.optString("s")) {
                "ok", "dup" -> byId[result.optString("id")]?.let { mirror.applyOwn(it, result.optLong("rev")) }
                "stale" -> stale = true
                else -> byId[result.optString("id")]?.let { refused += it }
            }
        }
        // Only when this batch itself moved the revision from exactly this copy's cursor is the copy
        // still complete. A batch that changed nothing says nothing about other devices' edits.
        if (ack.optBoolean("changed") && ack.has("from") && ack.optLong("from") == mirror.cursor) mirror.cursor = ack.optLong("rev")
        return Pushed(stale, refused)
    }

    /**
     * The baseline to keep when some operations will never be accepted (too large, malformed):
     * everything else is agreed, but a refused entry must not count as agreed, or its absence on
     * the server would later read as a deletion and remove it from this device.
     */
    private fun withoutRefused(baseline: SyncBaseline, refused: List<JSONObject>): SyncBaseline {
        if (refused.isEmpty()) return baseline
        val playlists = baseline.playlists.toMutableMap()
        val favorites = baseline.favorites.toMutableSet(); val albums = baseline.albums.toMutableSet(); val artists = baseline.artists.toMutableSet()
        for (op in refused) {
            val type = op.optString("t")
            val key = op.optJSONObject("v")?.optString("id")?.takeIf { it.isNotEmpty() } ?: op.optString("key")
            when (type.substringBefore('.')) {
                "pl" -> playlists.remove(key)
                "fav" -> favorites.remove(key)
                "alb" -> albums.remove(key)
                "art" -> artists.remove(key)
            }
        }
        return SyncBaseline(playlists, favorites, albums, artists)
    }

    suspend fun sync(
        uid: String, epoch: Int, baseline: SyncBaseline?, protected: Set<String>,
        local: suspend () -> SyncedLibrary, duplicates: (SyncedLibrary) -> (Playlist) -> Boolean = { { false } }
    ): Result = lock.withLock {
        val mirror = mirror(uid)
        var outbox = store.read(uid, OUTBOX)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (mirror.epoch != epoch) {
            // A new epoch is a new history (an import or a rollback happened): nothing carries over.
            mirror.clear(); mirror.epoch = epoch; persist(uid, mirror)
            if (outbox != null) { store.write(uid, OUTBOX, null); outbox = null }
        }
        if (outbox != null) {
            // The last batch may have been stored without its acknowledgment arriving. Its ids
            // make sending it again harmless, while the server still remembers them. An older
            // batch is dropped instead: the merge below derives any edit still missing again.
            val pending = outbox.optJSONArray("ops")
            val fresh = now() - outbox.optLong("at", 0L) in 0 until OUTBOX_MAX_AGE_MS
            if (fresh && outbox.optInt("epoch") == epoch && pending != null && pending.length() > 0) {
                push(mirror, epoch, (0 until pending.length()).map { pending.getJSONObject(it) })
            }
            store.write(uid, OUTBOX, null)
        }
        var last: Result? = null
        for (round in 0 until MAX_ROUNDS) {
            pull(mirror)
            persist(uid, mirror)
            val mine = local()
            val plan = AccountLibraryMerge.merge(mine, mirror.library(), baseline, now(), protected, duplicates(mine))
            val ops = if (plan.accountChanged) LibraryOps.diff(mirror, plan.account, newId) else emptyList()
            if (ops.isEmpty()) return@withLock Result(plan, saved = true, refused = 0)
            var stale = false
            val refused = mutableListOf<JSONObject>()
            for (batch in batches(ops)) {
                store.write(uid, OUTBOX, JSONObject().put("epoch", epoch).put("at", now()).put("ops", JSONArray(batch)).toString())
                val pushed = push(mirror, epoch, batch)
                store.write(uid, OUTBOX, null)
                stale = stale || pushed.stale; refused += pushed.refused
            }
            persist(uid, mirror)
            // A refused entry stays on this device and out of the baseline; everything else is saved.
            last = Result(plan.copy(baseline = withoutRefused(plan.baseline, refused)), saved = !stale, refused = refused.size)
            // Another device changed a playlist first: merge with its version and send again.
            if (!stale) return@withLock last
        }
        last!!
    }

    /**
     * A change the server pushed. When it follows directly from what this device holds it is taken
     * as is, so the sync that follows needs nothing from the server's database.
     */
    suspend fun accept(uid: String, frame: JSONObject): Boolean = lock.withLock {
        val mirror = mirror(uid)
        val delta = frame.optJSONObject("delta") ?: return@withLock false
        if (frame.optLong("from", -1) != mirror.cursor) return@withLock false
        mirror.apply(delta)
        mirror.cursor = frame.optLong("rev")
        persist(uid, mirror)
        true
    }

    /** Batches stay under the server's operation count and well under its request size. */
    private fun batches(ops: List<JSONObject>): List<List<JSONObject>> {
        val out = mutableListOf<MutableList<JSONObject>>()
        var size = 0
        for (op in ops) {
            val bytes = op.toString().length
            if (out.isEmpty() || out.last().size >= BATCH || size + bytes > BATCH_BYTES) { out += mutableListOf<JSONObject>(); size = 0 }
            out.last() += op; size += bytes
        }
        return out
    }

    companion object {
        const val PROTOCOL = 1
        private const val MAX_ROUNDS = 3
        /** Below the server's 30-day memory of operation ids. */
        private const val OUTBOX_MAX_AGE_MS = 20L * 86_400_000L
        private const val BATCH = 400
        private const val BATCH_BYTES = 1_500_000
        private const val MIRROR = "library-mirror"
        private const val OUTBOX = "library-outbox"
    }
}
