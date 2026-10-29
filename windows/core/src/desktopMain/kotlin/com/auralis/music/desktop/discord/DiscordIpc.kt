package com.auralis.music.desktop.discord

import org.json.JSONObject
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Discord's local Rich Presence connection: the named pipe the Discord desktop app opens
 * (\\.\pipe\discord-ipc-N). Frames are a little-endian opcode and length, then JSON. No account
 * token passes through Auralis; the Discord app shows the activity for whoever is signed in there.
 */
class DiscordIpc(private val clientId: String) {
    private var pipe: RandomAccessFile? = null

    val isConnected: Boolean get() = pipe != null

    /** Opens the pipe and handshakes; returns the READY event's data (it holds the user), or null. */
    @Synchronized
    fun connect(): JSONObject? {
        close()
        if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return null
        for (i in 0..9) {
            val file = runCatching { RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw") }.getOrNull() ?: continue
            pipe = file
            try {
                write(OP_HANDSHAKE, JSONObject().put("v", 1).put("client_id", clientId))
                val (op, body) = read()
                if (op == OP_FRAME && body.optString("evt") == "READY") return body.optJSONObject("data") ?: JSONObject()
            } catch (_: Exception) {
            }
            close()
        }
        return null
    }

    /** Shows [activity] (Discord's activity object), or clears it when null. False if the pipe broke. */
    @Synchronized
    fun setActivity(activity: JSONObject?): Boolean {
        if (pipe == null) return false
        return try {
            val args = JSONObject().put("pid", ProcessHandle.current().pid()).put("activity", activity ?: JSONObject.NULL)
            write(OP_FRAME, JSONObject().put("cmd", "SET_ACTIVITY").put("args", args).put("nonce", UUID.randomUUID().toString()))
            val (op, body) = read()
            op == OP_FRAME && body.optString("evt") != "ERROR"
        } catch (_: Exception) {
            close()
            false
        }
    }

    @Synchronized
    fun close() {
        runCatching { pipe?.close() }
        pipe = null
    }

    private fun write(op: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(op).putInt(payload.size).put(payload)
        pipe!!.write(frame.array())
    }

    private fun read(): Pair<Int, JSONObject> {
        val header = ByteArray(8)
        pipe!!.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val op = buffer.int
        val length = buffer.int
        require(length in 0..(1 shl 20)) { "Bad Discord frame length $length" }
        val payload = ByteArray(length)
        pipe!!.readFully(payload)
        return op to JSONObject(String(payload, Charsets.UTF_8))
    }

    private companion object {
        const val OP_HANDSHAKE = 0
        const val OP_FRAME = 1
    }
}
