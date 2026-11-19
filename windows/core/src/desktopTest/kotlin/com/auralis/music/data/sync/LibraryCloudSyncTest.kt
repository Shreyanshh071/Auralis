package com.auralis.music.data.sync

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The same situations AccountLibraryMergeTest covers for Firestore, run through the Cloudflare
 * path: devices that only ever talk to a server with the real protocol's rules. The server here is
 * an in-memory stand-in; LibraryCloudEndToEndTest runs against the real one.
 */
class LibraryCloudSyncTest {
    private val uid = "user-1"
    private var clock = 1_000_000L

    private fun song(id: String) = Track(id = id, title = "Song $id", artist = "A", thumbnail = "t", duration = 180)
    private fun list(id: String, vararg songs: String, title: String = "List $id", createdAt: Long = 10) =
        Playlist(id, title, tracks = songs.map(::song), createdAt = createdAt)

    /** The protocol's rules, as connect-server/src/library.js applies them. */
    private inner class Server {
        var state = "cloudflare"; var epoch = 2; var rev = 0L; var floor = 0L
        var offline = false; var loseNextAck = false
        var pushes = 0; var scans = 0
        val playlists = LinkedHashMap<String, Triple<Long, JSONObject, JSONArray>>()
        val gone = HashMap<String, Pair<Long, Long>>()
        val items = HashMap<String, JSONObject>()
        val seen = HashMap<String, Long>()
        var pins = JSONArray(); var pinsAt = 0L; var pinsRev = 0L
        private var pos = 0L

        fun transport() = LibraryTransport { frame ->
            if (offline) throw IOException("offline")
            val reply = handle(JSONObject(frame.toString()))
            if (loseNextAck && frame.optString("t") == "push") { loseNextAck = false; throw IOException("acknowledgment lost") }
            JSONObject(reply.toString())
        }

        private fun refuse(code: String) = JSONObject().put("t", "err").put("code", code).put("state", state).put("epoch", epoch)

        private fun handle(frame: JSONObject): JSONObject = when (frame.getString("t")) {
            "push" -> push(frame)
            "pull" -> pull(frame)
            else -> refuse("unknown")
        }

        private fun push(frame: JSONObject): JSONObject {
            if (frame.optInt("v") != 1) return refuse("version")
            if (state != "cloudflare") return refuse("authority")
            if (frame.optInt("epoch") != epoch) return refuse("epoch")
            pushes++
            val ops = frame.getJSONArray("ops")
            val next = rev + 1
            var changed = false
            val results = JSONArray()
            for (i in 0 until ops.length()) {
                val op = ops.getJSONObject(i); val id = op.getString("id")
                seen[id]?.let { results.put(JSONObject().put("id", id).put("s", "dup").put("rev", it)); return@let null } ?: run {
                    val type = op.getString("t")
                    var outcome = "ok"; var at = next
                    when {
                        type == "pl.put" || type == "pl.del" -> {
                            val key = if (type == "pl.put") op.getJSONObject("v").getString("id") else op.getString("key")
                            val held = playlists[key]?.first ?: gone[key]?.second ?: 0L
                            if (held > op.getLong("ifRev")) { outcome = "stale"; at = held }
                            else if (type == "pl.del") {
                                if (playlists.remove(key) != null) { gone[key] = op.optLong("at") to next; changed = true }
                            } else {
                                val tracks = if (op.has("tracks")) op.getJSONArray("tracks") else playlists[key]?.third ?: JSONArray()
                                playlists[key] = Triple(next, op.getJSONObject("v"), tracks); gone.remove(key); changed = true
                            }
                        }
                        type == "pins.set" -> if (op.getLong("at") > pinsAt) { pins = op.getJSONArray("pins"); pinsAt = op.getLong("at"); pinsRev = next; changed = true }
                        op.optJSONObject("v")?.optString("id")?.startsWith("refused-") == true -> outcome = "bad"
                        else -> {
                            val kind = mapOf("fav" to "f", "alb" to "a", "art" to "r").getValue(type.substringBefore('.'))
                            if (type.endsWith(".del")) {
                                val key = "$kind:${op.getString("key")}"
                                if (items[key]?.has("del") == false) { items[key] = JSONObject().put("k", kind).put("id", op.getString("key")).put("rev", next).put("del", clock); changed = true }
                            } else {
                                val v = op.getJSONObject("v"); val key = "$kind:${v.getString("id")}"
                                if (items[key]?.has("del") != false) {
                                    items[key] = JSONObject().put("k", kind).put("id", v.getString("id")).put("rev", next).put("pos", ++pos).put("v", v); changed = true
                                }
                            }
                        }
                    }
                    if (outcome == "ok") seen[id] = next
                    if (outcome == "bad") at = 0
                    results.put(JSONObject().put("id", id).put("s", outcome).put("rev", at))
                }
            }
            val from = rev
            if (changed) rev = next
            return JSONObject().put("t", "ack").put("rev", rev).put("from", from).put("changed", changed).put("results", results)
        }

        private fun pull(frame: JSONObject): JSONObject {
            if (state != "cloudflare") return refuse("authority")
            val full = frame.optBoolean("full")
            val cursor = frame.optLong("cursor")
            if (!full && (cursor < floor || cursor > rev)) return JSONObject().put("t", "reset").put("rev", rev)
            val out = JSONObject().put("t", "delta").put("full", full).put("rev", rev).put("playlists", JSONArray()).put("items", JSONArray())
            if (!full && cursor == rev) return out
            scans++
            val since = if (full) -1 else cursor
            for ((id, p) in playlists) if (p.first > since) out.getJSONArray("playlists").put(JSONObject(p.second.toString()).put("id", id).put("rev", p.first).put("tracks", p.third))
            for ((id, g) in gone) if (g.second > since) out.getJSONArray("playlists").put(JSONObject().put("id", id).put("rev", g.second).put("deletedAt", g.first))
            for (item in items.values) if (item.getLong("rev") > since && !(full && item.has("del"))) out.getJSONArray("items").put(item)
            if (full || pinsRev > cursor) out.put("pins", JSONObject().put("pins", pins).put("at", pinsAt).put("rev", pinsRev))
            return out
        }

        /** The library as the migration left it. */
        fun import(library: SyncedLibrary) {
            rev += 1; floor = rev
            for (p in library.playlists) playlists[p.id] = Triple(rev, LibraryJson.playlistFields(p), LibraryJson.tracks(p.tracks))
            for ((id, at) in library.deleted) gone[id] = at to rev
            for (t in library.favorites) items["f:${t.id}"] = JSONObject().put("k", "f").put("id", t.id).put("rev", rev).put("pos", ++pos).put("v", LibraryJson.track(t))
            for (a in library.albums) items["a:${a.id}"] = JSONObject().put("k", "a").put("id", a.id).put("rev", rev).put("pos", ++pos).put("v", LibraryJson.album(a))
            for (a in library.artists) items["r:${a.id}"] = JSONObject().put("k", "r").put("id", a.id).put("rev", rev).put("pos", ++pos).put("v", LibraryJson.artist(a))
            pins = JSONArray(library.pins); pinsAt = library.pinsAt; pinsRev = rev
        }

        fun favorites() = items.values.filter { it.getString("k") == "f" && !it.has("del") }.sortedBy { it.getLong("pos") }.map { it.getString("id") }
        fun tracksOf(id: String) = LibraryJson.tracks(playlists.getValue(id).third).map { it.id }
    }

    private class MemoryStore : LibraryCloudStore {
        val files = HashMap<String, String>()
        override fun read(uid: String, name: String) = files["$uid/$name"]
        override fun write(uid: String, name: String, value: String?) { if (value == null) files.remove("$uid/$name") else files["$uid/$name"] = value }
    }

    /** A device: its local library, its baseline, and the same applying DesktopAccount does. */
    private inner class Device(private val server: Server, private val name: String, var library: SyncedLibrary = SyncedLibrary(),
        var baseline: SyncBaseline? = null) {
        val store = MemoryStore()
        private var ids = 0
        var engine = LibraryCloudSync(server.transport(), store, now = { clock }, newId = { "$name-${ids++}" })

        /** The app was closed and opened again: only what is on disk survives. */
        fun restart() { engine = LibraryCloudSync(server.transport(), store, now = { clock }, newId = { "$name-r${ids++}" }) }

        suspend fun sync(protected: Set<String> = emptySet(), account: String = uid): LibraryCloudSync.Result {
            val result = engine.sync(account, server.epoch, baseline, protected, { library })
            val plan = result.plan
            val lists = library.playlists.filter { it.id !in plan.deletePlaylists }.associateBy { it.id }.toMutableMap()
            plan.writePlaylists.forEach { lists[it.id] = it }
            library = library.copy(playlists = lists.values.toList(),
                favorites = library.favorites.filter { it.id !in plan.removeFavorites } + plan.addFavorites,
                albums = library.albums.filter { it.id !in plan.removeAlbums } + plan.addAlbums,
                artists = library.artists.filter { it.id !in plan.removeArtists } + plan.addArtists,
                pins = plan.pins, pinsAt = plan.pinsAt)
            if (result.saved) baseline = plan.baseline
            return result
        }

        fun likes() = library.favorites.map { it.id }
        fun tracks(id: String) = library.playlists.first { it.id == id }.tracks.map { it.id }
        fun lists() = library.playlists.map { it.id }.sorted()
    }

    private fun agreed(library: SyncedLibrary) = SyncBaseline(library.playlists.associate { it.id to AccountLibraryMerge.fingerprint(it) },
        library.favorites.mapTo(HashSet()) { it.id }, library.albums.mapTo(HashSet()) { it.id }, library.artists.mapTo(HashSet()) { it.id })

    // ---- the first sync after an account moves ---------------------------------------------------

    @Test fun `first sync with a trusted baseline sends this device's offline edits and nothing else`() = runTest {
        val before = SyncedLibrary(listOf(list("p1", "a", "b"), list("p2", "x"), list("p3", "y")), listOf(song("f1"), song("f2"), song("f3")),
            listOf(SavedAlbum("al1", "Album", savedAt = 1)), listOf(SavedArtist("ar1", "Artist", savedAt = 1)))
        val server = Server().apply { import(before) }
        // Offline since the last Firestore sync: unliked f2, liked f9, deleted p3, added a song to p1.
        val device = Device(server, "pc", before.copy(playlists = listOf(list("p1", "a", "b", "c"), list("p2", "x")),
            favorites = listOf(song("f1"), song("f3"), song("f9"))), agreed(before))
        assertTrue(device.sync().saved)
        assertEquals(listOf("f1", "f3", "f9"), server.favorites())
        assertEquals(listOf("a", "b", "c"), server.tracksOf("p1"))
        assertFalse("p3" in server.playlists)
        assertEquals(clock, server.gone.getValue("p3").first, "the deletion is recorded for other devices")
        assertEquals(listOf("x"), server.tracksOf("p2"), "what did not change was not rewritten")
        assertEquals(1, server.pushes)
    }

    @Test fun `first sync with no baseline deletes nothing and combines both sides`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a")), listOf(song("f1"), song("f2")))) }
        // A fresh install with one playlist made before signing in.
        val device = Device(server, "new-phone", SyncedLibrary(listOf(list("mine", "m")), listOf(song("f7"))))
        assertTrue(device.sync().saved)
        assertEquals(setOf("f1", "f2", "f7"), server.favorites().toSet())
        assertEquals(listOf("mine", "p1"), device.lists())
        assertEquals(setOf("mine", "p1"), server.playlists.keys)
        assertEquals(listOf("f7", "f1", "f2"), device.likes())
    }

    @Test fun `a reinstalled device with a stale baseline does not delete the library`() = runTest {
        val full = SyncedLibrary((1..6).map { list("p$it", "s$it") }, (1..8).map { song("f$it") })
        val server = Server().apply { import(full) }
        // The app's data was mostly lost, but the saved baseline survived.
        val device = Device(server, "pc", SyncedLibrary(listOf(list("p1", "s1")), listOf(song("f1"))), agreed(full))
        device.sync()
        assertEquals(6, server.playlists.size)
        assertEquals(8, server.favorites().size)
        assertEquals(6, device.lists().size)
        assertEquals(8, device.likes().size)
    }

    @Test fun `a fresh install restores the whole imported library, in order and with repeated songs`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a", "b", "a", "c"), list("imported:PL9", "z", title = "Mix", createdAt = 77)),
            listOf(song("f1"), song("f2"), song("f3")), listOf(SavedAlbum("al", "Album", "Artist", "th", 12, 5)), listOf(SavedArtist("ar", "Artist")),
            pins = listOf("p1"), pinsAt = 40)) }
        val device = Device(server, "phone")
        device.sync()
        assertEquals(listOf("a", "b", "a", "c"), device.tracks("p1"))
        val mix = device.library.playlists.first { it.id == "imported:PL9" }
        assertEquals(listOf("Mix", 77L), listOf(mix.title, mix.createdAt))
        assertEquals(listOf("f1", "f2", "f3"), device.likes())
        assertEquals(SavedAlbum("al", "Album", "Artist", "th", 12, 5), device.library.albums.single())
        assertEquals(listOf("p1"), device.library.pins)
        assertEquals(0, server.pushes, "restoring uploads nothing")
    }

    // ---- everyday syncing --------------------------------------------------------------------------

    @Test fun `edits on one device arrive on the other, and nothing echoes back`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a")), listOf(song("f1")))) }
        val phone = Device(server, "phone"); val pc = Device(server, "pc")
        phone.sync(); pc.sync()
        phone.library = phone.library.copy(playlists = listOf(list("p1", "b", "a", "b", title = "Renamed"), list("new", "n")),
            favorites = phone.library.favorites + song("f2"), albums = listOf(SavedAlbum("al", "Album", savedAt = 3)))
        phone.sync()
        pc.sync()
        assertEquals(listOf("b", "a", "b"), pc.tracks("p1"))
        assertEquals("Renamed", pc.library.playlists.first { it.id == "p1" }.title)
        assertEquals(listOf("new", "p1"), pc.lists())
        assertEquals(listOf("f1", "f2"), pc.likes())
        assertEquals("al", pc.library.albums.single().id)
        val pushes = server.pushes; val rev = server.rev
        repeat(3) { phone.sync(); pc.sync() }
        assertEquals(pushes, server.pushes, "applying a download is not an edit")
        assertEquals(rev, server.rev)
    }

    @Test fun `an unlike and a deletion reach the other device`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a"), list("p2", "b")), listOf(song("f1"), song("f2")))) }
        val phone = Device(server, "phone"); val pc = Device(server, "pc")
        phone.sync(); pc.sync()
        phone.library = phone.library.copy(playlists = phone.library.playlists.filter { it.id != "p2" }, favorites = listOf(song("f1")))
        phone.sync(); pc.sync()
        assertEquals(listOf("p1"), pc.lists())
        assertEquals(listOf("f1"), pc.likes())
    }

    @Test fun `a rename does not resend the songs`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a", "b")))) }
        val device = Device(server, "pc"); device.sync()
        val ops = LibraryOps.diff(LibraryMirror.decode(device.store.read(uid, "library-mirror")),
            device.library.copy(playlists = listOf(list("p1", "a", "b", title = "New name"))))
        assertEquals(1, ops.size)
        assertFalse(ops.single().has("tracks"))
        assertEquals(1L, ops.single().getLong("ifRev"))
    }

    @Test fun `two devices edit one playlist at once and both edits survive`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a")))) }
        val phone = Device(server, "phone"); val pc = Device(server, "pc")
        phone.sync(); pc.sync()
        phone.library = phone.library.copy(playlists = listOf(list("p1", "a", "from-phone")))
        pc.library = pc.library.copy(playlists = listOf(list("p1", "from-pc", "a")))
        phone.sync()
        // The PC's mirror is one revision behind, as when both pushed at the same moment.
        val result = pc.sync()
        assertTrue(result.saved)
        assertEquals(listOf("from-pc", "a", "from-phone"), server.tracksOf("p1"), "this device's order first, the other's extra song appended")
        phone.sync()
        assertEquals(listOf("from-pc", "a", "from-phone"), phone.tracks("p1"))
    }

    @Test fun `a push refused as stale is merged and sent again within one sync`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a")))) }
        val phone = Device(server, "phone"); val pc = Device(server, "pc")
        phone.sync(); pc.sync()
        pc.library = pc.library.copy(playlists = listOf(list("p1", "a", "pc")))
        // The phone's edit lands between the PC's pull and its push.
        var raced = false
        val racing = LibraryTransport { frame ->
            if (!raced && frame.optString("t") == "push") {
                raced = true
                phone.library = phone.library.copy(playlists = listOf(list("p1", "a", "phone")))
                phone.sync()
            }
            server.transport().call(frame)
        }
        pc.engine = LibraryCloudSync(racing, pc.store, now = { clock }, newId = { "pc-race-${System.nanoTime()}" })
        assertTrue(pc.sync().saved)
        assertEquals(listOf("a", "pc", "phone"), server.tracksOf("p1"))
    }

    @Test fun `a lost acknowledgment does not apply an edit twice or lose it`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a")))) }
        val device = Device(server, "pc"); device.sync()
        device.library = device.library.copy(playlists = listOf(list("p1", "a", "a")), favorites = listOf(song("f1")))
        server.loseNextAck = true
        assertFailsWith<IOException> { device.sync() }
        val rev = server.rev
        assertEquals(listOf("a", "a"), server.tracksOf("p1"), "the server stored it; only the answer was lost")
        device.restart()
        assertTrue(device.sync().saved)
        assertEquals(rev, server.rev, "the retry was recognised, not applied again")
        assertEquals(listOf("a", "a"), server.tracksOf("p1"))
        assertEquals(listOf("f1"), server.favorites())
        assertNull(device.store.read(uid, "library-outbox"))
    }

    @Test fun `while the server is unreachable edits stay on the device and go up later`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1")))) }
        val device = Device(server, "pc"); device.sync()
        server.offline = true
        device.library = device.library.copy(favorites = listOf(song("f2")))
        assertFailsWith<IOException> { device.sync() }
        assertEquals(listOf("f2"), device.likes(), "a failure is never an empty account")
        assertEquals(listOf("f1"), server.favorites())
        server.offline = false
        assertTrue(device.sync().saved)
        assertEquals(listOf("f2"), server.favorites(), "the unlike and the like both arrived")
    }

    @Test fun `a device offline past the change history starts from a snapshot and keeps its edits`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a")), listOf(song("f1"), song("f2")))) }
        val phone = Device(server, "phone"); val pc = Device(server, "pc")
        phone.sync(); pc.sync()
        pc.library = pc.library.copy(favorites = pc.library.favorites + song("offline-like"))
        phone.library = phone.library.copy(favorites = listOf(song("f1")), playlists = listOf(list("p1", "a", "b")))
        phone.sync()
        server.floor = server.rev // Everything the PC could have been told about is compacted away.
        assertTrue(pc.sync().saved)
        assertEquals(listOf("a", "b"), pc.tracks("p1"))
        assertTrue("offline-like" in server.favorites())
        assertFalse("f2" in pc.likes(), "its baseline still tells an unlike elsewhere from a new like here")
    }

    @Test fun `a pushed change that follows directly needs no further read from the server`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1")))) }
        val pc = Device(server, "pc"); pc.sync()
        val scans = server.scans
        val change = JSONObject().put("t", "changed").put("from", server.rev).put("rev", server.rev + 1).put("delta", JSONObject()
            .put("items", JSONArray().put(JSONObject().put("k", "f").put("id", "f2").put("rev", server.rev + 1).put("pos", 9).put("v", LibraryJson.track(song("f2"))))))
        server.items["f:f2"] = change.getJSONObject("delta").getJSONArray("items").getJSONObject(0); server.rev += 1
        assertTrue(pc.engine.accept(uid, change))
        pc.sync()
        assertEquals(listOf("f1", "f2"), pc.likes())
        assertEquals(scans, server.scans)
        assertFalse(pc.engine.accept(uid, JSONObject(change.toString()).put("from", 99)), "a change with a gap before it is fetched instead")
    }

    // ---- found by the independent review -----------------------------------------------------------

    @Test fun `a push that changes nothing does not skip another device's edits`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a")), listOf(song("f1")))) }
        val a = Device(server, "a"); val b = Device(server, "b")
        a.sync(); b.sync()
        // A likes X. Between A's pull and A's push, B likes X and Y and edits the playlist, so A's
        // push of X changes nothing on the server and its answer names B's revision.
        a.library = a.library.copy(favorites = a.library.favorites + song("x"))
        var raced = false
        val racing = LibraryTransport { frame ->
            if (!raced && frame.optString("t") == "push") {
                raced = true
                b.library = b.library.copy(favorites = b.library.favorites + song("x") + song("y"), playlists = listOf(list("p1", "a", "from-b")))
                b.sync()
            }
            server.transport().call(frame)
        }
        a.engine = LibraryCloudSync(racing, a.store, now = { clock }, newId = { "a-race-${System.nanoTime()}" })
        a.sync()
        a.sync()
        assertEquals(setOf("f1", "x", "y"), a.likes().toSet(), "Y from the other device still arrives")
        assertEquals(listOf("a", "from-b"), a.tracks("p1"))
        // And a later edit of that playlist is not stuck as stale.
        a.library = a.library.copy(playlists = listOf(list("p1", "a", "from-b", "from-a")))
        assertTrue(a.sync().saved)
        assertEquals(listOf("a", "from-b", "from-a"), server.tracksOf("p1"))
    }

    @Test fun `the engine's own no-op push leaves the cursor alone`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1")))) }
        val a = Device(server, "a"); val b = Device(server, "b")
        a.sync(); b.sync()
        b.library = b.library.copy(favorites = b.library.favorites + song("x") + song("y")); b.sync()
        // A liked X offline too; its sync pushes X (a no-op on the server) only if its pull missed it.
        a.library = a.library.copy(favorites = a.library.favorites + song("x"))
        a.sync()
        assertEquals(setOf("f1", "x", "y"), a.likes().toSet())
    }

    @Test fun `a reset in the middle of paging starts again from everything`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1"), song("f2")))) }
        val device = Device(server, "pc"); device.sync()
        server.items["f:f3"] = JSONObject().put("k", "f").put("id", "f3").put("rev", server.rev + 1).put("pos", 50).put("v", LibraryJson.track(song("f3")))
        server.rev += 1
        var calls = 0
        val paging = LibraryTransport { frame ->
            if (frame.optString("t") == "pull" && !frame.optBoolean("full")) {
                calls++
                // First page claims more is coming; the next page finds the history compacted.
                if (calls == 1) return@LibraryTransport JSONObject().put("t", "delta").put("rev", server.rev).put("playlists", JSONArray())
                    .put("items", JSONArray()).put("more", JSONObject().put("phase", 1).put("kind", "f").put("key", "a").put("rev", server.rev))
                return@LibraryTransport JSONObject().put("t", "reset").put("rev", server.rev)
            }
            server.transport().call(frame)
        }
        device.engine = LibraryCloudSync(paging, device.store, now = { clock })
        device.sync()
        assertEquals(listOf("f1", "f2", "f3"), device.likes(), "the entry behind the reset was fetched, not skipped")
    }

    @Test fun `an entry the server will never take keeps the rest in step and is not deleted later`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1"), song("f2")))) }
        val a = Device(server, "a"); val b = Device(server, "b")
        a.sync(); b.sync()
        a.library = a.library.copy(favorites = a.library.favorites + song("refused-huge") + song("ok"))
        val result = a.sync()
        assertTrue(result.saved, "everything else is agreed")
        assertEquals(1, result.refused)
        assertFalse("refused-huge" in a.baseline!!.favorites)
        assertTrue("ok" in a.baseline!!.favorites)
        // Another device then removes f2: A follows, and keeps its refused entry.
        b.sync(); b.library = b.library.copy(favorites = b.library.favorites.filter { it.id != "f2" }); b.sync()
        a.sync()
        assertFalse("f2" in a.likes(), "a removal elsewhere is not undone")
        assertTrue("refused-huge" in a.likes(), "the refused entry is kept on this device")
    }

    @Test fun `a batch left in the outbox for weeks is not resent`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1")))) }
        val device = Device(server, "pc"); device.sync()
        device.store.write(uid, "library-outbox", JSONObject().put("epoch", server.epoch).put("at", clock - 25L * 86_400_000L)
            .put("ops", JSONArray().put(JSONObject().put("id", "ancient").put("t", "fav.del").put("key", "f1"))).toString())
        device.restart(); device.sync()
        assertEquals(listOf("f1"), server.favorites(), "an outbox past the server's memory of ids is dropped, not replayed")
        assertNull(device.store.read(uid, "library-outbox"))
    }

    // ---- service-linked playlists ----------------------------------------------------------------

    @Test fun `an acknowledged phone removal reaches a YouTube linked desktop and is not resurrected`() = runTest {
        val start = SyncedLibrary(listOf(list("imported:PL1", "a", "b", "c")))
        val server = Server().apply { import(start) }
        val phone = Device(server, "phone", start, agreed(start)); val pc = Device(server, "pc", start, agreed(start))
        phone.library = SyncedLibrary(listOf(list("imported:PL1", "a", "d")))
        phone.sync()
        // Cloudflare is the account authority: a service link must not undo an account edit.
        pc.sync(protected = setOf("imported:PL1"))
        assertEquals(listOf("a", "d"), pc.tracks("imported:PL1"))
        phone.sync()
        assertEquals(listOf("a", "d"), phone.tracks("imported:PL1"), "the desktop must not upload the removed songs again")
        pc.library = SyncedLibrary(listOf(list("imported:PL1", "a")))
        pc.sync(protected = setOf("imported:PL1"))
        phone.sync()
        assertEquals(listOf("a"), phone.tracks("imported:PL1"), "the reverse direction still works")
    }

    @Test fun `an explicit account playlist deletion reaches a YouTube linked desktop`() = runTest {
        val start = SyncedLibrary(listOf(list("imported:PL1", "a"), list("keep1", "b"), list("keep2", "c"), list("keep3", "d")))
        val server = Server().apply { import(start) }
        val phone = Device(server, "phone", start, agreed(start))
        val pc = Device(server, "pc", start, agreed(start))
        phone.library = start.copy(playlists = start.playlists.filterNot { it.id == "imported:PL1" })
        phone.sync()
        pc.sync(protected = setOf("imported:PL1"))
        assertEquals(listOf("keep1", "keep2", "keep3"), pc.lists())
    }

    // ---- authority, epochs and accounts ----------------------------------------------------------

    @Test fun `an account that is not on Cloudflare refuses, and nothing changes on the device`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1")))) }
        val device = Device(server, "pc", SyncedLibrary(favorites = listOf(song("mine"))))
        server.state = "migrating"
        val refused = assertFailsWith<LibraryAuthorityChanged> { device.sync() }
        assertEquals(LibraryAuthority.MIGRATING, refused.state)
        assertEquals(listOf("mine"), device.likes())
        assertEquals(0, server.pushes)
    }

    @Test fun `a new epoch discards the old mirror and any batch in flight`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1")))) }
        val device = Device(server, "pc"); device.sync()
        device.store.write(uid, "library-outbox", JSONObject().put("epoch", 2).put("ops", JSONArray().put(JSONObject().put("id", "old").put("t", "fav.del").put("key", "f1"))).toString())
        // Rolled back and migrated again: the same account, a different history.
        val reimported = Server().apply { epoch = 4; import(SyncedLibrary(favorites = listOf(song("f1"), song("f5")))) }
        device.engine = LibraryCloudSync(reimported.transport(), device.store, now = { clock })
        val result = device.engine.sync(uid, 4, device.baseline, emptySet(), { device.library })
        assertEquals(listOf("f5"), result.plan.addFavorites.map { it.id })
        assertEquals(listOf("f1", "f5"), reimported.favorites(), "the stale batch was not sent into the new history")
    }

    @Test fun `a stale epoch is refused before anything is written`() = runTest {
        val server = Server().apply { import(SyncedLibrary()) }
        val device = Device(server, "pc", SyncedLibrary(favorites = listOf(song("f1"))))
        val refused = assertFailsWith<LibraryAuthorityChanged> { device.engine.sync(uid, 1, null, emptySet(), { device.library }) }
        assertEquals(2, refused.epoch)
        assertTrue(server.favorites().isEmpty())
    }

    @Test fun `one account's mirror and outbox are never used for another`() = runTest {
        val first = Server().apply { import(SyncedLibrary(favorites = listOf(song("alice-song")))) }
        val device = Device(first, "pc"); device.sync()
        // Sign out, then another account signs in on the same device with its own library.
        val second = Server().apply { import(SyncedLibrary(favorites = listOf(song("bob-song")))) }
        device.engine = LibraryCloudSync(second.transport(), device.store, now = { clock })
        device.library = SyncedLibrary(); device.baseline = null
        device.sync(account = "user-2")
        assertEquals(listOf("bob-song"), device.likes())
        assertEquals(listOf("bob-song"), second.favorites(), "nothing of the first account was uploaded to the second")
        assertTrue(device.store.files.keys.any { it.startsWith("user-1/") } && device.store.files.keys.any { it.startsWith("user-2/") })
    }

    @Test fun `an unreadable mirror file means fetching everything again, not an empty account`() = runTest {
        val server = Server().apply { import(SyncedLibrary(favorites = listOf(song("f1")))) }
        val device = Device(server, "pc"); device.sync()
        device.store.write(uid, "library-mirror", "{ not json")
        device.restart()
        device.sync()
        assertEquals(listOf("f1"), device.likes())
        assertEquals(listOf("f1"), server.favorites())
    }

    @Test fun `the newest pin order wins and an older one is not sent`() = runTest {
        val server = Server().apply { import(SyncedLibrary(listOf(list("p1", "a"), list("p2", "b")), pins = listOf("p1"), pinsAt = 100)) }
        val phone = Device(server, "phone"); phone.sync()
        phone.library = phone.library.copy(pins = listOf("p2", "p1"), pinsAt = 200)
        phone.sync()
        val pc = Device(server, "pc", SyncedLibrary(pins = listOf("old"), pinsAt = 50))
        pc.sync()
        assertEquals(listOf("p2", "p1"), pc.library.pins)
        assertEquals(200L, server.pinsAt)
    }

    // ---- discovery ---------------------------------------------------------------------------------

    @Test fun `with the rollout off every account stays on Firestore without asking anyone`() {
        assertEquals(LibraryRoute.Firestore, LibraryAuthorityPolicy.route(enabled = false, known = null, reachable = false, now = clock))
        assertEquals(LibraryRoute.Firestore, LibraryAuthorityPolicy.route(false, AuthorityAnswer(LibraryAuthority.CLOUDFLARE, 2, clock), true, clock))
    }

    @Test fun `not reaching discovery is never taken as legacy`() {
        val hour = 3_600_000L
        fun route(known: AuthorityAnswer?, reachable: Boolean, now: Long = clock) = LibraryAuthorityPolicy.route(true, known, reachable, now)
        assertEquals(LibraryRoute.Wait(migrating = false), route(null, reachable = false))
        assertEquals(LibraryRoute.Wait(false), route(AuthorityAnswer(LibraryAuthority.LEGACY, 0, clock), reachable = true))
        assertEquals(LibraryRoute.Wait(false), route(AuthorityAnswer(LibraryAuthority.LEGACY, 0, clock), false, clock + 5 * hour))
        assertEquals(LibraryRoute.Wait(false), route(AuthorityAnswer(LibraryAuthority.LEGACY, 0, clock), false, clock + 7 * hour))
        assertEquals(LibraryRoute.Wait(false), route(AuthorityAnswer(LibraryAuthority.LEGACY, 0, clock), false, clock - hour), "a clock set back is not trusted")
        assertEquals(LibraryRoute.Wait(migrating = true), route(AuthorityAnswer(LibraryAuthority.MIGRATING, 1, clock), true))
        assertEquals(LibraryRoute.Cloudflare(2), route(AuthorityAnswer(LibraryAuthority.CLOUDFLARE, 2, 0), false, clock + 900 * hour),
            "once on Cloudflare, an outage never sends the account back to Firestore")
    }

    @Test fun `an answer with a lower epoch is an old one arriving late`() {
        val current = AuthorityAnswer(LibraryAuthority.CLOUDFLARE, 2, 10)
        assertEquals(current, LibraryAuthorityPolicy.remember(current, AuthorityAnswer(LibraryAuthority.LEGACY, 0, 20)))
        val rolledBack = AuthorityAnswer(LibraryAuthority.LEGACY, 4, 30)
        assertEquals(rolledBack, LibraryAuthorityPolicy.remember(current, rolledBack))
        assertEquals(current, LibraryAuthorityPolicy.remember(current, null))
        assertEquals(current, AuthorityAnswer.decode(current.encode()))
        assertNull(AuthorityAnswer.decode("garbage"))
    }
}
