package com.auralis.music.desktop.discord

import com.auralis.music.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * Discord's local Rich Presence connection:
 * - On Windows: the named pipe the Discord desktop app opens (\\.\pipe\discord-ipc-N).
 * - On macOS & Linux: the UNIX domain socket the Discord desktop app opens (discord-ipc-N).
 * Frames are a little-endian opcode and length, then JSON. No account token passes through Auralis;
 * the Discord app shows the activity for whoever is signed in there.
 */
class DiscordIpc(
    private val clientId: String,
    private val socketCandidates: (() -> List<File>)? = null
) {
    private var connection: IpcConnection? = null

    val isConnected: Boolean get() = connection != null

    /** Opens the pipe/socket and handshakes; returns the READY event's data (it holds the user), or null. */
    @Synchronized
    fun connect(): JSONObject? {
        close()
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        if (isWindows) {
            for (i in 0..9) {
                val file = runCatching { RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw") }.getOrNull() ?: continue
                connection = WindowsPipeConnection(file)
                try {
                    write(OP_HANDSHAKE, JSONObject().put("v", 1).put("client_id", clientId))
                    val (op, body) = read()
                    if (op == OP_FRAME && body.optString("evt") == "READY") {
                        val user = body.optJSONObject("data")?.optJSONObject("user")?.optString("username")
                        Log.i(TAG, "Connected to Discord IPC on Windows pipe: user=$user")
                        return body.optJSONObject("data") ?: JSONObject()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Pipe handshake failed: ${e.message}")
                }
                close()
            }
        } else {
            val candidates = socketCandidates?.invoke() ?: unixSocketCandidates()
            for (candidate in candidates) {
                if (!candidate.exists()) continue
                val conn = runCatching {
                    val address = UnixDomainSocketAddress.of(candidate.toPath())
                    val channel = SocketChannel.open(address)
                    UnixSocketConnection(channel)
                }.getOrNull() ?: continue
                connection = conn
                try {
                    write(OP_HANDSHAKE, JSONObject().put("v", 1).put("client_id", clientId))
                    val (op, body) = read()
                    if (op == OP_FRAME && body.optString("evt") == "READY") {
                        val user = body.optJSONObject("data")?.optJSONObject("user")?.optString("username")
                        Log.i(TAG, "Connected to Discord IPC at ${candidate.absolutePath}: user=$user")
                        return body.optJSONObject("data") ?: JSONObject()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Socket handshake failed at ${candidate.absolutePath}: ${e.message}")
                }
                close()
            }
        }
        return null
    }

    /** Shows [activity] (Discord's activity object), or clears it when null. False if the pipe/socket broke. */
    @Synchronized
    fun setActivity(activity: JSONObject?): Boolean {
        if (connection == null) return false
        return try {
            val args = JSONObject().put("pid", ProcessHandle.current().pid()).put("activity", activity ?: JSONObject.NULL)
            write(OP_FRAME, JSONObject().put("cmd", "SET_ACTIVITY").put("args", args).put("nonce", UUID.randomUUID().toString()))
            val (op, body) = read()
            val ok = op == OP_FRAME && body.optString("evt") != "ERROR"
            if (ok) {
                Log.d(TAG, "Sent Discord activity: ${activity?.optString("details") ?: "cleared"}")
            } else {
                Log.w(TAG, "Discord activity rejected: evt=${body.optString("evt")}")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send Discord activity: ${e.message}")
            close()
            false
        }
    }

    @Synchronized
    fun close() {
        runCatching { connection?.close() }
        connection = null
    }

    private fun write(op: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op).putInt(payload.size).put(payload)
        connection!!.write(frame.array())
    }

    private fun read(): Pair<Int, JSONObject> {
        val header = ByteArray(8)
        connection!!.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val op = buffer.int
        val length = buffer.int
        require(length in 0..(1 shl 20)) { "Bad Discord frame length $length" }
        val payload = ByteArray(length)
        connection!!.readFully(payload)
        return op to JSONObject(String(payload, Charsets.UTF_8))
    }

    private interface IpcConnection : Closeable {
        fun write(bytes: ByteArray)
        fun readFully(bytes: ByteArray)
    }

    private class WindowsPipeConnection(private val pipe: RandomAccessFile) : IpcConnection {
        override fun write(bytes: ByteArray) = pipe.write(bytes)
        override fun readFully(bytes: ByteArray) = pipe.readFully(bytes)
        override fun close() = pipe.close()
    }

    private class UnixSocketConnection(private val channel: SocketChannel) : IpcConnection {
        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
        }

        override fun readFully(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) throw EOFException("Discord IPC socket closed")
            }
        }

        override fun close() = channel.close()
    }

    companion object {
        private const val TAG = "DiscordIpc"
        const val OP_HANDSHAKE = 0
        const val OP_FRAME = 1

        fun unixSocketCandidates(): List<File> {
            val dirs = linkedSetOf<File>()
            listOf("TMPDIR", "XDG_RUNTIME_DIR", "TMP", "TEMP").forEach { env ->
                System.getenv(env)?.takeIf { it.isNotBlank() }?.let { dirs.add(File(it)) }
            }
            System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let { dirs.add(File(it)) }
            dirs.add(File("/tmp"))
            System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { xdg ->
                dirs.add(File(xdg, "app/com.discordapp.Discord"))
            }
            val candidates = mutableListOf<File>()
            for (i in 0..9) {
                for (dir in dirs) {
                    candidates.add(File(dir, "discord-ipc-$i"))
                }
            }
            return candidates
        }
    }
}
