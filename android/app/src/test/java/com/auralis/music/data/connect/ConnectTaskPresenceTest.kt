package com.auralis.music.data.connect

import org.junit.Assert.*
import org.junit.Test

class ConnectTaskPresenceTest {
    @Test fun backgroundingAndScreenOffKeepARegisteredTaskAvailable() {
        val presence = ConnectTaskPresence()
        presence.foreground(true)
        presence.service(true)
        presence.foreground(false)
        assertTrue(presence.taskPresent)
        assertTrue(presence.available)
    }

    @Test fun swipingAwayGoesOfflineEvenBeforeTheServiceFinishesCleanup() {
        val presence = ConnectTaskPresence()
        presence.foreground(true)
        presence.service(true)
        presence.removeTask()
        assertFalse(presence.available)
        presence.foreground(false) // late onStop cannot re-register it
        assertFalse(presence.taskPresent)
        assertFalse(presence.available)
    }

    @Test fun serviceOrMediaProcessAloneCannotReopenAClosedTask() {
        val presence = ConnectTaskPresence()
        presence.service(true)
        assertFalse(presence.available)
        presence.foreground(true)
        assertTrue(presence.available)
        presence.removeTask()
        presence.service(false)
        presence.service(true)
        assertFalse(presence.available)
    }

    @Test fun reopeningRegistersAgainAndServiceLossDoesNotAdvertiseADeadBackgroundClient() {
        val presence = ConnectTaskPresence()
        presence.foreground(true)
        presence.service(true)
        presence.foreground(false)
        presence.service(false)
        assertFalse(presence.available)
        presence.foreground(true)
        assertTrue(presence.available)
        presence.removeTask()
        presence.foreground(true)
        assertTrue(presence.available)
    }
}
