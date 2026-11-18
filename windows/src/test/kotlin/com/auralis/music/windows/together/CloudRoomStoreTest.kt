package com.auralis.music.windows.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PC's room store against the real room server. Runs when AURALIS_CONNECT_PORT names a local
 * `wrangler dev` (connect-server/test/serve.mjs starts one). Nothing leaves this machine.
 */
class CloudRoomStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @AfterTest fun stop() { scope.cancel() }

    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) { check(System.currentTimeMillis() < until) { "timed out" }; Thread.sleep(50) }
    }

    @Test fun `host and guest run a room the way DesktopTogether uses it`() {
        val port = System.getenv("AURALIS_CONNECT_PORT")?.toIntOrNull() ?: return
        val base = "http://127.0.0.1:$port"
        val code = DesktopTogether.generateCode()
        val host = CloudRoomStore(base, scope) { "dev:pchost" }
        val guest = CloudRoomStore(base, scope) { "dev:pcguest" }
        val now = System.currentTimeMillis()
        assertFalse(guest.has(code), "nothing is there before the host creates it")

        host.createRoom(code, JSONObject().put("hostName", "PC").put("isPlaying", true).put("playbackPosition", 0).put("updatedAt", now)
            .put("expireAt", java.time.Instant.now()), JSONObject().put("id", "pchost").put("name", "PC").put("lastSeen", now))
        assertTrue(guest.has(code))
        assertEquals("pchost", guest.getRoom(code)!!.getString("hostId"))
        guest.joinRoom(code, JSONObject().put("id", "pcguest").put("name", "Guest").put("lastSeen", now))
        eventually { host.listMembers(code).size == 2 }
        assertTrue(guest.getMember(code, "pchost")!!.optLong("serverSeen") > 0)

        host.writeRoom(code, JSONObject().put("isPlaying", false).put("playbackPosition", 12_000).put("updatedAt", System.currentTimeMillis()), stamp = true)
        eventually { guest.getRoom(code)?.optBoolean("isPlaying", true) == false }
        assertEquals(12_000L, guest.getRoom(code)!!.getLong("playbackPosition"))

        guest.heartbeat(code, System.currentTimeMillis())
        assertTrue(kotlin.math.abs(guest.offsetMs) < 60_000, "the local server's clock is this machine's")
        guest.writeNetwork(code, JSONObject().put("readyFor", "t1"))
        guest.writeSelf(code, JSONObject().put("command", JSONObject().put("type", "pause").put("seq", 3)))
        eventually { host.getMember(code, "pcguest")?.optJSONObject("command")?.optLong("seq") == 3L }
        assertEquals("t1", host.getMember(code, "pcguest")!!.getJSONObject("network").getString("readyFor"))

        guest.addRec(code, "ignored", JSONObject().put("track", JSONObject().put("id", "x").put("title", "X")).put("recommendedByName", "Guest").put("note", ""))
        eventually { host.listRecs(code).size == 1 }
        val id = host.listRecs(code).single().first.substringAfterLast('/')
        host.toggleUpvote(code, id)
        host.setRecStatus(code, id, "accepted")
        eventually { guest.listRecs(code).singleOrNull()?.second?.optString("status") == "accepted" }
        assertEquals(2, guest.listRecs(code).single().second.getJSONArray("upvotes").length())

        host.deleteRoom(code)
        eventually { guest.getRoom(code) == null }
        runCatching { guest.removeSelf(code) }
        assertNull(guest.getRoom(code))
    }
}
