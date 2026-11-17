package com.auralis.music.data.sync

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The real client (HTTP calls, the notification socket, files on disk) against the real server.
 * Runs when AURALIS_CONNECT_PORT names a local `wrangler dev` (connect-server/test/serve.mjs starts
 * one, with the local-only admin secret). Nothing leaves this machine.
 */
class LibraryCloudEndToEndTest {
    // AURALIS_LIBRARY_URL + AURALIS_STAGING_SECRET + LIBRARY_ADMIN_SECRET target a deployed staging
    // Worker with its synthetic identities (staging-<id>, never a real account) instead.
    private val stagingSecret = System.getenv("AURALIS_STAGING_SECRET")?.takeIf { it.isNotBlank() }
    private val stagingUrl = System.getenv("AURALIS_LIBRARY_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() && stagingSecret != null }
    private val port: Int? = System.getenv("AURALIS_CONNECT_PORT")?.toIntOrNull() ?: stagingUrl?.let { 0 }
    private val base get() = stagingUrl ?: "http://127.0.0.1:$port"
    private val adminSecret = System.getenv("LIBRARY_ADMIN_SECRET")?.takeIf { it.isNotBlank() && stagingSecret != null } ?: "local-admin"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient()
    private val uid = if (stagingSecret != null) "staging-libe2e-${System.nanoTime().toString(36)}" else "libe2e" + System.nanoTime()
    private val bearer get() = if (stagingSecret != null) "staging:$stagingSecret:${uid.removePrefix("staging-")}" else "dev:$uid"

    @AfterTest fun stop() { scope.cancel() }

    private fun song(id: String) = Track(id = id, title = "Song $id", artist = "A", thumbnail = "t", duration = 180)

    private fun admin(action: String, body: JSONObject = JSONObject()): JSONObject {
        val request = Request.Builder().url("$base/v1/library/admin/$uid/$action").header("x-library-admin", adminSecret)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return http.newCall(request).execute().use { JSONObject(it.body!!.string()).put("http", it.code) }
    }

    /** The cutover for an account with no Firestore document. The checksum is of the empty library. */
    private fun migrateEmpty(): Int {
        assertEquals("migrating", admin("begin").getString("state"))
        val empty = JSONObject().put("playlists", listOf<Any>()).put("favorites", listOf<Any>()).put("albums", listOf<Any>())
            .put("artists", listOf<Any>()).put("pins", listOf<Any>()).put("pinsAt", 0).put("deleted", listOf<Any>())
        val imported = admin("import", JSONObject().put("library", empty).put("checksum", EMPTY).put("source", JSONObject().put("exists", false)))
        assertEquals(200, imported.getInt("http"), imported.toString())
        return admin("commit", JSONObject().put("checksum", EMPTY)).getInt("epoch")
    }

    private inner class Device(val name: String) {
        val dir = Files.createTempDirectory("auralis-library-$name").toFile()
        val changes = AtomicInteger()
        var library = SyncedLibrary()
        var baseline: SyncBaseline? = null
        val client = LibraryCloudClient(base, name, FileLibraryCloudStore(dir), http, scope, token = { _, _ -> bearer })

        suspend fun sync(): LibraryCloudSync.Result {
            val route = client.route(uid, check = true) as LibraryRoute.Cloudflare
            val result = client.sync(uid, route.epoch, baseline, emptySet(), { library })
            val plan = result.plan
            val lists = library.playlists.filter { it.id !in plan.deletePlaylists }.associateBy { it.id }.toMutableMap()
            plan.writePlaylists.forEach { lists[it.id] = it }
            library = library.copy(playlists = lists.values.toList(),
                favorites = library.favorites.filter { it.id !in plan.removeFavorites } + plan.addFavorites)
            if (result.saved) baseline = plan.baseline
            return result
        }
    }

    private suspend fun until(what: String, check: () -> Boolean) = withTimeout(10_000) { while (!check()) delay(50) }.let { assertTrue(check(), what) }

    @Test fun `discovery, sync between two devices, notifications and a frozen account`() {
        if (port == null) return
        runBlocking {
            val phone = Device("phone"); val pc = Device("pc")
            assertEquals(LibraryRoute.Firestore, phone.client.route(uid, check = true), "an account nobody migrated is legacy")
            assertEquals("migrating", admin("begin").getString("state"))
            assertEquals(LibraryRoute.Wait(migrating = true), phone.client.route(uid, check = true))
            admin("abort")
            val epoch = migrateEmpty()
            assertEquals(LibraryRoute.Cloudflare(epoch), phone.client.route(uid, check = true))

            pc.client.watch(uid) { pc.changes.incrementAndGet() }
            phone.library = SyncedLibrary(listOf(Playlist("p1", "Road", tracks = listOf(song("a"), song("b"), song("a")), createdAt = 5)), listOf(song("f1")))
            assertTrue(phone.sync().saved)
            until("the PC is told about the phone's edit") { pc.changes.get() > 0 }
            pc.sync()
            assertEquals(listOf("a", "b", "a"), pc.library.playlists.single().tracks.map { it.id })
            assertEquals(5L, pc.library.playlists.single().createdAt)
            assertEquals(listOf("f1"), pc.library.favorites.map { it.id })

            // Both edit the same playlist; the second to arrive merges and still lands.
            phone.library = phone.library.copy(playlists = listOf(phone.library.playlists.single().copy(tracks = listOf(song("a"), song("b"), song("a"), song("phone")))))
            pc.library = pc.library.copy(playlists = listOf(pc.library.playlists.single().copy(tracks = listOf(song("pc"), song("a"), song("b"), song("a")))))
            phone.sync()
            assertTrue(pc.sync().saved)
            phone.sync()
            assertEquals(listOf("pc", "a", "b", "a", "phone"), phone.library.playlists.single().tracks.map { it.id })

            // A second run of the same app reads its mirror from disk and has nothing to send.
            val status = admin("status")
            val again = LibraryCloudClient(base, "pc", FileLibraryCloudStore(pc.dir), http, scope, token = { _, _ -> bearer })
            again.sync(uid, epoch, pc.baseline, emptySet(), { pc.library })
            assertEquals(status.getLong("rev"), admin("status").getLong("rev"))

            // The account is frozen for a rollback: the watching device hears, and writes are refused.
            val before = pc.changes.get()
            admin("rollback-begin")
            until("the PC hears that the account moved") { pc.changes.get() > before }
            assertEquals(LibraryRoute.Wait(migrating = true), pc.client.route(uid))
            phone.library = phone.library.copy(favorites = phone.library.favorites + song("late"))
            assertFailsWith<LibraryAuthorityChanged> { phone.client.sync(uid, epoch, phone.baseline, emptySet(), { phone.library }) }
            assertEquals(LibraryRoute.Wait(migrating = true), phone.client.route(uid), "the refusal itself updated what the phone believes")
            pc.client.stopWatching()
        }
    }

    @Test fun `first connection catches an edit made after the initial pull and before watching`() {
        if (port == null) return
        runBlocking {
            migrateEmpty()
            val phone = Device("phone"); val pc = Device("pc")
            try {
                pc.sync()
                phone.library = SyncedLibrary(favorites = listOf(song("between-pull-and-socket")))
                phone.sync()
                pc.client.watch(uid) { pc.sync(); pc.changes.incrementAndGet() }
                until("opening the socket catches edits made before it existed") { pc.changes.get() > 0 }
                assertEquals(listOf("between-pull-and-socket"), pc.library.favorites.map { it.id })
            } finally { phone.client.stopAll(); pc.client.stopAll() }
        }
    }

    @Test fun `a topic added to an already open socket also catches up`() {
        if (port == null) return
        runBlocking {
            migrateEmpty()
            val pc = Device("pc")
            val settingsCatchUps = AtomicInteger()
            try {
                pc.client.watch(uid) { pc.changes.incrementAndGet() }
                until("first listener receives its opening catch-up") { pc.changes.get() > 0 }
                pc.client.listen(uid, "settings") { frame ->
                    if (frame == null) settingsCatchUps.incrementAndGet()
                }
                until("a later topic catches up without another remote edit or reconnect") { settingsCatchUps.get() > 0 }
            } finally { pc.client.stopAll() }
        }
    }

    @Test fun `deleting the account erases its library on the service and this device's files for it`() {
        if (port == null) return
        runBlocking {
            val phone = Device("phone")
            val epoch = migrateEmpty()
            phone.library = SyncedLibrary(favorites = listOf(song("f1")))
            phone.sync()
            assertTrue(phone.dir.listFiles().orEmpty().any { it.name.contains("library-mirror") })
            phone.client.erase(uid)
            assertEquals(0, admin("verify").getJSONObject("counts").getInt("favorites"))
            assertTrue(phone.dir.listFiles().orEmpty().none { it.name.startsWith(uid) }, "no copy of the deleted account's library stays on disk")
            assertEquals(LibraryRoute.Wait(migrating = true), phone.client.route(uid, check = true), "and nothing can be uploaded to it again")
            // A deletion that failed later (sign-in not deleted) can be retried.
            phone.client.erase(uid)
            assertFailsWith<LibraryAuthorityChanged> { phone.client.sync(uid, epoch, null, emptySet(), { phone.library }) }
        }
    }

    @Test fun `account deletion erases settings and listens before the library feature is enabled`() {
        if (port == null) return
        runBlocking {
            val dir = Files.createTempDirectory("account-only-erase").toFile()
            val client = LibraryCloudClient(base, "account-only", FileLibraryCloudStore(dir), http, scope,
                token = { _, _ -> bearer }, libraryEnabled = false)
            try {
                val settings = CloudPreferencesBackend("android") { u, frame -> client.call(u, frame) }
                val listens = ListensCloudSync(FileLibraryCloudStore(dir)) { u, frame -> client.call(u, frame) }
                settings.exchange(uid, mapOf("a.pure_black_mini_player" to AccountPreference("true", 1, "phone")), emptyMap())
                listens.push(uid, listOf(ListenEvent("fixture", System.currentTimeMillis() - 180_000, 120_000)))
                client.erase(uid)
                assertFailsWith<LibraryAuthorityChanged> { settings.exchange(uid, emptyMap(), emptyMap()) }
                assertFailsWith<LibraryAuthorityChanged> { listens.pull(uid) { } }
                client.erase(uid) // Still safe to retry if a later account-deletion step failed.
            } finally { client.stopAll(); dir.deleteRecursively() }
        }
    }

    @Test fun `erase also removes an archive left by a rollback, and fails when the service is unreachable`() {
        if (port == null) return
        runBlocking {
            val phone = Device("phone")
            migrateEmpty()
            phone.library = SyncedLibrary(favorites = listOf(song("kept")))
            phone.sync()
            admin("rollback-begin"); admin("rollback-commit")
            assertEquals(LibraryRoute.Firestore, phone.client.route(uid, check = true))
            phone.client.erase(uid)
            assertEquals(0, admin("verify").getJSONObject("counts").getInt("favorites"), "the archived rows went too")
            val unreachable = LibraryCloudClient("http://127.0.0.1:9", "phone", FileLibraryCloudStore(phone.dir), http, scope, token = { _, _ -> bearer })
            assertFailsWith<java.io.IOException> { unreachable.erase(uid) }
        }
    }

    @Test fun `the Android copy of this code is the same file`() {
        // Both apps must run the same protocol. Skipped when the Android sources are not checked out.
        for (name in listOf("LibraryCloud.kt", "LibraryCloudClient.kt", "AccountSettingsCloud.kt", "AccountListensCloud.kt")) {
            val here = java.io.File("src/commonMain/kotlin/com/auralis/music/data/sync/$name")
            val android = java.io.File("../../shared/src/commonMain/kotlin/com/auralis/music/data/sync/$name")
            if (!here.isFile || !android.isFile) continue
            assertEquals(here.readText(), android.readText(), "$name differs between windows/core and shared")
        }
    }

    @Test fun `settings changed on one device reach another in seconds through the shared socket`() {
        if (port == null) return
        runBlocking {
            fun device(name: String): Triple<LibraryCloudClient, AccountPreferencesSync, MutableMap<String, String>> {
                val values = mutableMapOf("a.lyrics_animation" to "dark")
                val client = LibraryCloudClient(base, name, FileLibraryCloudStore(Files.createTempDirectory("settings-$name").toFile()), http, scope,
                    token = { _, _ -> bearer }, libraryEnabled = false)
                return Triple(client, AccountPreferencesSync(object : AccountPreferencesLocal {
                    override suspend fun read() = values.toMap()
                    override suspend fun apply(incoming: Map<String, String>) { values.putAll(incoming) }
                }, CloudPreferencesBackend("windows") { u, f -> client.call(u, f) }, object : AccountPreferencesJournal {
                    var text: String? = null; override suspend fun read() = text; override suspend fun write(value: String) { text = value }
                }, name, pollMs = 6 * 3_600_000L), values)
            }
            val (pcClient, pc, pcValues) = device("pc1")
            val (otherClient, other, otherValues) = device("pc2")
            pc.step(uid); other.step(uid)
            otherClient.listen(uid, "settings") { other.refresh() }
            delay(800) // the socket opens
            pcValues["a.lyrics_animation"] = "light"
            withTimeout(5_000) { while (true) { pc.step(uid); other.step(uid); if (otherValues["a.lyrics_animation"] == "light") break; delay(200) } }
            assertEquals("light", otherValues["a.lyrics_animation"], "arrived by notification, with a 6-hour poll interval")
            assertTrue(pcClient.serviceConfigured && !pcClient.enabled, "settings without the library")
            otherClient.stopAll()
        }
    }

    @Test fun `finished listens reach another device in seconds, catch up after reconnecting, and a clear applies everywhere`() {
        if (port == null) return
        runBlocking {
            val dirA = Files.createTempDirectory("listens-a").toFile(); val dirB = Files.createTempDirectory("listens-b").toFile()
            val clientA = LibraryCloudClient(base, "pc1", FileLibraryCloudStore(dirA), http, scope, token = { _, _ -> bearer }, libraryEnabled = false)
            val clientB = LibraryCloudClient(base, "pc2", FileLibraryCloudStore(dirB), http, scope, token = { _, _ -> bearer }, libraryEnabled = false)
            val a = ListensCloudSync(FileLibraryCloudStore(dirA)) { u, f -> clientA.call(u, f) }
            val b = ListensCloudSync(FileLibraryCloudStore(dirB)) { u, f -> clientB.call(u, f) }
            val onB = java.util.concurrent.ConcurrentHashMap<String, ListenEvent>()
            var clearedOnB = 0L
            val applyB: suspend (ListensPulled) -> Unit = { p -> p.events.forEach { onB[it.key] = it }; if (p.clearedBefore > clearedOnB) {
                clearedOnB = p.clearedBefore; onB.values.removeIf { it.startedAt < p.clearedBefore } } }
            b.pull(uid, applyB)
            clientB.listen(uid, "listens") { frame -> if (frame == null || !b.accept(uid, frame, applyB)) b.pull(uid, applyB) }
            delay(800)
            val t = System.currentTimeMillis() - 3_600_000L
            val batch = listOf(ListenEvent("song-1", t, 180_000, "One", "A", device = "pc1"), ListenEvent("song-2", t + 200_000, 120_000, "Two", "A", device = "pc1"))
            assertEquals(1, a.push(uid, batch))
            withTimeout(5_000) { while (onB.size < 2) delay(50) }
            assertEquals(setOf("song-1", "song-2"), onB.values.map { it.trackId }.toSet(), "pushed with the events, no fetch needed")

            // B goes offline; A keeps listening; B catches up from its cursor when it comes back.
            clientB.stopAll()
            a.push(uid, listOf(ListenEvent("song-3", t + 400_000, 60_000, device = "pc1")))
            b.pull(uid, applyB)
            assertTrue(onB.values.any { it.trackId == "song-3" }, "missed while away, caught up on return")

            // A clears its stats: B drops everything before the marker.
            clientB.listen(uid, "listens") { frame -> if (frame == null || !b.accept(uid, frame, applyB)) b.pull(uid, applyB) }
            delay(800)
            a.clear(uid, t + 300_000)
            withTimeout(5_000) { while (clearedOnB == 0L) delay(50) }
            assertEquals(setOf("song-3"), onB.values.map { it.trackId }.toSet())
            clientB.stopAll()
        }
    }

    @Test fun `history import stops at the daily cap and resumes, and only finished listens are sent`() {
        if (port == null) return
        runBlocking {
            val dir = Files.createTempDirectory("listens-import").toFile()
            val client = LibraryCloudClient(base, "pc1", FileLibraryCloudStore(dir), http, scope, token = { _, _ -> bearer }, libraryEnabled = false)
            val sync = ListensCloudSync(FileLibraryCloudStore(dir)) { u, f -> client.call(u, f) }
            assertEquals(false, sync.status(uid)!!.optBoolean("imported"))
            val now = System.currentTimeMillis()
            val history = (1..20).map { ListenEvent("old-$it", now - it * 86_400_000L, 30_000) }
            sync.push(uid, history, import = true)
            sync.markImported(uid)
            assertEquals(true, sync.status(uid)!!.optBoolean("imported"))

            val batcher = ListenBatcher(FileLibraryCloudStore(dir))
            val playing = ListenEvent("now", now - 30_000, 25_000)
            val done = ListenEvent("earlier", now - 600_000, 200_000)
            assertEquals(listOf("earlier"), batcher.ready(uid, listOf(done, playing), now).map { it.trackId }, "the listen still playing waits")
            batcher.markSent(uid, listOf(done), now)
            assertEquals(emptyList(), batcher.ready(uid, listOf(done, playing), now).map { it.trackId }, "sent once")
            assertEquals(listOf("now"), batcher.ready(uid, listOf(done, playing), now + 120_000).map { it.trackId }, "finished once it stopped growing")
        }
    }

    @Test fun `a wrong or missing sign-in gets nothing`() {
        if (port == null) return
        runBlocking {
            val dir = Files.createTempDirectory("auralis-library-anon").toFile()
            val nobody = LibraryCloudClient(base, "pc", FileLibraryCloudStore(dir), http, scope, token = { _, _ -> null })
            assertEquals(LibraryRoute.Wait(migrating = false), nobody.route(uid, check = true), "no answer is not legacy")
            val refused = LibraryCloudClient(base, "pc", FileLibraryCloudStore(dir), http, scope, token = { _, _ -> "not-a-token" })
            assertEquals(LibraryRoute.Wait(migrating = false), refused.route(uid, check = true))
        }
    }

    private companion object {
        /** SHA-256 of the canonical empty library (connect-server/src/library-core.js). */
        const val EMPTY = "b4237b833c03db3194f911f5da3866e6b4c22451149d5919e79a412eb674dc46"
    }
}
