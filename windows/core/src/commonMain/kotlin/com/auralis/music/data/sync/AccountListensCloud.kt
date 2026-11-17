package com.auralis.music.data.sync

import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// Listening history on the account sync service (docs/library-sync/account-sync-proposal.md).
// Events keep the Firestore day-document shape; the service merges by key, keeping the longer
// listen. Nothing here runs unless a build names the account sync service.

/** One listen, in the shape both apps already upload. [device] is set for listens sent by updated apps. */
data class ListenEvent(
    val trackId: String, val startedAt: Long, val listenedMs: Long,
    val title: String? = null, val artist: String? = null, val album: String? = null,
    val thumbnail: String? = null, val duration: Long = 0, val device: String? = null,
    val artistsJson: String? = null
) {
    val key: String get() = "${startedAt}_$trackId"

    fun toJson(): JSONObject = JSONObject().put("t", trackId).put("ts", startedAt).put("ms", listenedMs)
        .put("ti", title ?: JSONObject.NULL).put("ar", artist ?: JSONObject.NULL).put("al", album ?: JSONObject.NULL)
        .put("th", thumbnail ?: JSONObject.NULL).put("du", duration).apply {
            if (device != null) put("d", device)
            if (artistsJson != null) put("as", artistsJson)
        }

    companion object {
        private fun text(o: JSONObject, k: String) = o.optString(k).takeIf { !o.isNull(k) && it.isNotEmpty() && it != "null" }

        fun fromJson(o: JSONObject): ListenEvent? {
            val id = o.optString("t"); val ts = o.optLong("ts"); val ms = o.optLong("ms")
            if (id.isBlank() || ts <= 0 || ms <= 0) return null
            return ListenEvent(id, ts, ms, text(o, "ti"), text(o, "ar"), text(o, "al"), text(o, "th"), o.optLong("du"), text(o, "d"), text(o, "as"))
        }
    }
}

/** The service is out of import budget for today; try again after [retryAfterMs]. */
class ListensImportLater(val retryAfterMs: Long) : IOException("History copy paused until tomorrow")

/**
 * Picks which listens to send: only finished ones, each once (again only if it grew), in batches.
 * A listen is finished when a newer one exists or it stopped growing [settleMs] ago; one cut short
 * by a crash is finished at the next start, because it has stopped growing by then.
 */
class ListenBatcher(private val store: LibraryCloudStore, private val settleMs: Long = 60_000L, private val keepMs: Long = 7 * 86_400_000L) {
    private fun sent(uid: String): MutableMap<String, Long> = runCatching {
        val json = JSONObject(store.read(uid, SENT) ?: return@runCatching mutableMapOf())
        json.keys().asSequence().associateWith { json.getLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    /** Listens ready to send from [local] (this device's own, newest last), and not sent at this length. */
    fun ready(uid: String, local: List<ListenEvent>, now: Long): List<ListenEvent> {
        val done = sent(uid)
        val newest = local.maxOfOrNull { it.startedAt }
        return local.filter { e ->
            val finished = e.startedAt != newest || now - (e.startedAt + e.listenedMs) >= settleMs
            finished && (done[e.key] ?: 0L) < e.listenedMs
        }
    }

    /** Records that [events] were accepted, forgetting entries older than a week. */
    fun markSent(uid: String, events: List<ListenEvent>, now: Long) {
        val done = sent(uid)
        events.forEach { done[it.key] = maxOf(done[it.key] ?: 0L, it.listenedMs) }
        val kept = done.filterKeys { key -> key.substringBefore('_').toLongOrNull()?.let { now - it < keepMs } != false }
        store.write(uid, SENT, JSONObject(kept).toString())
    }

    private companion object { const val SENT = "listens-sent" }
}

/** What a pull brought: new or longer listens, the account's clear marker, and whether to start over. */
class ListensPulled(val events: List<ListenEvent>, val clearedBefore: Long, val reset: Boolean)

/** The listens topic: push batches, pull from a cursor, accept pushed changes, import, clear. */
class ListensCloudSync(
    private val store: LibraryCloudStore,
    private val call: suspend (uid: String, frame: JSONObject) -> JSONObject
) {
    private val lock = kotlinx.coroutines.sync.Mutex()

    private fun cursor(uid: String) = store.read(uid, CURSOR)?.toLongOrNull() ?: 0L
    private fun setCursor(uid: String, rev: Long) = store.write(uid, CURSOR, rev.toString())

    private suspend fun send(uid: String, frame: JSONObject): JSONObject {
        val reply = call(uid, frame.put("v", 2))
        if (reply.optString("t") == "err") when (reply.optString("code")) {
            "authority" -> throw LibraryAuthorityChanged(LibraryAuthority.of(reply.optString("state")), reply.optInt("epoch"))
            "later" -> throw ListensImportLater(reply.optLong("retryAfterMs", 3_600_000L))
            else -> throw IOException("Listening sync refused: ${reply.optString("code")}")
        }
        return reply
    }

    /** What the service knows about this account's history: revision, clear marker, import done, bridge. */
    suspend fun status(uid: String): JSONObject? =
        send(uid, JSONObject().put("t", "hello")).optJSONObject("topics")?.optJSONObject("listens")

    /**
     * Sends [events] in batches of up to 500. Each batch is one revision on the service and a few
     * rows. Returns how many batches were sent. Resending after a lost acknowledgment is harmless.
     */
    suspend fun push(uid: String, events: List<ListenEvent>, import: Boolean = false, legacy: Boolean = false): Int = lock.withLock {
        var batches = 0
        for (chunk in events.chunked(BATCH)) {
            val frame = JSONObject().put("t", "listens").put("events", JSONArray(chunk.map { it.toJson() }))
            if (import) frame.put("import", true)
            if (legacy) frame.put("legacy", true)
            val ack = send(uid, frame)
            // Only when this batch itself moved the revision from this device's cursor is nothing missed,
            // and only for this device's own listens: copied history and old apps' listens are not
            // held here yet, so the next pull must bring them.
            if (!import && !legacy && ack.optBoolean("changed") && ack.optLong("from", -1) == cursor(uid)) setCursor(uid, ack.optLong("rev"))
            batches++
        }
        batches
    }

    suspend fun markImported(uid: String) = lock.withLock {
        send(uid, JSONObject().put("t", "listens").put("events", JSONArray()).put("importDone", true)); Unit
    }

    /** Everything changed after this device's cursor, applied by [apply] before the cursor moves. */
    suspend fun pull(uid: String, apply: suspend (ListensPulled) -> Unit) = lock.withLock {
        var frame = send(uid, JSONObject().put("t", "listens.pull").put("cursor", cursor(uid)))
        var reset = false
        var guard = 0
        while (true) {
            if (frame.optString("t") == "reset") {
                if (++guard > 3) throw IOException("Listening history kept changing while it was fetched")
                reset = true
                frame = send(uid, JSONObject().put("t", "listens.pull").put("full", true))
                continue
            }
            val events = mutableListOf<ListenEvent>()
            val days = frame.optJSONArray("days") ?: JSONArray()
            for (i in 0 until days.length()) {
                val map = days.getJSONObject(i).optJSONObject("events") ?: continue
                map.keys().forEach { k -> map.optJSONObject(k)?.let(ListenEvent::fromJson)?.let(events::add) }
            }
            apply(ListensPulled(events, frame.optLong("clearedBefore"), reset))
            reset = false
            val more = frame.optString("more").takeIf { frame.has("more") && it.isNotEmpty() } ?: break
            frame = send(uid, JSONObject().put("t", "listens.pull").put("cursor", cursor(uid)).put("full", guard > 0).put("page", more))
        }
        setCursor(uid, frame.optLong("rev", cursor(uid)))
    }

    /**
     * A change another device made. When it follows directly from this device's cursor it is
     * applied from the frame itself, with no request; otherwise (a gap, a clear, a large batch) the
     * caller pulls. Returns whether it was applied here.
     */
    suspend fun accept(uid: String, frame: JSONObject, apply: suspend (ListensPulled) -> Unit): Boolean = lock.withLock {
        val days = frame.optJSONArray("days") ?: return@withLock false
        if (frame.has("clearedBefore") || frame.optLong("from", -1) != cursor(uid)) return@withLock false
        val events = mutableListOf<ListenEvent>()
        for (i in 0 until days.length()) {
            val map = days.getJSONObject(i).optJSONObject("events") ?: continue
            map.keys().forEach { k -> map.optJSONObject(k)?.let(ListenEvent::fromJson)?.let(events::add) }
        }
        apply(ListensPulled(events, 0L, false))
        setCursor(uid, frame.optLong("rev"))
        true
    }

    /**
     * Days changed after [fromRev], whole, without moving this device's cursor. Used after a bridge
     * restore to copy to Firestore what updated apps recorded while the bridge was retired.
     */
    suspend fun daysSince(uid: String, fromRev: Long, handle: suspend (day: String, events: List<ListenEvent>) -> Unit) = lock.withLock {
        var frame = send(uid, JSONObject().put("t", "listens.pull").put("cursor", fromRev))
        var full = false
        if (frame.optString("t") == "reset") { full = true; frame = send(uid, JSONObject().put("t", "listens.pull").put("full", true)) }
        while (true) {
            val days = frame.optJSONArray("days") ?: JSONArray()
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                val map = d.optJSONObject("events") ?: continue
                handle(d.getString("day"), map.keys().asSequence().mapNotNull { k -> map.optJSONObject(k)?.let(ListenEvent::fromJson) }.toList())
            }
            val more = frame.optString("more").takeIf { frame.has("more") && it.isNotEmpty() } ?: break
            frame = send(uid, JSONObject().put("t", "listens.pull").put("cursor", fromRev).put("full", full).put("page", more))
        }
    }

    /** The gap since [fromRev] is in Firestore now. */
    suspend fun markBackfilled(uid: String, fromRev: Long) = lock.withLock {
        send(uid, JSONObject().put("t", "listens").put("events", JSONArray()).put("backfillDone", true).put("backfillFrom", fromRev)); Unit
    }

    /** "Clear listening stats" for the whole account: everything before [before] goes on every device. */
    suspend fun clear(uid: String, before: Long): Long = lock.withLock {
        send(uid, JSONObject().put("t", "listens.clear").put("before", before)).optLong("clearedBefore")
    }

    private companion object {
        const val CURSOR = "listens-cursor"
        const val BATCH = 500
    }
}
