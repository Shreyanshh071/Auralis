package com.auralis.music.data.connect

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectPresenceWriteTest {
    @Test fun closingWaitsForAnInflightHeartbeatBeforeRemovingRegistration() = runTest {
        val db = mockk<FirebaseFirestore>()
        val devices = mockk<CollectionReference>()
        val device = mockk<DocumentReference>()
        every { db.document(any()) } returns mockk(relaxed = true)
        every { db.collection(any()) } returns devices
        every { devices.document("phone") } returns device
        val pendingHeartbeat = TaskCompletionSource<Void>()
        every { device.set(any<Any>()) } returns pendingHeartbeat.task
        every { device.delete() } returns Tasks.forResult<Void>(null)
        val backend = FirestoreConnectBackend(db, "account", ServerClock { 1000L })
        val heartbeat = launch { backend.heartbeat(ConnectDevice("phone")) }
        runCurrent()
        val close = launch { backend.removeDevice("phone") }
        runCurrent()
        verify(exactly = 0) { device.delete() }
        assertFalse(close.isCompleted)
        pendingHeartbeat.setResult(null)
        runCurrent()
        assertTrue(heartbeat.isCompleted)
        assertTrue(close.isCompleted)
        verify(exactly = 1) { device.delete() }
    }
}
