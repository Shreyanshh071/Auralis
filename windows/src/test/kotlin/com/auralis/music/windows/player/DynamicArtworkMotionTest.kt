package com.auralis.music.windows.player

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicArtworkMotionTest {
    @Test fun measuredLoudnessFollowsPlaybackAndRejectsDifferentLengthRecordings() {
        val analysis = JSONObject("""{
            "track":{"duration":10.25},
            "segments":[
                {"start":0,"duration":5,"loudness_start":-30,"loudness_max":-25,"loudness_max_time":1},
                {"start":5,"duration":5,"loudness_start":-10,"loudness_max":-4,"loudness_max_time":1}
            ]
        }""")
        val energy = assertNotNull(ArtworkEnergy.parse(analysis, 10))
        assertTrue(energy.at(6.0) > energy.at(1.0))
        assertTrue(energy.at(1.0) < energy.at(4.0) && energy.at(4.0) <= energy.at(6.0))
        assertNull(ArtworkEnergy.parse(analysis, 14))
    }

    @Test fun loudPassagesMoveFasterAndPausingReturnsToDrift() {
        val quiet = ArtworkMotionClock()
        val loud = ArtworkMotionClock()
        repeat(400) {
            quiet.step(.025f, true, 0f)
            loud.step(.025f, true, 1f)
        }
        assertTrue(loud.time > quiet.time * 2)
        assertTrue(loud.scale > quiet.scale)
        repeat(400) { loud.step(.025f, false, 1f) }
        val before = loud.time
        loud.step(.025f, false, 1f)
        assertTrue(loud.time - before in .002f.. .003f)
        assertTrue(loud.scale < 1.001f)
    }
}
