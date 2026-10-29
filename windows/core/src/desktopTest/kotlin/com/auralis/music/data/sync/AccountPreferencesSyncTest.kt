package com.auralis.music.data.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AccountPreferencesSyncTest {
    private class Local : AccountPreferencesLocal {
        var values = AccountPreferenceSchema.defaultValues
        var on = true
        override suspend fun read() = values
        override suspend fun apply(values: Map<String, String>) { this.values += values }
        override suspend fun enabled() = on
        fun change(key: String, value: String) { values += key to value }
    }
    private class Journal : AccountPreferencesJournal {
        var text: String? = null
        override suspend fun read() = text
        override suspend fun write(value: String) { text = value }
    }
    private class Cloud : AccountPreferencesBackend {
        val accounts = mutableMapOf<String, Map<String, AccountPreference>>()
        var offline = false
        var calls = 0
        var during: (() -> Unit)? = null
        override suspend fun exchange(uid: String, edits: Map<String, AccountPreference>, seed: Map<String, String>): Map<String, AccountPreference> {
            calls++
            if (offline) throw java.io.IOException("offline")
            val merged = AccountPreferencesMerge.merge(accounts[uid].orEmpty(), edits, seed)
            accounts[uid] = merged
            during?.invoke(); during = null
            return merged
        }
    }
    private var time = 0L
    private fun engine(local: Local, cloud: Cloud, journal: Journal = Journal(), device: String = "phone") =
        AccountPreferencesSync(local, cloud, journal, device, now = { time }, pollMs = 1000)

    @Test fun freshSignInRestoresSavedLyricsAndMiniPlayerInsteadOfUploadingDefaults() = runBlocking {
        val cloud = Cloud()
        val phone = Local().apply { change("a.lyrics_animation", "Spicy Lyrics"); change("a.mini_player_bg_style", "Live Mesh") }
        engine(phone, cloud).step("user")
        val pc = Local().apply { change("a.lyrics_animation", "Fade"); change("a.mini_player_bg_style", "Blur") }
        engine(pc, cloud, device = "pc").step("user")
        assertEquals("Spicy Lyrics", pc.values["a.lyrics_animation"])
        assertEquals("Live Mesh", pc.values["a.mini_player_bg_style"])
        assertEquals("Spicy Lyrics", cloud.accounts["user"]?.get("a.lyrics_animation")?.value)
    }

    @Test fun independentOfflineEditsMergeAcrossRestartAndRetryIsPaced() = runBlocking {
        val cloud = Cloud(); val a = Local(); val b = Local(); val journal = Journal()
        var first = engine(a, cloud, journal, "phone")
        val second = engine(b, cloud, device = "pc")
        first.step("user"); second.step("user")
        cloud.offline = true
        a.change("a.mini_player_bg_style", "Live Mesh"); b.change("a.lyrics_animation", "Spicy Lyrics")
        first.step("user"); second.step("user"); time += 1000
        first.step("user"); second.step("user")
        val calls = cloud.calls
        repeat(5) { time += 1000; first.step("user") }
        assertEquals(calls, cloud.calls, "Offline failures must not hammer the server")
        cloud.offline = false; time += 16000
        second.step("user")
        first = engine(a, cloud, journal, "phone")
        first.step("user"); time += 1000; second.step("user")
        assertEquals("Live Mesh", b.values["a.mini_player_bg_style"])
        assertEquals("Spicy Lyrics", a.values["a.lyrics_animation"])
    }

    @Test fun changesMadeWhileRequestIsInFlightAreNotOverwritten() = runBlocking {
        val local = Local(); val cloud = Cloud(); val sync = engine(local, cloud)
        sync.step("user")
        cloud.during = { local.change("a.lyrics_animation", "Fade") }
        time += 1000; sync.step("user")
        assertEquals("Fade", local.values["a.lyrics_animation"])
        time += 1000; sync.step("user")
        assertEquals("Fade", cloud.accounts["user"]?.get("a.lyrics_animation")?.value)
    }

    @Test fun accountSwitchDoesNotUploadThePreviousAccountsChoices() = runBlocking {
        val local = Local(); val cloud = Cloud(); val journal = Journal(); val sync = engine(local, cloud, journal)
        local.change("a.mini_player_bg_style", "Live Mesh"); sync.step("alice")
        sync.step(null)
        local.change("a.lyrics_animation", "Fade")
        sync.step("bob")
        assertEquals(AccountPreferenceSchema.defaultValues, local.values)
        assertEquals("Live Mesh", cloud.accounts["alice"]?.get("a.mini_player_bg_style")?.value)
        assertEquals(AccountPreferenceSchema.defaultValues["a.mini_player_bg_style"], cloud.accounts["bob"]?.get("a.mini_player_bg_style")?.value)
        sync.step("alice")
        assertEquals("Live Mesh", local.values["a.mini_player_bg_style"])
    }

    @Test fun editsOnTheSameDeviceWhileSignedOutAreSavedOnNextSignIn() = runBlocking {
        val local = Local(); val cloud = Cloud(); val journal = Journal(); val sync = engine(local, cloud, journal)
        sync.step("user"); sync.step(null)
        local.change("a.color_palette", "Lavender")
        engine(local, cloud, journal).step("user")
        assertEquals("Lavender", cloud.accounts["user"]?.get("a.color_palette")?.value)
    }

    @Test fun disablingCloudSyncPreventsBothUploadAndRestore() = runBlocking {
        val local = Local().apply { on = false }; val cloud = Cloud()
        val sync = engine(local, cloud)
        sync.step("user")
        assertEquals(0, cloud.calls)
        assertEquals(AccountPreferencesSync.Status.OFF, sync.status.value)
    }

    @Test fun sameFieldConflictsResolveDeterministicallyWithoutWallClocks() {
        val key = "a.lyrics_animation"
        val a = AccountPreference("Fade", 2, "phone")
        val b = AccountPreference("Spicy Lyrics", 2, "pc")
        val ab = AccountPreferencesMerge.merge(mapOf(key to a), mapOf(key to b), emptyMap())
        val ba = AccountPreferencesMerge.merge(mapOf(key to b), mapOf(key to a), emptyMap())
        assertEquals(ab, ba)
        assertEquals(a, ab[key])
    }

    @Test fun invalidAndDeviceSpecificSettingsNeverEnterTheSyncPayload() {
        for (key in listOf("p.volume", "a.display_density", "a.high_refresh_rate", "spotify_token", "download_path"))
            assertFalse(AccountPreferenceSchema.valid(key, "anything"))
        assertFalse(AccountPreferenceSchema.valid("a.lyrics_text_size", "NaN"))
        assertFalse(AccountPreferenceSchema.valid("a.liquid_glass", "yes"))
        assertFalse(AccountPreferenceSchema.valid("p.playback_rate", "100"))
        assertTrue(AccountPreferencesMerge.decode(mapOf("a.lyrics_animation" to mapOf("value" to "Fade", "revision" to -1, "device" to "x"))).isEmpty())
    }
}
