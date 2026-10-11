package com.auralis.music.windows.together

import com.auralis.music.domain.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.test.*

class RoomProtocolTest {
    @Test fun firestoreValuesRoundTripWithoutLosingTypes() {
        val value = JSONObject().put("id", "room").put("isPlaying", true).put("position", 1234L)
            .put("rate", 1.25).put("none", JSONObject.NULL).put("queue", JSONArray().put(JSONObject().put("title", "日本語")))
        val restored = FirestoreRest.decodeFields(FirestoreRest.encodeFields(value))
        assertEquals(1234L, restored.getLong("position"))
        assertTrue(restored.getBoolean("isPlaying"))
        assertEquals(1.25, restored.getDouble("rate"))
        assertTrue(restored.isNull("none"))
        assertEquals("日本語", restored.getJSONArray("queue").getJSONObject(0).getString("title"))
        assertEquals(1000L, FirestoreRest.decode(FirestoreRest.encode(Instant.ofEpochMilli(1000))))
    }
    @Test fun guestUsesExactHostRecordingAndPausedTimeDoesNotAdvance() {
        val song = Track(id = "catalog", title = "Song", artist = "Artist")
        assertEquals("video", DesktopTogether.parseTrack(DesktopTogether.trackJson(song).put("videoId", "video"), current = true).id)
        assertEquals("catalog", DesktopTogether.parseTrack(DesktopTogether.trackJson(song).put("videoId", JSONObject.NULL), current = true).id)
        assertEquals(2000L, DesktopTogether.estimatedPosition(1000, 10_000, true, 11_000, 1.0))
        assertEquals(1000L, DesktopTogether.estimatedPosition(1000, 10_000, false, 11_000, 1.0))
        assertEquals(1000L, DesktopTogether.estimatedPosition(1000, 12_000, true, 11_000, 1.0))
    }
}
