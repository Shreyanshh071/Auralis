package com.auralis.music

import android.content.Context
import com.auralis.music.data.sync.AndroidAccountPreferences
import com.google.firebase.auth.FirebaseAuth
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountPreferencesStartupTest {
    @Test fun `missing Firebase configuration does not crash startup sync`() = runTest {
        mockkStatic(FirebaseAuth::class)
        try {
            every { FirebaseAuth.getInstance() } throws IllegalStateException("Default FirebaseApp is not initialized")
            val sync = AndroidAccountPreferences(mockk<Context>()).start(backgroundScope)
            sync.join()
            assertFalse(sync.isCancelled)
        } finally {
            unmockkStatic(FirebaseAuth::class)
        }
    }
}
