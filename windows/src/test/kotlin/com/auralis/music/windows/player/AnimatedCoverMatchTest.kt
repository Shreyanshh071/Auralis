package com.auralis.music.windows.player

import kotlin.test.*

/** Animated covers must come from the same recording, not just a song with the same name. */
class AnimatedCoverMatchTest {
    private fun same(a: String, b: String) = CanvasLookup.titleKey(a) == CanvasLookup.titleKey(b)

    @Test
    fun versionTagsMakeADifferentRecording() {
        // Apple's search put a DJ mix's "Let Down (Mixed)" (with that compilation's video) next to Radiohead's.
        assertFalse(same("Let Down", "Let Down (Mixed)"))
        assertFalse(same("Creep", "Creep (Acoustic)"))
        assertFalse(same("Wonderwall", "Wonderwall - Live"))
        assertFalse(same("Blinding Lights", "Blinding Lights (Chromatics Remix)"))
        assertFalse(same("Heat Waves", "Heat Waves [Sped Up]"))
    }

    @Test
    fun tagsThatKeepTheRecordingAreIgnored() {
        assertTrue(same("Where Is My Mind? - 2007 Remaster", "Where Is My Mind?"))
        assertTrue(same("Let Down (Remastered)", "Let Down"))
        assertTrue(same("Bitter Sweet Symphony - Remastered 2016", "Bitter Sweet Symphony"))
        assertTrue(same("Stay (feat. Justin Bieber)", "Stay"))
        assertTrue(same("Counting Stars (Official Video)", "Counting Stars"))
        assertTrue(same("Feel Good Inc.", "Feel Good Inc"))
        assertTrue(same("Mr. Brightside [Explicit]", "Mr. Brightside"))
    }

    @Test
    fun aMatchingTagOnBothSidesStillMatches() {
        assertTrue(same("Wonderwall (Live)", "Wonderwall - Live"))
    }
}
