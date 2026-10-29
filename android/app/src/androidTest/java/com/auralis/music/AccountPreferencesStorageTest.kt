package com.auralis.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.datastore.*
import com.auralis.music.data.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountPreferencesStorageTest {
    @Test fun reportsWhetherTheInstalledPhoneProfileIsReadyForAccountSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enabled = SettingsDataStore(context).settingsFlow.first().cloudSyncEnabled
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        android.util.Log.i("AccountPreferencesProbe", "Cloud sync enabled: $enabled; signed-in account: ${user != null && !user.isAnonymous}")
        Unit
    }

    @Test fun savedPhonePreferencesCanRestoreToAFreshPhoneWithoutModifyingTheOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appearance = AppearanceSettingsDataStore(context)
        val player = SettingsDataStore(context)
        val local = DataStoreAccountPreferences(context.appearanceSettingsDataStore, context.settingsDataStore) {
            AccountPreferenceSchema.values(appearance.settingsFlow.first(), player.settingsFlow.first())
        }
        val before = local.read()
        assertTrue(before.size >= 40)
        assertTrue(before.keys.none { it.contains("token") || it.contains("volume") || it.contains("path") })
        var cloud = emptyMap<String, AccountPreference>()
        val backend = AccountPreferencesBackend { _, edits, seed ->
            AccountPreferencesMerge.merge(cloud, edits, seed).also { cloud = it }
        }
        fun journal() = object : AccountPreferencesJournal {
            var text: String? = null
            override suspend fun read() = text
            override suspend fun write(value: String) { text = value }
        }
        // Read the real profile; emulate an enabled account without altering the user's switch.
        val source = object : AccountPreferencesLocal {
            override suspend fun read() = local.read()
            override suspend fun apply(values: Map<String, String>) { assertTrue(values.isEmpty()) }
        }
        AccountPreferencesSync(source, backend, journal(), "source-phone").step("fixture-user")
        var restored = AccountPreferenceSchema.defaultValues
        val fresh = object : AccountPreferencesLocal {
            override suspend fun read() = restored
            override suspend fun apply(values: Map<String, String>) { restored += values }
        }
        AccountPreferencesSync(fresh, backend, journal(), "new-phone").step("fixture-user")
        assertEquals(before, restored)
        assertEquals(before, local.read())
        assertEquals(before["a.lyrics_animation"], restored["a.lyrics_animation"])
        assertEquals(before["a.mini_player_bg_style"], restored["a.mini_player_bg_style"])
    }
}
