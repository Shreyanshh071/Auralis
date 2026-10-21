package com.auralis.music

import android.Manifest
import com.auralis.music.ui.onboarding.TourPermission
import org.junit.Assert.*
import org.junit.Test

class TourPermissionsTest {
    @Test fun `notifications only use a runtime prompt on Android 13 or newer`() {
        assertNull(TourPermission.NOTIFICATIONS.runtimePermission(32))
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, TourPermission.NOTIFICATIONS.runtimePermission(33))
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, TourPermission.NOTIFICATIONS.runtimePermission(35))
    }

    @Test fun `scoped storage devices never request legacy storage access`() {
        for (api in listOf(29, 32, 33, 35)) {
            assertFalse(TourPermission.visibleOn(api).contains(TourPermission.STORAGE))
            assertNull(TourPermission.STORAGE.runtimePermission(api))
        }
        for (api in listOf(24, 28)) {
            assertTrue(TourPermission.visibleOn(api).contains(TourPermission.STORAGE))
            assertEquals(Manifest.permission.WRITE_EXTERNAL_STORAGE, TourPermission.STORAGE.runtimePermission(api))
        }
    }

    @Test fun `microphone is optional and there are no unrelated access requests`() {
        for (api in listOf(24, 28, 33, 35)) {
            assertTrue(TourPermission.visibleOn(api).contains(TourPermission.MICROPHONE))
            assertEquals(Manifest.permission.RECORD_AUDIO, TourPermission.MICROPHONE.runtimePermission(api))
        }
        assertEquals(listOf(TourPermission.NOTIFICATIONS, TourPermission.MICROPHONE), TourPermission.visibleOn(35))
    }
}
