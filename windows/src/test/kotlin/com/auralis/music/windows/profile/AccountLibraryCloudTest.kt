package com.auralis.music.windows.profile

import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.together.FirestoreRest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * The desktop account choosing between Firestore and the Cloudflare library. The tests that need
 * the library server run when AURALIS_CONNECT_PORT names a local `wrangler dev`
 * (connect-server/test/serve.mjs); the others always run.
 */
class AccountLibraryCloudTest {
    private val port = System.getenv("AURALIS_CONNECT_PORT")?.toIntOrNull()
    private val http = OkHttpClient()

    /**
     * Where the library service runs. AURALIS_LIBRARY_URL with AURALIS_STAGING_SECRET and
     * LIBRARY_ADMIN_SECRET targets a deployed staging Worker with its synthetic identities
     * (staging-<id>, never a real account); otherwise a local `wrangler dev` on AURALIS_CONNECT_PORT.
     */
    private val stagingSecret = System.getenv("AURALIS_STAGING_SECRET")?.takeIf { it.isNotBlank() }
    private val base: String? = System.getenv("AURALIS_LIBRARY_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() && stagingSecret != null }
        ?: port?.let { "http://127.0.0.1:$it" }
    private val adminSecret = System.getenv("LIBRARY_ADMIN_SECRET")?.takeIf { it.isNotBlank() && stagingSecret != null } ?: "local-admin"
    private fun newUid(name: String) = if (stagingSecret != null) "staging-$name-${System.nanoTime().toString(36)}".take(48) else name + System.nanoTime()
    private fun tokenFor(uid: String) = if (stagingSecret != null) "staging:$stagingSecret:${uid.removePrefix("staging-")}" else "dev:$uid"

    private inner class Fixture(libraryUrl: String, val uid: String = newUid("desk")) {
        private val token = tokenFor(uid)
        val dir = java.nio.file.Files.createTempDirectory("account-library-cloud").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val library = DesktopLibraryRepository(File(dir, "library.json"))
        val stats = DesktopListeningStats(File(dir, "listens.json"))
        var denyWrites = false
        var libraryReads = 0
        var libraryWrites = 0
        var cloud = JSONObject().put("favorites", JSONArray())
        val api = FirestoreRest("test", "key") { url, _, body, _ -> when {
            url.contains("signInWithPassword") -> JSONObject().put("idToken", token).put("refreshToken", "refresh")
                .put("localId", uid).put("expiresIn", "3600")
            url.contains("securetoken.googleapis.com") -> JSONObject().put("id_token", token).put("refresh_token", "refresh")
                .put("user_id", uid).put("expires_in", "3600")
            url.contains("accounts:lookup") -> JSONObject("""{"users":[{"email":"test@example.com","displayName":"Listener"}]}""")
            url.contains("/listening?") -> JSONObject().put("documents", JSONArray())
            url.endsWith("/users/$uid") -> { libraryReads++; JSONObject().put("fields", FirestoreRest.encodeFields(cloud)) }
            url.endsWith(":commit") -> {
                // What the security-rule fence answers once the account's library has moved.
                if (denyWrites) throw FirestoreRest.ServiceException(403, "Missing or insufficient permissions.")
                libraryWrites++
                cloud = FirestoreRest.decodeFields(body!!.getJSONArray("writes").getJSONObject(0).getJSONObject("update").getJSONObject("fields"))
                JSONObject()
            }
            else -> error("Unexpected test request $url")
        } }
        val account = DesktopAccount(File(dir, "session.bin"), scope, api, libraryUrl = libraryUrl)
        suspend fun signIn() {
            account.signIn("test@example.com", "password", false)
            withTimeout(5000) { account.state.first { it.profile != null } }
        }
        suspend fun sync() = account.syncCloudLibrary(library, stats)
        suspend fun likes() = library.getFavoriteTracks().first().map { it.id }.toSet()
        fun firestoreLikes() = cloud.getJSONArray("favorites").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }

        fun admin(action: String, body: JSONObject = JSONObject()): JSONObject {
            val request = Request.Builder().url("$base/v1/library/admin/$uid/$action").header("x-library-admin", adminSecret)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return http.newCall(request).execute().use { JSONObject(it.body!!.string()) }
        }
        fun migrateEmpty(): Int {
            admin("begin")
            val empty = JSONObject().put("playlists", listOf<Any>()).put("favorites", listOf<Any>()).put("albums", listOf<Any>())
                .put("artists", listOf<Any>()).put("pins", listOf<Any>()).put("pinsAt", 0).put("deleted", listOf<Any>())
            admin("import", JSONObject().put("library", empty).put("checksum", EMPTY).put("source", JSONObject().put("exists", false)))
            return admin("commit", JSONObject().put("checksum", EMPTY)).getInt("epoch")
        }
        fun serverLikes() = admin("export").getJSONObject("library").getJSONArray("favorites").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }
        /** Another device of the same account liking a song. */
        fun phoneLikes(id: String, epoch: Int) {
            val frame = JSONObject().put("t", "push").put("v", 1).put("epoch", epoch).put("ops", JSONArray().put(JSONObject()
                .put("id", "phone-$id").put("t", "fav.put").put("v", JSONObject().put("id", id).put("title", id).put("artist", "Artist"))))
            val request = Request.Builder().url("$base/v1/library/rpc?device=phone").header("Authorization", "Bearer $token")
                .post(frame.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { assertEquals(200, it.code) }
        }
        fun close() { scope.cancel(); dir.deleteRecursively() }
    }

    private fun like(id: String) = Track(id, id, "Artist")

    @Test fun `with the rollout off the library syncs through Firestore and nothing else is contacted`() = runBlocking {
        val f = Fixture(libraryUrl = "")
        try {
            f.signIn()
            assertTrue(f.sync())
            f.library.setFavorite(like("local"), true)
            assertTrue(f.sync())
            assertEquals(setOf("local"), f.firestoreLikes())
            assertFalse(f.account.libraryNotifies)
            assertFalse(File(f.dir, "library-cloud").exists(), "no discovery answer, mirror or outbox is ever written")
        } finally { f.close() }
    }

    @Test fun `an unreachable discovery service is not legacy, so nothing is read or written anywhere`() = runBlocking {
        // A port nothing listens on: discovery can't answer and nothing was ever cached.
        val f = Fixture(libraryUrl = "http://127.0.0.1:9")
        try {
            f.signIn()
            f.library.setFavorite(like("local"), true)
            f.cloud.put("favorites", JSONArray().put(JSONObject().put("id", "stale-copy").put("title", "x").put("artist", "y")))
            assertFalse(f.sync())
            assertEquals(0, f.libraryReads, "the Firestore library is not read while its status is unknown")
            assertEquals(0, f.libraryWrites)
            assertEquals(setOf("local"), f.likes(), "the edit stays on this device")
            assertTrue(f.account.state.value.syncMessage!!.contains("Couldn't check where your library syncs"))
        } finally { f.close() }
    }

    @Test fun `an account still on Firestore keeps syncing there after discovery is switched on`() = runBlocking {
        if (base == null) return@runBlocking
        val f = Fixture(base)
        try {
            f.signIn()
            f.library.setFavorite(like("local"), true)
            assertTrue(f.sync())
            assertEquals(setOf("local"), f.firestoreLikes())
            assertEquals(emptySet(), f.serverLikes())
            assertFalse(f.account.libraryNotifies)
        } finally { f.close() }
    }

    @Test fun `a migrated account syncs with the library server and never writes its library to Firestore`() = runBlocking {
        if (base == null) return@runBlocking
        val f = Fixture(base)
        try {
            val epoch = f.migrateEmpty()
            f.signIn()
            // The frozen Firestore copy must not be restored onto this device.
            f.cloud.put("favorites", JSONArray().put(JSONObject().put("id", "frozen").put("title", "x").put("artist", "y")))
            val notified = AtomicInteger()
            f.account.onLibraryChanged = { if (f.sync()) notified.incrementAndGet() }
            f.library.setFavorite(like("local"), true)
            assertTrue(f.sync())
            assertEquals(setOf("local"), f.serverLikes())
            assertEquals(setOf("local"), f.likes())
            assertEquals(0, f.libraryReads); assertEquals(0, f.libraryWrites)
            assertTrue(f.account.libraryNotifies)
            assertNull(f.account.state.value.syncMessage)

            // Another device likes a song: this one hears about it and applies it, with no polling.
            delay(500) // let the notification socket open
            val syncsBefore = notified.get()
            f.phoneLikes("from-phone", epoch)
            // The socket's opening catch-up also syncs, so wait for the like itself, delivered by a
            // notification-driven sync (this test runs no periodic check).
            withTimeout(10_000) { while (f.likes() != setOf("local", "from-phone")) delay(50) }
            assertTrue(notified.get() > syncsBefore, "applied by a sync the notification triggered")
            assertEquals(setOf("local", "from-phone"), f.likes())

            // Unliking here removes it for the account too.
            f.library.setFavorite(like("from-phone"), false)
            assertTrue(f.sync())
            assertEquals(setOf("local"), f.serverLikes())
        } finally { f.close() }
    }

    @Test fun `while an account is being moved nothing is uploaded anywhere and the edit is kept`() = runBlocking {
        if (base == null) return@runBlocking
        val f = Fixture(base)
        try {
            f.admin("begin")
            f.signIn()
            f.library.setFavorite(like("during-move"), true)
            assertFalse(f.sync())
            assertEquals(0, f.libraryReads); assertEquals(0, f.libraryWrites)
            assertTrue(f.account.state.value.syncMessage!!.contains("being moved"))
            // The move finishes: the queued edit goes to the new home on the next sync.
            f.admin("abort"); f.migrateEmpty()
            assertTrue(f.sync())
            assertEquals(setOf("during-move"), f.serverLikes())
            assertEquals(0, f.libraryWrites)
        } finally { f.close() }
    }

    @Test fun `a write the fence refuses sends the device back to discovery, then to the new library`() = runBlocking {
        if (base == null) return@runBlocking
        val f = Fixture(base)
        try {
            f.signIn()
            assertTrue(f.sync(), "legacy at first, and that answer is now cached")
            // The account is migrated while this device keeps running with its cached answer.
            f.migrateEmpty()
            f.denyWrites = true
            f.library.setFavorite(like("after-cutover"), true)
            assertFalse(f.sync())
            assertEquals(setOf("after-cutover"), f.likes())
            assertTrue(f.sync(), "the refusal made it ask again, and it found the library server")
            assertEquals(setOf("after-cutover"), f.serverLikes())
            assertEquals(0, f.libraryWrites)
        } finally { f.close() }
    }

    @Test fun `two desktop instances of one account stay in step, and a different account sees none of it`() = runBlocking {
        if (base == null) return@runBlocking
        val first = Fixture(base)
        val second = Fixture(base, uid = first.uid)
        val stranger = Fixture(base)
        try {
            first.migrateEmpty(); stranger.migrateEmpty()
            first.signIn(); second.signIn(); stranger.signIn()
            val heard = AtomicInteger()
            second.account.onLibraryChanged = { if (second.sync()) heard.incrementAndGet() }
            assertTrue(first.sync()); assertTrue(second.sync()); assertTrue(stranger.sync())

            // A like and a playlist on the first PC reach the second by notification, with no polling.
            first.library.setFavorite(like("liked-on-pc1"), true)
            val made = first.library.createPlaylist("Synthetic mix", null, null)
            first.library.replacePlaylistTracks(made.id, listOf(like("t1"), like("t2"), like("t1")))
            assertTrue(first.sync())
            withTimeout(15_000) { while (heard.get() == 0 || second.likes() != setOf("liked-on-pc1")) delay(100) }
            assertEquals(listOf("t1", "t2", "t1"), second.library.getPlaylist(made.id).first()!!.tracks.map { it.id })

            // Both edit the same playlist; both edits survive, and the two copies end up equal.
            first.library.replacePlaylistTracks(made.id, listOf(like("t1"), like("t2"), like("t1"), like("from-pc1")))
            second.library.replacePlaylistTracks(made.id, listOf(like("from-pc2"), like("t1"), like("t2"), like("t1")))
            assertTrue(first.sync()); assertTrue(second.sync()); assertTrue(first.sync())
            val one = first.library.getPlaylist(made.id).first()!!.tracks.map { it.id }
            val two = second.library.getPlaylist(made.id).first()!!.tracks.map { it.id }
            assertEquals(one, two)
            assertTrue("from-pc1" in one && "from-pc2" in one, "both edits kept: $one")

            // An unlike on the second PC removes it on the first.
            second.library.setFavorite(like("liked-on-pc1"), false)
            assertTrue(second.sync()); assertTrue(first.sync())
            assertEquals(emptySet(), first.likes())

            // Another account, signed in on a third PC, has none of this.
            assertTrue(stranger.sync())
            assertEquals(emptySet(), stranger.likes())
            assertTrue(stranger.library.getPlaylists().first().none { it.id == made.id })
            assertEquals(0, first.libraryWrites + second.libraryWrites + stranger.libraryWrites, "no Firestore library writes on the new path")
        } finally { first.close(); second.close(); stranger.close() }
    }

    @Test fun `library checks back off after failures and stop when changes are notified`() {
        val minute = 60_000L
        val schedule = LibraryCheckSchedule()
        var now = 0L
        assertFalse(schedule.due(now, notified = false))
        now += 14 * minute; assertFalse(schedule.due(now, false))
        now += minute; assertTrue(schedule.due(now, false), "a healthy Firestore account is checked every 15 minutes")
        schedule.done(now, succeeded = false)
        val retries = mutableListOf<Long>()
        repeat(6) {
            val failedAt = now
            while (!schedule.due(now, false)) now += minute
            retries += (now - failedAt) / minute
            schedule.done(now, succeeded = false)
        }
        assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 15L), retries)
        schedule.done(now, succeeded = true)
        now += 60 * minute
        assertFalse(schedule.due(now, notified = true), "nothing is polled while the server notifies")
        assertTrue(schedule.due(now, notified = false))
    }

    private companion object {
        const val EMPTY = "b4237b833c03db3194f911f5da3866e6b4c22451149d5919e79a412eb674dc46"
    }
}
