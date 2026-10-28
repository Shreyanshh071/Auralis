package com.auralis.music.ui.viewmodel

import com.auralis.music.data.connect.AuralisConnect
import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.auralis.music.domain.auth.UserProfile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthDisconnectTest {
    @Test fun disconnectClearsAuthBeforeReturningEvenWhenCloudWouldNeverRespond() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkObject(AuralisConnect)
        try {
            val profile = MutableStateFlow(UserProfile(uid = "old-account", isGoogleConnected = true))
            val manager = mockk<GoogleAccountSyncManager>(relaxed = true)
            every { manager.userProfile } returns profile
            every { manager.remotePlaylists } returns MutableStateFlow(emptyList())
            every { manager.isSyncing } returns MutableStateFlow(false)
            every { manager.syncMessage } returns MutableStateFlow(null)
            coEvery { manager.flushToCloud() } coAnswers { awaitCancellation() }
            every { AuralisConnect.disconnectLocally() } returns Unit
            every { manager.disconnectAccount() } answers { profile.value = UserProfile() }
            val vm = AuthViewModel(manager)
            runCurrent()
            assertTrue(vm.uiState.value.profile.isGoogleConnected)

            vm.disconnectAccount()

            // No advanceTimeBy/runCurrent: sign-in must be available in this call.
            assertFalse(vm.uiState.value.profile.isGoogleConnected)
            assertTrue(vm.uiState.value.profile.uid.isBlank())
            verify(exactly = 1) { manager.disconnectAccount() }
            verify(exactly = 1) { AuralisConnect.disconnectLocally() }
            coVerify(exactly = 0) { manager.flushToCloud() }
            coVerify(exactly = 0) { manager.removeAccountItems(any()) }
            coVerify(exactly = 0) { AuralisConnect.leaveAccount() }
        } finally {
            unmockkObject(AuralisConnect)
            Dispatchers.resetMain()
        }
    }
}
