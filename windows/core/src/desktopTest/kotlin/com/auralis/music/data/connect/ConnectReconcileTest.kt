package com.auralis.music.data.connect

import com.auralis.music.data.connect.ReconcileResult.KEEP_LOCAL
import com.auralis.music.data.connect.ReconcileResult.KEEP_SESSION
import com.auralis.music.data.connect.ReconcileResult.WAIT_FOR_CLOCK
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectReconcileTest {
    private val margin = 3_000L
    private val session = ConnectSession(version = 12, epoch = 5, ownerId = "pc")
    private fun decide(intent: ConnectPlayIntent?, session: ConnectSession = this.session, baseEpoch: Long? = 5L, baseVersion: Long? = 10L, tap: Long? = 100_000L) =
        reconcileLocalControl("phone", baseEpoch, baseVersion, tap, margin, session, intent)

    @Test fun anIntentAlreadySeenWhenActingIsOlderWhateverItsTimestampSays() {
        // A wrong clock on the other device must not let a known action outrank a newer one.
        assertEquals(KEEP_LOCAL, decide(ConnectPlayIntent(10, 5, 999_999_999L, "pc")))
        assertEquals(KEEP_LOCAL, decide(ConnectPlayIntent(9, 5, 999_999_999L, "pc")))
    }

    @Test fun routineReportsRecordNothingSoTheyNeverOutrankAnAction() {
        val checkpointed = session.copy(version = 900, updatedAtMs = 500_000L)
        assertEquals(KEEP_LOCAL, decide(ConnectPlayIntent(10, 5, 1_000L, "pc"), checkpointed))
    }

    @Test fun anUnseenIntentIsOrderedByTimeWithAMargin() {
        assertEquals(KEEP_LOCAL, decide(ConnectPlayIntent(11, 5, 90_000L, "pc")))
        assertEquals(KEEP_SESSION, decide(ConnectPlayIntent(11, 5, 120_000L, "pc")))
    }

    @Test fun actionsTooCloseToOrderLeaveWhatTheSessionCommitted() {
        assertEquals(KEEP_SESSION, decide(ConnectPlayIntent(11, 5, 100_000L, "pc")))
        assertEquals(KEEP_SESSION, decide(ConnectPlayIntent(11, 5, 97_000L, "pc")))
        assertEquals(KEEP_SESSION, decide(ConnectPlayIntent(11, 5, 102_999L, "pc")))
    }

    @Test fun anUnseenIntentWaitsForATrustedClockInsteadOfGuessing() {
        assertEquals(WAIT_FOR_CLOCK, decide(ConnectPlayIntent(11, 5, 90_000L, "pc"), tap = null))
    }

    @Test fun anActionBeforeAnyReadTreatsEveryIntentAsUnseen() {
        assertEquals(KEEP_LOCAL, decide(ConnectPlayIntent(3, 5, 1_000L, "pc"), baseEpoch = null, baseVersion = null))
        assertEquals(KEEP_SESSION, decide(ConnectPlayIntent(3, 5, 200_000L, "pc"), baseEpoch = null, baseVersion = null))
    }

    @Test fun ownIntentNeverCountsAgainstThisDevice() {
        assertEquals(KEEP_LOCAL, decide(ConnectPlayIntent(11, 5, 500_000L, "phone")))
    }

    @Test fun anOutputMovedWithNothingRecordedStaysWhereItIs() {
        val moved = session.copy(epoch = 6)
        assertEquals(KEEP_SESSION, decide(null, moved))
        assertEquals(KEEP_SESSION, decide(ConnectPlayIntent(11, 5, 1_000L, "laptop"), moved), "The intent predates the move")
        assertEquals(KEEP_LOCAL, decide(null, session), "Nothing moved, nothing newer known")
        assertEquals(KEEP_LOCAL, decide(null, moved, baseEpoch = null))
        assertEquals(KEEP_LOCAL, decide(ConnectPlayIntent(11, 6, 1_000L, "laptop"), moved), "A recorded move is ordered like any action")
    }

    @Test fun theIntentAndSavedControlSurviveEncoding() {
        val intent = ConnectPlayIntent(4, 2, 1234L, "pc")
        assertEquals(intent, ConnectCodec.decodeIntent(ConnectCodec.encodeIntent(intent)))
        for (saved in listOf(ConnectSavedControl(1, 2, 3, 4), ConnectSavedControl(null, null, 3, 4)))
            assertEquals(saved, ConnectSavedControl.decode(saved.encode()))
        assertNull(ConnectSavedControl.decode(null))
        assertNull(ConnectSavedControl.decode(""))
        assertNull(ConnectSavedControl.decode("1,2,not-a-number,4"))
    }

    @Test fun theSessionFormatIsExactlyWhatReleasedClientsWrite() {
        val keys = setOf("version", "ownerId", "ownerName", "ownerType", "epoch", "leaseUntil", "track", "playing", "buffering", "positionMs",
            "positionAt", "durationMs", "queueRev", "queueIndex", "shuffle", "repeat", "context", "transfer", "commands", "updatedBy", "updatedAt")
        assertEquals(keys, ConnectCodec.encodeSession(session).keys)
    }

    @Test fun oneActionBringsTheAudioHereOnlyWhenAnotherDeviceHasIt() {
        val me = ConnectDevice("phone", "Phone")
        fun row(state: ConnectRowState = ConnectRowState.IDLE, output: Boolean = false) =
            ConnectDeviceRow(me, "Phone", isThis = true, isOutput = output, state = state)
        val laptop = ConnectOutput("pc", "Laptop", ConnectDeviceType.COMPUTER, isThis = false, active = true, playing = true)
        assertTrue(ConnectUi(enabled = true, output = laptop, devices = listOf(row())).canPlayHere)
        assertTrue(ConnectUi(enabled = true, output = laptop.copy(playing = false), devices = listOf(row())).canPlayHere)
        assertFalse(ConnectUi(enabled = true, output = laptop.copy(isThis = true), devices = listOf(row(output = true))).canPlayHere)
        assertFalse(ConnectUi(enabled = true, output = laptop.copy(active = false), devices = listOf(row())).canPlayHere)
        assertFalse(ConnectUi(enabled = true, output = laptop, devices = listOf(row(ConnectRowState.CONNECTING))).canPlayHere)
        assertFalse(ConnectUi(enabled = true, output = laptop, devices = listOf(row(ConnectRowState.UNAVAILABLE))).canPlayHere)
        assertFalse(ConnectUi(enabled = true, output = laptop, devices = listOf(row()),
            transfer = ConnectTransferUi("phone", "Phone", failed = false)).canPlayHere)
    }

    @Test fun windowsPlaybackCodeNeverImportsFirebase() {
        val roots = listOf("src/desktopMain/kotlin/com/auralis/music/desktop/playback", "src/desktopMain/kotlin/com/auralis/music/desktop/library")
            .map(::File).onEach { assertTrue(it.isDirectory, "$it moved: point this check at the playback code") }
        val offenders = roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { f -> Regex("com\\.google\\.firebase|com\\.google\\.firestore|FirestoreRest|DesktopAccount").containsMatchIn(f.readText()) }
        assertTrue(offenders.isEmpty(), "Playback must not depend on Firebase: $offenders")
    }
}
