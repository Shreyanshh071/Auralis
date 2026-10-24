package com.auralis.music.data.network

import org.junit.Assert.*
import org.junit.Test

class SpicyLyricsAccessTest {
    @Test fun blankSettingsUseTheApplicationKey() {
        assertEquals("sl_pk_application", SpicyLyricsAccess.resolve("", "sl_pk_application"))
        assertEquals("sl_pk_application", SpicyLyricsAccess.resolve("  ", "sl_pk_application"))
    }
    @Test fun userOverrideWinsWithoutChangingTheBundledKey() {
        assertEquals("sl_pk_personal", SpicyLyricsAccess.resolve(" sl_pk_personal ", "sl_pk_application"))
    }
    @Test fun secretsAndMalformedKeysAreNeverUsed() {
        assertEquals("", SpicyLyricsAccess.resolve("sl_sk_never", "sl_pk_application"))
        assertEquals("", SpicyLyricsAccess.resolve("", "sl_sk_never"))
        assertEquals("", SpicyLyricsAccess.resolve("invalid", "sl_pk_application"))
        assertEquals("", SpicyLyricsAccess.resolve("", ""))
    }
}
