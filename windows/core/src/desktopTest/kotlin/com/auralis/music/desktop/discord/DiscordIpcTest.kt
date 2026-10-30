package com.auralis.music.desktop.discord

import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class DiscordIpcTest {

    @Test
    fun unixSocketCandidatesProduceValidFilePaths() {
        val candidates = DiscordIpc.unixSocketCandidates()
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.any { it.name == "discord-ipc-0" })
        assertTrue(candidates.any { it.name == "discord-ipc-9" })
    }

    @Test
    fun unixSocketHandshakeAndSetActivityFlow() = runBlocking {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (isWindows) return@runBlocking

        val tempDir = Files.createTempDirectory("discord-test").toFile()
        val socketFile = File(tempDir, "discord-ipc-0")

        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketFile.toPath()))

        val serverJob = launch(Dispatchers.IO) {
            val clientChannel = server.accept()
            try {
                // 1. Read handshake
                val hsHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                while (hsHeader.hasRemaining()) clientChannel.read(hsHeader)
                hsHeader.flip()
                val hsOp = hsHeader.getInt()
                val hsLen = hsHeader.getInt()
                val hsPayload = ByteBuffer.allocate(hsLen)
                while (hsPayload.hasRemaining()) clientChannel.read(hsPayload)
                val hsJson = JSONObject(String(hsPayload.array(), Charsets.UTF_8))

                assertEquals(0, hsOp) // OP_HANDSHAKE
                assertEquals("test-client-id", hsJson.getString("client_id"))

                // Reply with READY event
                val readyBody = JSONObject()
                    .put("cmd", "DISPATCH")
                    .put("evt", "READY")
                    .put("data", JSONObject().put("user", JSONObject().put("username", "TestUser")))
                val readyBytes = readyBody.toString().toByteArray(Charsets.UTF_8)
                val readyBuf = ByteBuffer.allocate(8 + readyBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(1).putInt(readyBytes.size).put(readyBytes)
                readyBuf.flip()
                while (readyBuf.hasRemaining()) clientChannel.write(readyBuf)

                // 2. Read SET_ACTIVITY
                val actHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                while (actHeader.hasRemaining()) clientChannel.read(actHeader)
                actHeader.flip()
                val actOp = actHeader.getInt()
                val actLen = actHeader.getInt()
                val actPayload = ByteBuffer.allocate(actLen)
                while (actPayload.hasRemaining()) clientChannel.read(actPayload)
                val actJson = JSONObject(String(actPayload.array(), Charsets.UTF_8))

                assertEquals(1, actOp) // OP_FRAME
                assertEquals("SET_ACTIVITY", actJson.getString("cmd"))
                val activity = actJson.getJSONObject("args").getJSONObject("activity")
                assertEquals("Test Track", activity.getString("details"))

                // Reply with SET_ACTIVITY ack
                val actResp = JSONObject()
                    .put("cmd", "SET_ACTIVITY")
                    .put("data", activity)
                    .put("nonce", actJson.getString("nonce"))
                val actRespBytes = actResp.toString().toByteArray(Charsets.UTF_8)
                val actRespBuf = ByteBuffer.allocate(8 + actRespBytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(1).putInt(actRespBytes.size).put(actRespBytes)
                actRespBuf.flip()
                while (actRespBuf.hasRemaining()) clientChannel.write(actRespBuf)
            } finally {
                clientChannel.close()
            }
        }

        val ipc = DiscordIpc("test-client-id", socketCandidates = { listOf(socketFile) })
        try {
            val ready = ipc.connect()
            assertNotNull(ready)
            assertEquals("TestUser", ready.getJSONObject("user").getString("username"))
            assertTrue(ipc.isConnected)

            val activity = JSONObject().put("details", "Test Track").put("state", "Test Artist")
            val sent = ipc.setActivity(activity)
            assertTrue(sent)

            ipc.close()
            assertFalse(ipc.isConnected)
        } finally {
            serverJob.join()
            server.close()
            socketFile.delete()
            tempDir.delete()
        }
    }

    @Test
    fun connectFailsGracefullyWhenNoSocketAvailable() {
        val nonExistentDir = File(System.getProperty("java.io.tmpdir"), "non-existent-" + UUID.randomUUID())
        val ipc = DiscordIpc("test-client-id", socketCandidates = { listOf(File(nonExistentDir, "discord-ipc-0")) })
        val result = ipc.connect()
        assertNull(result)
        assertFalse(ipc.isConnected)
    }

    @Test
    fun liveDiscordConnectIfRunning() {
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (isWindows) return
        val hasSocket = DiscordIpc.unixSocketCandidates().any { it.exists() }
        if (!hasSocket) return
        val ipc = DiscordIpc("1553114990909591732")
        val ready = ipc.connect()
        assertNotNull(ready, "Expected to connect to running Discord socket")
        assertTrue(ipc.isConnected)
        val user = ready.optJSONObject("user")
        assertNotNull(user)
        assertTrue(user.optString("username").isNotBlank())
        ipc.close()
        assertFalse(ipc.isConnected)
    }
}
