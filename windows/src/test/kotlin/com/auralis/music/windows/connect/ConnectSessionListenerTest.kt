package com.auralis.music.windows.connect

import com.auralis.music.windows.together.FirestoreRest
import com.google.firestore.v1.*
import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.test.*

class ConnectSessionListenerTest {
    private fun api() = FirestoreRest("test-project", "test-key") { _, _, _, _ ->
        JSONObject().put("idToken", "test-token").put("refreshToken", "test-refresh")
            .put("localId", "account").put("expiresIn", "3600")
    }.apply { signInEmail("test@example.com", "test-password") }

    @Test fun streamsChangesAndCurrentWithoutPollingAndClosesOnCancellation() = runBlocking {
        val name = InProcessServerBuilder.generateName()
        var requested: ListenRequest? = null
        val doc = Document.newBuilder().setName("session").putFields("playing", Value.newBuilder().setBooleanValue(false).build()).build()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(object : FirestoreGrpc.FirestoreImplBase() {
            override fun listen(out: StreamObserver<ListenResponse>): StreamObserver<ListenRequest> = object : StreamObserver<ListenRequest> {
                override fun onNext(value: ListenRequest) {
                    requested = value
                    out.onNext(ListenResponse.newBuilder().setDocumentChange(DocumentChange.newBuilder().setDocument(doc)).build())
                    out.onNext(ListenResponse.newBuilder().setTargetChange(TargetChange.newBuilder().setTargetChangeType(TargetChange.TargetChangeType.CURRENT)).build())
                }
                override fun onError(t: Throwable) = Unit
                override fun onCompleted() = out.onCompleted()
            }
        }).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            val events = withTimeout(5_000) { ConnectSessionListener(api(), "account") { channel }.events().take(2).toList() }
            assertEquals(doc, (events[0] as ConnectSessionListener.Event.Changed).document)
            assertEquals(ConnectSessionListener.Event.Current, events[1])
            assertEquals(2, requested!!.addTarget.documents.documentsCount)
            assertTrue(requested!!.addTarget.documents.documentsList.all { it.contains("/users/account/connect/") })
            assertTrue(channel.isShutdown)
        } finally { channel.shutdownNow(); server.shutdownNow() }
    }

    @Test fun permissionFailureEndsStreamSoCallerCanFallBack() = runBlocking {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(object : FirestoreGrpc.FirestoreImplBase() {
            override fun listen(out: StreamObserver<ListenResponse>): StreamObserver<ListenRequest> = object : StreamObserver<ListenRequest> {
                override fun onNext(value: ListenRequest) {
                    out.onNext(ListenResponse.newBuilder().setTargetChange(TargetChange.newBuilder()
                        .setTargetChangeType(TargetChange.TargetChangeType.REMOVE)
                        .setCause(com.google.rpc.Status.newBuilder().setCode(7))).build())
                }
                override fun onError(t: Throwable) = Unit
                override fun onCompleted() = Unit
            }
        }).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            val error = assertFails { withTimeout(5_000) { ConnectSessionListener(api(), "account") { channel }.events().toList() } }
            assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(error).code)
        } finally { channel.shutdownNow(); server.shutdownNow() }
    }

    @Test fun decodesServerTimestampAndNestedTrackData() {
        val doc = Document.newBuilder()
            .putFields("positionAt", Value.newBuilder().setTimestampValue(Timestamp.newBuilder().setSeconds(123).setNanos(456_000_000)).build())
            .putFields("track", Value.newBuilder().setMapValue(MapValue.newBuilder().putFields("id", Value.newBuilder().setStringValue("song").build())).build())
            .build()
        val fields = ConnectSessionListener.fields(doc)
        assertEquals(123_456L, fields["positionAt"])
        assertEquals("song", (fields["track"] as Map<*, *>)["id"])
    }
}
