package com.auralis.music.windows.connect

import com.auralis.music.windows.together.FirestoreRest
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/** Opt-in, read-only verification of TLS/auth/rules against the saved Windows account. */
class ConnectLiveSessionTest {
    @Test fun signedInAccountReceivesCurrentSession() = runBlocking {
        assumeTrue(System.getenv("AURALIS_VERIFY_CONNECT") == "1")
        withContext(Dispatchers.IO) {
            val saved = File(System.getenv("APPDATA"), "Auralis/account-session.bin")
            val plaintext = String(Crypt32Util.cryptUnprotectData(saved.readBytes()), Charsets.UTF_8)
            val refresh = if (plaintext.startsWith("{")) JSONObject(plaintext).getString("refreshToken") else plaintext
            val api = FirestoreRest.fromResource()
            api.restoreAccount(refresh)
            val reads = List(3) {
                val at = System.nanoTime()
                api.accountGet("users/${api.uid}/connect/session")
                (System.nanoTime() - at) / 1_000_000
            }
            println("Read-only pooled session request times: ${reads.joinToString()}ms")
            val started = System.nanoTime()
            withTimeout(20_000) {
                ConnectSessionListener(api, api.uid).events().first { it == ConnectSessionListener.Event.Current }
            }
            println("Authenticated realtime session confirmed in ${(System.nanoTime() - started) / 1_000_000}ms; no playback writes made")
        }
    }
}
