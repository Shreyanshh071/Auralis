package com.auralis.music

import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.auralis.music.domain.auth.UserProfile
import com.auralis.music.ui.viewmodel.ListenTogetherUiState
import org.junit.Assert.*
import org.junit.Test

class ListenTogetherIdentityTest {

    @Test
    fun `ListenTogetherUiState default myDisplayName does not contain Listener`() {
        val state = ListenTogetherUiState()
        assertNotEquals("Listener", state.myDisplayName)
        assertFalse("myDisplayName must not contain listener placeholder", state.myDisplayName.contains("Listener", ignoreCase = true))
    }

    @Test
    fun `UserProfile default displayName does not contain Guest Listener`() {
        val profile = UserProfile()
        assertNotEquals("Guest Listener", profile.displayName)
        assertNotEquals("Listener", profile.displayName)
        assertEquals("", profile.displayName)
    }

    @Test
    fun `GoogleAccountSyncManager isGenericListener correctly identifies placeholder listener names`() {
        assertTrue(GoogleAccountSyncManager.isGenericListener("Listener"))
        assertTrue(GoogleAccountSyncManager.isGenericListener("listener"))
        assertTrue(GoogleAccountSyncManager.isGenericListener("Guest Listener"))
        assertTrue(GoogleAccountSyncManager.isGenericListener("Auralis Listener"))

        // Real user identities must not be flagged
        assertFalse(GoogleAccountSyncManager.isGenericListener("Ishaan Thakur"))
        assertFalse(GoogleAccountSyncManager.isGenericListener("JohnDoe"))
        assertFalse(GoogleAccountSyncManager.isGenericListener("alex@gmail.com"))
    }
}
