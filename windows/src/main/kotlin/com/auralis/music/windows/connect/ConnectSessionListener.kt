package com.auralis.music.windows.connect

import com.auralis.music.windows.together.FirestoreRest
import com.google.firestore.v1.Document
import com.google.firestore.v1.FirestoreGrpc
import com.google.firestore.v1.ListenRequest
import com.google.firestore.v1.ListenResponse
import com.google.firestore.v1.Target
import com.google.firestore.v1.TargetChange
import com.google.firestore.v1.Value
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.TimeUnit

/** A persistent, authenticated Firestore watch. No service account or admin SDK. */
internal class ConnectSessionListener(
    private val api: FirestoreRest,
    private val uid: String,
    private val channelFactory: () -> ManagedChannel = {
        ManagedChannelBuilder.forAddress("firestore.googleapis.com", 443).useTransportSecurity().build()
    }
) {
    sealed interface Event {
        data class Changed(val document: Document) : Event
        data class Deleted(val name: String) : Event
        data object Current : Event
    }

    fun events(): Flow<Event> = callbackFlow {
        val auth = api.listenCredentials(uid)
        val channel = channelFactory()
        val headers = Metadata().apply {
            put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer ${auth.bearer}")
            put(Metadata.Key.of("google-cloud-resource-prefix", Metadata.ASCII_STRING_MARSHALLER), auth.database)
        }
        val responses = object : StreamObserver<ListenResponse> {
            override fun onNext(value: ListenResponse) {
                when {
                    value.hasDocumentChange() -> trySend(Event.Changed(value.documentChange.document))
                    value.hasDocumentDelete() -> trySend(Event.Deleted(value.documentDelete.document))
                    value.hasTargetChange() -> when (value.targetChange.targetChangeType) {
                        TargetChange.TargetChangeType.CURRENT -> trySend(Event.Current)
                        TargetChange.TargetChangeType.REMOVE -> close(Status.fromCodeValue(value.targetChange.cause.code).asRuntimeException())
                        TargetChange.TargetChangeType.RESET -> close(Status.ABORTED.asRuntimeException())
                        else -> Unit
                    }
                }
            }
            override fun onError(t: Throwable) { close(t) }
            override fun onCompleted() { close() }
        }
        val requests = FirestoreGrpc.newStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            .withDeadlineAfter(45, TimeUnit.MINUTES)
            .listen(responses)
        try {
            requests.onNext(ListenRequest.newBuilder().setDatabase(auth.database).setAddTarget(
                Target.newBuilder().setTargetId(1).setDocuments(Target.DocumentsTarget.newBuilder()
                    .addDocuments("${auth.database}/documents/users/$uid/connect/session")
                    .addDocuments("${auth.database}/documents/users/$uid/connect/queue"))
            ).build())
            awaitClose { channel.shutdownNow() }
        } finally {
            channel.shutdownNow()
        }
    }.buffer(Channel.UNLIMITED)

    companion object {
        internal fun fields(document: Document): Map<String, Any?> = document.fieldsMap.mapValues { decode(it.value) }

        private fun decode(value: Value): Any? = when (value.valueTypeCase) {
            Value.ValueTypeCase.BOOLEAN_VALUE -> value.booleanValue
            Value.ValueTypeCase.INTEGER_VALUE -> value.integerValue
            Value.ValueTypeCase.DOUBLE_VALUE -> value.doubleValue
            Value.ValueTypeCase.STRING_VALUE -> value.stringValue
            Value.ValueTypeCase.TIMESTAMP_VALUE -> value.timestampValue.seconds * 1000L + value.timestampValue.nanos / 1_000_000L
            Value.ValueTypeCase.MAP_VALUE -> value.mapValue.fieldsMap.mapValues { decode(it.value) }
            Value.ValueTypeCase.ARRAY_VALUE -> value.arrayValue.valuesList.map(::decode)
            else -> null
        }
    }
}
