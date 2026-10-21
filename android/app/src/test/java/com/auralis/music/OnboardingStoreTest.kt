package com.auralis.music

import android.content.Context
import android.content.SharedPreferences
import com.auralis.music.data.datastore.OnboardingStore
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.NewUserSeedProvider
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingStoreTest {
    private val values = mutableMapOf<String, Any>()
    private lateinit var context: Context

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        context = mockk()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.contains(any()) } answers { values.containsKey(firstArg()) }
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        every { prefs.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.putString(any(), any()) } answers { secondArg<String?>()?.let { values[firstArg()] = it }; editor }
        OnboardingStore.init(context, false)
        OnboardingStore.bindAccount("")
    }

    @After fun cleanup() {
        NewUserSeedProvider.setStarter(emptyList(), emptyList())
        Dispatchers.resetMain()
    }

    @Test fun firstLaunchTourPersistsAcrossRelaunchWithoutCompletingArtistSelection() {
        assertFalse(OnboardingStore.state.value.tourSeen)
        OnboardingStore.markTourSeen()
        OnboardingStore.init(context, false)
        assertTrue(OnboardingStore.state.value.tourSeen)
        assertFalse(OnboardingStore.state.value.pickerDone)
    }

    @Test fun newAccountDoesNotInheritAnotherAccountsChoicesOrRepeatTour() {
        OnboardingStore.markTourSeen()
        OnboardingStore.bindAccount("first")
        OnboardingStore.savePicks(listOf(Artist("a", "A")), listOf(Track("s", "Song", "A")))
        OnboardingStore.bindAccount("second")
        assertTrue(OnboardingStore.state.value.tourSeen)
        assertFalse(OnboardingStore.state.value.pickerDone)
        assertTrue(NewUserSeedProvider.SEED_ARTISTS.isEmpty())
        OnboardingStore.bindAccount("first")
        assertTrue(OnboardingStore.state.value.pickerDone)
        assertEquals(listOf("A"), NewUserSeedProvider.SEED_ARTISTS)
    }

    @Test fun restoredChoicesCompletePickerEvenWhenSongCacheIsEmpty() {
        OnboardingStore.bindAccount("returning")
        OnboardingStore.restorePicks("returning", mapOf("artists" to """[{"id":"a","name":"A"}]""", "tracks" to "[]"))
        assertTrue(OnboardingStore.state.value.pickerDone)
        assertEquals(listOf("A"), NewUserSeedProvider.SEED_ARTISTS)
    }

    @Test fun lateRestoreCannotSwitchTheActiveAccount() {
        OnboardingStore.bindAccount("current")
        OnboardingStore.restorePicks("old", mapOf("artists" to """[{"id":"a","name":"A"}]""", "tracks" to "[]"))
        assertEquals("current", OnboardingStore.state.value.accountId)
        assertFalse(OnboardingStore.state.value.pickerDone)
        assertTrue(NewUserSeedProvider.SEED_ARTISTS.isEmpty())
    }

    @Test fun legacySignedInUpgradeSkipsTourButDoesNotInventArtistChoices() {
        OnboardingStore.init(context, true)
        assertTrue(OnboardingStore.state.value.tourSeen)
        assertFalse(OnboardingStore.state.value.pickerDone)
    }

    @Test fun newAccountOnUsedPhoneRequiresItsOwnTourAndArtistPicker() {
        OnboardingStore.markTourSeen()
        OnboardingStore.onAuthenticated("existing", newlyCreated = false)
        OnboardingStore.bindAccount("")
        OnboardingStore.onAuthenticated("brand-new", newlyCreated = true)
        assertFalse(OnboardingStore.state.value.tourSeen)
        assertFalse(OnboardingStore.state.value.pickerDone)
        assertTrue(OnboardingStore.state.value.isNewAccount)
        OnboardingStore.markTourSeen()
        assertTrue(OnboardingStore.state.value.tourSeen)
        OnboardingStore.bindAccount("brand-new")
        assertTrue(OnboardingStore.state.value.tourSeen)
    }

    @Test fun firstInstallTourImmediatelyBeforeSignupDoesNotPlayTwice() {
        OnboardingStore.markTourSeen()
        OnboardingStore.onAuthenticated("new", newlyCreated = true)
        OnboardingStore.requireNewcomerTour("new")
        assertTrue(OnboardingStore.state.value.tourSeen)
        assertFalse(OnboardingStore.state.value.pickerDone)
    }

    @Test fun pendingAccountTourSurvivesProcessRestart() {
        OnboardingStore.onAuthenticated("new", newlyCreated = true)
        OnboardingStore.init(context, true)
        OnboardingStore.bindAccount("new")
        assertFalse(OnboardingStore.state.value.tourSeen)
        assertTrue(OnboardingStore.state.value.isNewAccount)
    }

    @Test fun loginWithNoListeningAlsoGetsTourOnceAfterRestoreDecision() {
        OnboardingStore.markTourSeen()
        OnboardingStore.onAuthenticated("first", newlyCreated = false)
        OnboardingStore.onAuthenticated("empty-login", newlyCreated = false)
        OnboardingStore.requireNewcomerTour("empty-login")
        assertFalse(OnboardingStore.state.value.tourSeen)
        OnboardingStore.markTourSeen()
        OnboardingStore.requireNewcomerTour("empty-login")
        assertTrue(OnboardingStore.state.value.tourSeen)
    }
}
