package com.auralis.music.ui.connect

import com.auralis.music.R
import com.auralis.music.data.connect.*
import org.junit.Assert.*
import org.junit.Test

class ConnectPlayerVisibilityTest {
    private val laptop = ConnectOutput("laptop", "Laptop", ConnectDeviceType.COMPUTER, false, true, true)

    @Test fun pickerShowsConnectionStatusWithoutTreatingOptInAsConnection() {
        val connecting = ConnectUi(enabled = true)
        val connected = connecting.copy(connected = true)
        assertEquals(R.string.connect_disconnected, connectConnectionStatus(true, false, connected))
        assertEquals(R.string.connect_disconnected, connectConnectionStatus(false, true, connected))
        assertEquals(R.string.connect_disconnected, connectConnectionStatus(true, true, ConnectUi()))
        assertEquals(R.string.connect_connecting, connectConnectionStatus(true, true, connecting))
        assertEquals(R.string.connected, connectConnectionStatus(true, true, connected))
        assertEquals(R.string.connect_reconnecting, connectConnectionStatus(true, true, connecting.copy(error = "Offline")))
    }

    @Test fun idleLocalAndHistoricalSessionsHaveNoPill() {
        assertFalse(shouldShowPlayerDevicePill(ConnectUi(enabled = true)))
        assertFalse(shouldShowPlayerDevicePill(ConnectUi(enabled = true, output = laptop.copy(isThis = true))))
        assertFalse(shouldShowPlayerDevicePill(ConnectUi(enabled = true, output = laptop.copy(active = false, playing = false))))
        assertFalse(shouldShowPlayerDevicePill(ConnectUi(enabled = false, output = laptop)))
    }

    @Test fun liveRemoteOutputShowsEvenWhilePaused() {
        assertTrue(shouldShowPlayerDevicePill(ConnectUi(enabled = true, output = laptop)))
        assertTrue(shouldShowPlayerDevicePill(ConnectUi(enabled = true, output = laptop.copy(playing = false))))
    }

    @Test fun connectionAttemptsShowButFailedAttemptsDoNot() {
        val attempt = ConnectTransferUi("laptop", "Laptop", false)
        assertTrue(shouldShowPlayerDevicePill(ConnectUi(enabled = true, transfer = attempt)))
        assertFalse(shouldShowPlayerDevicePill(ConnectUi(enabled = true, transfer = attempt.copy(failed = true))))
    }
}
