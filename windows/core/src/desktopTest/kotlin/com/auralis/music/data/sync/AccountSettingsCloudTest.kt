package com.auralis.music.data.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings backend and its Firestore bridge, against stand-ins with the real merge rule. */
class AccountSettingsCloudTest {
    private var time = 1_000_000L
    private fun p(value: String, revision: Long, device: String) = AccountPreference(value, revision, device)

    /** The service's settings rule, as connect-server/src/settings-core.js applies it. */
    private class Service {
        val maps = HashMap<String, Map<String, AccountPreference>>()
        var bridge = true
        var legacyMarks = 0
        var calls = 0
        fun call(frame: JSONObject): JSONObject {
            calls++
            val platform = frame.getString("platform")
            val edits = AccountPreferencesMerge.decode(frame.getJSONObject("edits").let { e -> e.keys().asSequence().associateWith { k ->
                e.getJSONObject(k).let { mapOf("value" to it.get("value"), "revision" to it.get("revision"), "device" to it.get("device")) } } })
            val seed = frame.optJSONObject("seed")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } }.orEmpty()
            val merged = AccountPreferencesMerge.merge(maps[platform].orEmpty(), edits, seed)
            maps[platform] = merged
            if (frame.optBoolean("legacy")) legacyMarks++
            return JSONObject().put("t", "settings").put("platform", platform).put("bridge", bridge)
                .put("values", JSONObject(AccountPreferencesMerge.encode(merged)))
        }
    }

    /** Firestore's `preferences.windows` with the transactional merge the apps use; counts billed operations. */
    private class Firestore : AccountPreferencesBackend {
        var map: Map<String, AccountPreference> = emptyMap()
        var reads = 0; var writes = 0; var failing = false
        override suspend fun exchange(uid: String, edits: Map<String, AccountPreference>, seed: Map<String, String>): Map<String, AccountPreference> {
            if (failing) throw IOException("Quota exceeded")
            reads++
            val merged = AccountPreferencesMerge.merge(map, edits, seed)
            if (merged != map) { writes++; map = merged }
            return merged
        }
    }

    private fun backend(service: Service, firestore: Firestore) =
        BridgedPreferencesBackend(CloudPreferencesBackend("windows") { _, frame -> service.call(frame) }, firestore, now = { time })

    /** A real synced setting: values the app accepts are any short text. */
    private val theme = "a.lyrics_animation"

    @Test fun `a start reads Firestore once and pulls after notifications never do`() = runBlocking {
        val service = Service(); val firestore = Firestore(); val b = backend(service, firestore)
        b.exchange("u", emptyMap(), mapOf(theme to "dark"))
        assertEquals(1, firestore.reads, "the start-time bridge")
        repeat(20) { b.exchange("u", emptyMap(), emptyMap()) }
        assertEquals(1, firestore.reads, "20 notification pulls cost no Firestore reads")
        assertEquals(21, service.calls)
    }

    @Test fun `an edit is mirrored with read-merge-write, and old apps then see it`() = runBlocking {
        val service = Service(); val firestore = Firestore(); val b = backend(service, firestore)
        b.exchange("u", emptyMap(), mapOf(theme to "dark"))
        val reads = firestore.reads
        b.exchange("u", mapOf(theme to p("light", 1, "pc")), emptyMap())
        assertEquals(reads + 1, firestore.reads, "1 read per mirrored edit batch")
        assertEquals("light", firestore.map.getValue(theme).value, "an old app polling Firestore sees the edit")
        assertEquals("light", service.maps.getValue("windows").getValue(theme).value)
    }

    @Test fun `the mirror never overwrites a newer edit made by an old app, and carries it to the service`() = runBlocking {
        val service = Service(); val firestore = Firestore(); val b = backend(service, firestore)
        b.exchange("u", emptyMap(), mapOf(theme to "dark"))
        // An old app changes the theme in Firestore at revision 5.
        firestore.map = firestore.map + (theme to p("sepia", 5, "old-phone"))
        // This device edits the same key at revision 2.
        val result = b.exchange("u", mapOf(theme to p("light", 2, "pc")), emptyMap())
        assertEquals("sepia", firestore.map.getValue(theme).value, "the older edit did not overwrite Firestore")
        assertEquals("sepia", result.getValue(theme).value, "the old app's newer value won on the service too")
        assertEquals(1, service.legacyMarks, "the account is marked as still having an old app")
    }

    @Test fun `old-app changes reach the service at the next start, not before`() = runBlocking {
        val service = Service(); val firestore = Firestore()
        backend(service, firestore).exchange("u", emptyMap(), mapOf(theme to "dark"))
        firestore.map = firestore.map + ("a.lyrics_animation" to p("Fade", 3, "old-pc"))
        val running = backend(service, firestore)
        running.exchange("u", emptyMap(), emptyMap())
        assertEquals("Fade", service.maps.getValue("windows").getValue("a.lyrics_animation").value, "picked up at this app's start")
        firestore.map = firestore.map + ("a.lyrics_animation" to p("Spicy", 4, "old-pc"))
        running.exchange("u", emptyMap(), emptyMap())
        assertEquals("Fade", service.maps.getValue("windows").getValue("a.lyrics_animation").value, "a pull does not read Firestore: old-app changes wait")
    }

    @Test fun `a Firestore failure never fails the exchange, and is retried later`() = runBlocking {
        val service = Service(); val firestore = Firestore(); val b = backend(service, firestore)
        firestore.failing = true
        val result = b.exchange("u", mapOf(theme to p("light", 1, "pc")), mapOf(theme to "dark"))
        assertEquals("light", result.getValue(theme).value, "the service holds the edit")
        firestore.failing = false
        b.exchange("u", emptyMap(), emptyMap())
        assertEquals(0, firestore.reads, "not retried on every pull")
        time += 15 * 60_000L
        b.exchange("u", emptyMap(), emptyMap())
        assertEquals("light", firestore.map.getValue(theme).value, "retried after 15 minutes with the whole map")
    }

    @Test fun `an old phone returning after 45 quiet days still syncs, because the bridge never retires by itself`() = runBlocking {
        val service = Service(); val firestore = Firestore()
        backend(service, firestore).exchange("u", emptyMap(), mapOf(theme to "dark"))
        // 45 days pass with no old-app activity. The service still says bridge = true (nothing is timed).
        time += 45L * 86_400_000L
        firestore.map = firestore.map + (theme to p("Fade", 7, "old-phone"))
        val nextStart = backend(service, firestore)
        val merged = nextStart.exchange("u", emptyMap(), emptyMap())
        assertEquals("Fade", merged.getValue(theme).value, "the old phone's change reached the updated apps")
        assertEquals(1, service.legacyMarks, "and the account is marked as still having an old device")
        // And an updated device's edit still reaches the old phone through Firestore.
        nextStart.exchange("u", mapOf(theme to p("Spicy Lyrics", 8, "pc")), emptyMap())
        assertEquals("Spicy Lyrics", firestore.map.getValue(theme).value)
    }

    @Test fun `after an explicit retirement, an old phone's change no longer reaches updated apps`() = runBlocking {
        val service = Service(); val firestore = Firestore()
        backend(service, firestore).exchange("u", emptyMap(), mapOf(theme to "dark"))
        service.bridge = false // retired by an operator for this account
        firestore.map = firestore.map + (theme to p("Fade", 7, "old-phone"))
        val merged = backend(service, firestore).exchange("u", emptyMap(), emptyMap())
        assertEquals("dark", merged.getValue(theme).value, "the consequence the retirement warning describes")
    }

    @Test fun `restoring the bridge reconciles changes made on both sides while it was retired`() = runBlocking {
        val service = Service(); val firestore = Firestore()
        backend(service, firestore).exchange("u", emptyMap(), mapOf(theme to "dark", "a.mini_player_bg_style" to "Blur"))
        service.bridge = false
        // While retired: an updated device changes one setting (service only), an old phone another (Firestore only).
        backend(service, firestore).exchange("u", mapOf(theme to p("Fade", 3, "pc")), emptyMap())
        firestore.map = firestore.map + ("a.mini_player_bg_style" to p("Live Mesh", 4, "old-phone"))
        assertEquals("dark", firestore.map.getValue(theme).value, "the old phone did not get the updated device's change")
        service.bridge = true // restored by the operator
        val merged = backend(service, firestore).exchange("u", emptyMap(), emptyMap()) // the next updated-app start
        assertEquals("Fade", firestore.map.getValue(theme).value, "the updated device's change reached the old phone")
        assertEquals("Live Mesh", merged.getValue("a.mini_player_bg_style").value, "the old phone's change reached the updated apps")
        assertEquals("Live Mesh", service.maps.getValue("windows").getValue("a.mini_player_bg_style").value)
    }

    @Test fun `once the service retires the bridge, Firestore is never touched`() = runBlocking {
        val service = Service().apply { bridge = false }; val firestore = Firestore(); val b = backend(service, firestore)
        b.exchange("u", emptyMap(), mapOf(theme to "dark"))
        b.exchange("u", mapOf(theme to p("light", 1, "pc")), emptyMap())
        assertEquals(0, firestore.reads + firestore.writes)
        assertFalse(b.lastUsedFirestore)
    }

    @Test fun `the engine pulls when told to, instead of polling`() = runBlocking {
        val service = Service(); val firestore = Firestore()
        val local = object : AccountPreferencesLocal {
            var values = mapOf(theme to "dark")
            override suspend fun read() = values
            override suspend fun apply(values: Map<String, String>) { this.values = this.values + values }
        }
        val journal = object : AccountPreferencesJournal { var text: String? = null
            override suspend fun read() = text; override suspend fun write(value: String) { text = value } }
        val engine = AccountPreferencesSync(local, backend(service, firestore), journal, "pc", now = { time }, pollMs = 6 * 3_600_000L)
        engine.step("u")
        val calls = service.calls
        repeat(3600) { time += 1000; engine.step("u") }
        assertEquals(calls, service.calls, "an hour idle: no requests at all")
        service.maps["windows"] = service.maps.getValue("windows") + (theme to p("light", 9, "phone"))
        engine.refresh()
        time += 1000; engine.step("u")
        assertEquals("light", local.values[theme], "a notification makes the next step pull")
        assertTrue(service.calls == calls + 1)
    }
}
