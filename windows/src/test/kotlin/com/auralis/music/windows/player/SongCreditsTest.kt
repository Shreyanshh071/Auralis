package com.auralis.music.windows.player

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SongCreditsTest {
    @Test fun parsesYouTubeMusicCreditsIntoPeopleWithTheirRoles() {
        val json = javaClass.getResource("/credits-blinding-lights.json")!!.readText()
        val credits = SongCredits.parse(JSONObject(json))
        val weeknd = credits.first()
        assertEquals("The Weeknd", weeknd.name)
        assertEquals(listOf("Main Artist", "Producer"), weeknd.roles)
        assertEquals("UClYV6hHlupm_S_ObS1W-DYw", weeknd.channelId)
        assertEquals(listOf("Writer", "Producer"), credits.first { it.name == "Max Martin" }.roles)
        // "Music metadata provided by" (the label) is not a person.
        assertEquals(null, credits.firstOrNull { it.name == "Republic Records" })
    }
}
