package com.auralis.music.data.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TogetherRoomSocketTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @AfterTest fun stop() { scope.cancel() }

    @Test fun `Android and Windows use the same room client`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val path = "src/commonMain/kotlin/com/auralis/music/data/together/TogetherRoomSocket.kt"
        assertEquals(File(root, "shared/$path").readText(), File(root, "windows/core/$path").readText(),
            "TogetherRoomSocket.kt differs between shared/ and windows/core/")
    }

    @Test fun `maps survive the trip through JSON with whole numbers as Long`() {
        val map = mapOf("a" to 1, "b" to listOf(mapOf("c" to 2.5f)), "d" to null, "e" to "x")
        val back = TogetherRoomSocket.mapOf(TogetherRoomSocket.jsonOf(map))
        assertEquals(1L, back["a"])
        assertEquals(2.5, ((back["b"] as List<*>)[0] as Map<*, *>)["c"])
        assertEquals(null, back["d"])
        assertTrue("d" in back)
    }

    /**
     * The real client against the real server. Runs when AURALIS_CONNECT_PORT names a local
     * `wrangler dev` (connect-server/test/serve.mjs starts one). Nothing leaves this machine.
     */
    @Test fun `host and guest share a room through the local server`() = runBlocking {
        val port = System.getenv("AURALIS_CONNECT_PORT")?.toIntOrNull() ?: return@runBlocking
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..6).map { alphabet.random() }.joinToString("")
        val http = OkHttpClient()
        fun socket(uid: String) = TogetherRoomSocket("http://127.0.0.1:$port/v1/together", code, scope, http) { "dev:$uid" }.also { it.start() }
        val host = socket("kthost")
        assertTrue(host.awaitLoaded(10_000))
        assertEquals(null, host.state.value.room, "a fresh code is an empty room")
        host.create(mapOf("hostName" to "Host", "isPlaying" to true, "playbackPosition" to 0L, "updatedAt" to System.currentTimeMillis()),
            mapOf("name" to "Host", "lastSeen" to System.currentTimeMillis()))
        assertEquals("kthost", host.state.value.room?.get("hostId"))

        val guest = socket("ktguest")
        assertTrue(guest.awaitLoaded(10_000))
        assertNotNull(guest.state.value.room)
        assertEquals(null, guest.state.value.members, "outsiders don't see the roster")
        guest.join(mapOf("name" to "Guest", "lastSeen" to System.currentTimeMillis()))
        withTimeout(5_000) { host.state.first { it.members?.size == 2 } }

        host.updateRoom(mapOf("isPlaying" to false, "playbackPosition" to 30_000L, "updatedAt" to System.currentTimeMillis()))
        val pushed = withTimeout(5_000) { guest.state.first { it.room?.get("isPlaying") == false } }
        assertEquals(30_000L, pushed.room?.get("playbackPosition"))

        val beat = guest.updateMember(mapOf("lastSeen" to System.currentTimeMillis()))
        assertTrue(beat.serverNow > 0 && beat.ackAt >= beat.sentAt)
        guest.updateMember(mapOf("network" to mapOf("slow" to true)))
        guest.updateMember(mapOf("network" to mapOf("readyFor" to "t1")))
        val roster = withTimeout(5_000) { host.state.first { s -> s.members.orEmpty().any { (it["network"] as? Map<*, *>)?.get("readyFor") == "t1" } } }
        assertEquals(true, (roster.members!!.first { it["id"] == "ktguest" }["network"] as Map<*, *>)["slow"])

        val id = guest.recommend(mapOf("track" to mapOf("id" to "x", "title" to "X"), "recommendedByName" to "Guest", "note" to ""))
        withTimeout(5_000) { host.state.first { it.recs?.any { r -> r["id"] == id } == true } }
        assertFailsWith<TogetherRoomSocket.Refused> { guest.setRecStatus(id, "accepted") }
        host.setRecStatus(id, "accepted")
        withTimeout(5_000) { guest.state.first { it.recs?.firstOrNull()?.get("status") == "accepted" } }

        host.leave()
        val ended = withTimeout(5_000) { guest.state.first { it.ended } }
        assertEquals(null, ended.room)
        guest.stop()
    }
}
