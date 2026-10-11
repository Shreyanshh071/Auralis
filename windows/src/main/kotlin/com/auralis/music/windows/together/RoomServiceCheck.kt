package com.auralis.music.windows.together

import com.auralis.music.domain.model.Track
import org.json.JSONObject

/** An isolated protocol check: no user rooms, settings, library, media keys or audio involved. */
fun main() {
    val host = FirestoreRest.fromResource()
    val guest = FirestoreRest.fromResource()
    val code = DesktopTogether.generateCode()
    val path = "rooms/$code"
    var created = false
    try {
        host.authenticate(); guest.authenticate()
        val now = System.currentTimeMillis()
        host.write(path, JSONObject().put("id", code).put("code", code).put("hostId", host.uid).put("hostName", "Windows protocol check")
            .put("isPlaying", false).put("playbackPosition", 1234).put("updatedAt", now).put("status", "active")
            .put("currentTrack", DesktopTogether.trackJson(Track(id = "check", title = "Protocol check", artist = "Auralis"))), "serverUpdatedAt", merge = false, create = true)
        created = true
        println("PASS host creates room with server timestamp")
        val member = JSONObject().put("id", guest.uid).put("name", "Temporary guest").put("isHost", false).put("lastSeen", now)
        guest.write("$path/members/${guest.uid}", member, "serverSeen", merge = false)
        check(guest.get(path)?.optLong("playbackPosition") == 1234L)
        check(host.list("$path/members").size == 1)
        println("PASS guest joins and reads room; host reads roster")
        guest.write("$path/members/${guest.uid}", JSONObject().put("command", JSONObject().put("type", "play").put("seq", 1)))
        check(host.list("$path/members").single().second.getJSONObject("command").getString("type") == "play")
        try {
            guest.write(path, JSONObject().put("isPlaying", true))
            error("Security failure: a guest modified the host room")
        } catch (e: FirestoreRest.ServiceException) { check(e.status == 403) }
        println("PASS guest command exchange and host-only room security")
        host.write(path, JSONObject().put("isPlaying", true).put("playbackPosition", 5678), "serverUpdatedAt")
        val update = guest.get(path)!!
        check(update.getBoolean("isPlaying") && update.getLong("playbackPosition") == 5678L && update.getLong("serverUpdatedAt") > 0)
        println("PASS playback updates and server time round trip")
    } finally {
        if (created) {
            host.delete(path)
            host.list("$path/members").forEach { host.delete(it.first) }
        }
        host.deleteAnonymousAccount(); guest.deleteAnonymousAccount()
        println("Temporary room and anonymous participants removed")
    }
}
