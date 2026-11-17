package com.auralis.music.windows.profile

import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.Track
import com.auralis.music.windows.together.FirestoreRest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Listening history between real DesktopAccount instances through the account sync service on a
 * local `wrangler dev` (AURALIS_CONNECT_PORT; skipped otherwise), with a stand-in Firestore that
 * counts billed operations. Firestore holds the old apps' copy.
 */
class AccountListensCloudTest {
    private val port = System.getenv("AURALIS_CONNECT_PORT")?.toIntOrNull()
    // AURALIS_LIBRARY_URL + AURALIS_STAGING_SECRET + LIBRARY_ADMIN_SECRET target a deployed staging
    // Worker with its synthetic identities (staging-<id>, never a real account).
    private val stagingSecret = System.getenv("AURALIS_STAGING_SECRET")?.takeIf { it.isNotBlank() }
    private val base: String? = System.getenv("AURALIS_LIBRARY_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() && stagingSecret != null }
        ?: port?.let { "http://127.0.0.1:$it" }
    private val adminSecret = System.getenv("LIBRARY_ADMIN_SECRET")?.takeIf { it.isNotBlank() && stagingSecret != null } ?: "local-admin"
    private val uid = if (stagingSecret != null) "staging-listens-${System.nanoTime().toString(36)}" else "listens" + System.nanoTime()
    private val token = if (stagingSecret != null) "staging:$stagingSecret:${uid.removePrefix("staging-")}" else "dev:$uid"

    /** users/{uid}/listening/{day} documents, shared by every fixture, as Firestore would be. */
    private val days = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
    private val reads = AtomicInteger(); private val writes = AtomicInteger()

    private fun dayDoc(path: String, fields: JSONObject) = JSONObject().put("name", "projects/test/databases/(default)/documents/$path")
        .put("fields", FirestoreRest.encodeFields(fields))

    private inner class Desktop(name: String) {
        val dir = java.nio.file.Files.createTempDirectory("listens-$name").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stats = DesktopListeningStats(File(dir, "listens.json"))
        val api = FirestoreRest("test", "key") { url, method, body, _ -> when {
            url.contains("signInWithPassword") -> JSONObject().put("idToken", token).put("refreshToken", "refresh").put("localId", uid).put("expiresIn", "3600")
            url.contains("securetoken.googleapis.com") -> JSONObject().put("id_token", token).put("refresh_token", "refresh").put("user_id", uid).put("expires_in", "3600")
            url.contains("accounts:lookup") -> JSONObject("""{"users":[{"email":"test@example.com","displayName":"Listener"}]}""")
            url.endsWith("/users/$uid") -> { reads.incrementAndGet(); JSONObject().put("fields", FirestoreRest.encodeFields(JSONObject())) }
            url.contains("/users/$uid/listening?") -> {
                reads.addAndGet(maxOf(1, days.size))
                JSONObject().put("documents", JSONArray(days.map { (d, f) -> dayDoc("users/$uid/listening/$d", f) }))
            }
            url.endsWith(":runQuery") -> {
                val after = body!!.getJSONObject("structuredQuery").getJSONObject("where").getJSONObject("fieldFilter")
                    .getJSONObject("value").getString("integerValue").toLong()
                val hits = days.filter { it.value.optLong("updatedAt") > after }
                reads.addAndGet(maxOf(1, hits.size))
                JSONObject().put("results", JSONArray(hits.map { (d, f) -> JSONObject().put("document", dayDoc("users/$uid/listening/$d", f)) }))
            }
            url.endsWith(":commit") -> {
                val write = body!!.getJSONArray("writes").getJSONObject(0)
                val day = write.getJSONObject("update").getString("name").substringAfterLast('/')
                val fields = FirestoreRest.decodeFields(write.getJSONObject("update").getJSONObject("fields"))
                val held = days[day] ?: JSONObject().put("events", JSONObject())
                fields.optJSONObject("events")?.let { e -> e.keys().forEach { k -> held.getJSONObject("events").put(k, e.get(k)) } }
                held.put("updatedAt", fields.optLong("updatedAt")); days[day] = held
                writes.incrementAndGet(); JSONObject()
            }
            else -> error("Unexpected request $method $url")
        } }
        val account = DesktopAccount(File(dir, "session.bin"), scope, api, accountSyncUrl = base!!)
        suspend fun signIn() { account.signIn("test@example.com", "password", false); withTimeout(5000) { account.state.first { it.profile != null } } }
        fun ids() = stats.listens.value.map { it.track.id }.toSet()
        fun close() { account.accountSync.stopAll(); scope.cancel(); dir.deleteRecursively() }
    }

    private fun oldAppListen(trackId: String, ts: Long, ms: Long) {
        val day = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(ts))
        val doc = days[day] ?: JSONObject().put("events", JSONObject())
        doc.getJSONObject("events").put("${ts}_$trackId", JSONObject().put("t", trackId).put("ts", ts).put("ms", ms).put("ti", trackId).put("ar", "Old"))
        doc.put("updatedAt", System.currentTimeMillis()); days[day] = doc
    }

    @Test fun `history moves to the service once, then listens travel between desktops by notification`() = runBlocking {
        if (base == null) return@runBlocking
        val now = System.currentTimeMillis()
        oldAppListen("history-1", now - 3 * 86_400_000L, 200_000)
        oldAppListen("history-2", now - 2 * 86_400_000L, 100_000)
        val a = Desktop("a"); val b = Desktop("b")
        try {
            a.signIn(); b.signIn()
            assertTrue(a.account.listensOnCloud())
            a.account.syncListensCloud(a.stats)
            assertEquals(setOf("history-1", "history-2"), a.ids(), "the Firestore history was copied once and restored")
            b.account.syncListensCloud(b.stats)
            assertEquals(setOf("history-1", "history-2"), b.ids(), "the second desktop restores from the service")

            val heard = AtomicInteger()
            b.account.accountSync.listen(uid, "listens") { frame -> b.account.listensChanged(frame, b.stats); heard.incrementAndGet() }
            delay(800)
            val readsBefore = reads.get()
            a.stats.record("rec-1", Track("song-done", "Done", "A"), now - 600_000, 180_000)
            a.stats.record("rec-2", Track("song-playing", "Playing", "A"), now - 20_000, 15_000)
            val sent = a.account.syncListensCloud(a.stats)
            assertEquals(1, sent, "only the finished listen goes up; the one still playing waits")
            withTimeout(10_000) { while ("song-done" !in b.ids()) delay(50) }
            withTimeout(5_000) { while (heard.get() == 0) delay(20) } // arrived by notification, not a poll
            assertFalse("song-playing" in b.ids())
            val mirrored = days.values.flatMap { d -> d.getJSONObject("events").let { e -> e.keys().asSequence().map { e.getJSONObject(it) }.toList() } }
                .first { it.optString("t") == "song-done" }
            assertEquals(true, mirrored.has("d"), "mirrored to Firestore for old apps, marked with the device")
            assertEquals(0, a.account.syncListensCloud(a.stats), "sent once")
            assertEquals(readsBefore, reads.get(), "steady use reads nothing from Firestore")

            // A listen recorded by an old app version reaches the service at the next desktop start.
            oldAppListen("old-app-song", now - 300_000, 60_000)
            val restarted = Desktop("a2"); restarted.signIn()
            restarted.account.syncListensCloud(restarted.stats)
            b.account.syncListensCloud(b.stats)
            assertTrue("old-app-song" in b.ids(), "carried over by the start-time bridge")
            restarted.close()

            // Clearing stats on one desktop clears the account's history everywhere.
            a.account.clearListensCloud()
            withTimeout(10_000) { while (b.ids().isNotEmpty()) delay(50) }
            assertEquals(emptySet(), b.ids())
        } finally { a.close(); b.close() }
    }

    @Test fun `an old phone back after weeks offline uploads listens with old timestamps, and they still arrive`() = runBlocking {
        if (base == null) return@runBlocking
        val now = System.currentTimeMillis()
        val a = Desktop("a")
        try {
            a.signIn()
            a.account.syncListensCloud(a.stats)
            // The phone was offline for 45 days; it uploads what it recorded then, with those dates.
            oldAppListen("offline-1", now - 45L * 86_400_000L, 200_000)
            oldAppListen("offline-2", now - 40L * 86_400_000L, 150_000)
            val next = Desktop("a-next"); next.signIn()
            next.account.syncListensCloud(next.stats)
            assertTrue(next.ids().containsAll(setOf("offline-1", "offline-2")), "picked up by upload time, not listen time")
            a.account.syncListensCloud(a.stats)
            assertTrue(a.ids().containsAll(setOf("offline-1", "offline-2")), "and passed on to the other desktop")
            next.close()
        } finally { a.close() }
    }

    @Test fun `after an explicit retirement, an old phone's listens are no longer carried over`() = runBlocking {
        if (base == null) return@runBlocking
        val now = System.currentTimeMillis()
        val a = Desktop("a")
        try {
            a.signIn()
            a.account.syncListensCloud(a.stats)
            val http = okhttp3.OkHttpClient()
            fun admin(action: String, body: JSONObject) = http.newCall(okhttp3.Request.Builder().url("$base/v1/library/admin/$uid/$action")
                .header("x-library-admin", adminSecret).post(okhttp3.RequestBody.create(null, body.toString())).build()).execute().use { JSONObject(it.body!!.string()) }
            val ack = admin("retire-bridge", JSONObject()).getString("acknowledge")
            assertEquals(false, admin("retire-bridge", JSONObject().put("acknowledge", ack)).getJSONObject("bridge").getBoolean("active"))
            oldAppListen("after-retirement", now - 60_000, 30_000)
            val next = Desktop("a-next"); next.signIn()
            next.account.syncListensCloud(next.stats)
            assertFalse("after-retirement" in next.ids(), "the warned-about consequence")
            next.close()
        } finally { a.close() }
    }

    @Test fun `restoring the bridge reconciles listens recorded on both sides while it was retired`() = runBlocking {
        if (base == null) return@runBlocking
        val now = System.currentTimeMillis()
        val a = Desktop("a")
        val http = okhttp3.OkHttpClient()
        fun admin(action: String, body: JSONObject = JSONObject()) = http.newCall(okhttp3.Request.Builder().url("$base/v1/library/admin/$uid/$action")
            .header("x-library-admin", adminSecret).post(okhttp3.RequestBody.create(null, body.toString())).build()).execute().use { JSONObject(it.body!!.string()) }
        fun firestoreHas(trackId: String) = days.values.any { d -> d.getJSONObject("events").let { e -> e.keys().asSequence().any { e.getJSONObject(it).optString("t") == trackId } } }
        try {
            a.signIn()
            a.account.syncListensCloud(a.stats)
            admin("retire-bridge", JSONObject().put("acknowledge", admin("retire-bridge").getString("acknowledge")))
            // While retired: this desktop finishes a listen (service only) and an old phone uploads one (Firestore only).
            a.stats.record("rec-retired", Track("updated-during-retirement", "U", "A"), now - 900_000, 200_000)
            assertEquals(1, a.account.syncListensCloud(a.stats))
            assertFalse(firestoreHas("updated-during-retirement"), "not mirrored while retired")
            oldAppListen("old-during-retirement", now - 800_000, 100_000)
            assertEquals(true, admin("restore-bridge").getJSONObject("bridge").getBoolean("backfillPending"))
            // The next updated-app start reconciles both directions.
            val next = Desktop("a-next"); next.signIn()
            next.account.syncListensCloud(next.stats)
            assertTrue(firestoreHas("updated-during-retirement"), "the gap was copied to Firestore for old devices")
            assertTrue("old-during-retirement" in next.ids(), "the old phone's listen reached the updated apps")
            assertEquals(false, admin("status").getJSONObject("bridge").getBoolean("backfillPending"), "done once")
            next.close()
        } finally { a.close() }
    }
}
