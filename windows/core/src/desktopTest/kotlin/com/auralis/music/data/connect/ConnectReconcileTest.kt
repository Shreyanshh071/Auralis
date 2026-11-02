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
    private fun session(intent: ConnectPlayIntent?, epoch: Long = 5L) = ConnectSession(epoch = epoch, ownerId = "pc", intent = intent)
    private fun decide(session: ConnectSession, baseEpoch: Long? = 5L, baseSeq: Long? = 10L, tap: Long? = 100_000L) =
        reconcileLocalControl("phone", baseEpoch, baseSeq, tap, margin, session)

    @Test fun anIntentAlreadySeenWhenActingIsOlderWhateverItsTimestampSays() {
        // A wrong clock on the other device must not let a known action outrank a newer one.
        assertEquals(KEEP_LOCAL, decide(session(ConnectPlayIntent(10, 999_999_999L, "pc"))))
        assertEquals(KEEP_LOCAL, decide(session(ConnectPlayIntent(9, 999_999_999L, "pc"))))
    }

    @Test fun routineReportsCarryTheOldIntentSoTheyNeverOutrankAnAction() {
        val checkpointed = session(ConnectPlayIntent(10, 1_000L, "pc")).copy(version = 900, updatedAtMs = 500_000L)
        assertEquals(KEEP_LOCAL, decide(checkpointed))
    }

    @Test fun anUnseenIntentIsOrderedByTimeWithAMargin() {
        assertEquals(KEEP_LOCAL, decide(session(ConnectPlayIntent(11, 90_000L, "pc"))))
        assertEquals(KEEP_SESSION, decide(session(ConnectPlayIntent(11, 120_000L, "pc"))))
    }

    @Test fun actionsTooCloseToOrderLeaveWhatTheSessionCommitted() {
        assertEquals(KEEP_SESSION, decide(session(ConnectPlayIntent(11, 100_000L, "pc"))))
        assertEquals(KEEP_SESSION, decide(session(ConnectPlayIntent(11, 97_000L, "pc"))))
        assertEquals(KEEP_SESSION, decide(session(ConnectPlayIntent(11, 102_999L, "pc"))))
    }

    @Test fun anUnseenIntentWaitsForATrustedClockInsteadOfGuessing() {
        assertEquals(WAIT_FOR_CLOCK, decide(session(ConnectPlayIntent(11, 90_000L, "pc")), tap = null))
    }

    @Test fun anActionBeforeAnyReadTreatsEveryIntentAsUnseen() {
        assertEquals(KEEP_LOCAL, decide(session(ConnectPlayIntent(3, 1_000L, "pc")), baseEpoch = null, baseSeq = null))
        assertEquals(KEEP_SESSION, decide(session(ConnectPlayIntent(3, 200_000L, "pc")), baseEpoch = null, baseSeq = null))
    }

    @Test fun ownIntentNeverCountsAgainstThisDevice() {
        assertEquals(KEEP_LOCAL, decide(session(ConnectPlayIntent(11, 500_000L, "phone"))))
    }

    @Test fun aSessionWithoutIntentsFallsBackToOwnership() {
        assertEquals(KEEP_LOCAL, decide(session(null, epoch = 5L)))
        assertEquals(KEEP_SESSION, decide(session(null, epoch = 6L)))
        assertEquals(KEEP_LOCAL, decide(session(null, epoch = 6L), baseEpoch = null))
    }

    @Test fun theIntentSurvivesFirestoreAndIsLeftOutUntilThereIsOne() {
        val stamped = session(ConnectPlayIntent(4, 1234L, "pc"))
        assertEquals(stamped.intent, ConnectCodec.decodeSession(ConnectCodec.encodeSession(stamped)).intent)
        assertFalse("intent" in ConnectCodec.encodeSession(session(null)))
        assertNull(ConnectCodec.decodeSession(ConnectCodec.encodeSession(session(null))).intent)
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
