package com.auralis.music

import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.player.*
import org.junit.Assert.*
import org.junit.Test

class FoliaBackgroundsTest {
    @Test fun onlyTheFiveRequestedBackgroundsRemainSelectableWithoutChangingDefaults() {
        val styles = PlayerBackgroundStyle.entries.filter { it.isFolia }
        assertEquals(5, styles.size)
        assertEquals(setOf("Folia · Latent", "Folia · Monet", "Folia · Paper texture", "Folia · Lens distortion", "Folia · Sora"), styles.map { it.displayName }.toSet())
        for (removed in listOf("Folia · Common", "Folia · Nomand · Dithering", "Folia · Nomand · Fluted glass", "Folia · Nomand · Halftone dots"))
            assertEquals(PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.fromKey(removed))
        assertEquals(PlayerBackgroundStyle.FOLIA_NOMAND_PAPER, PlayerBackgroundStyle.fromKey("Folia · Nomand · Paper texture"))
        assertEquals(PlayerBackgroundStyle.FOLIA_NOMAND_LENS, PlayerBackgroundStyle.fromKey("Folia · Nomand · Lens distortion"))
        assertEquals(5, styles.map { it.displayName }.distinct().size)
        styles.forEach { assertEquals(it, PlayerBackgroundStyle.fromKey(it.displayName)); assertEquals(it, PlayerBackgroundStyle.fromKey(it.name)) }
        assertEquals("Blur", AppearanceSettings().playerBackgroundStyle)
        assertEquals("Live Mesh", AppearanceSettings().miniPlayerBackgroundStyle)
        assertTrue(MiniPlayerBackgrounds.options.none { PlayerBackgroundStyle.fromKey(it).isFolia })
        assertEquals(PlayerBackgroundStyle.BLUR, PlayerBackgroundStyle.fromKey("dynamic blurred artwork"))
        assertEquals(PlayerBackgroundStyle.LIVE_MESH, PlayerBackgroundStyle.fromKey("Live Mesh"))
    }

    @Test fun motionStopsForEveryInactiveOrBatterySavingState() {
        assertTrue(foliaMotionAllowed(true, true, true, false, false))
        assertFalse(foliaMotionAllowed(false, true, true, false, false))
        assertFalse(foliaMotionAllowed(true, false, true, false, false))
        assertFalse(foliaMotionAllowed(true, true, false, false, false))
        assertFalse(foliaMotionAllowed(true, true, true, true, false))
        assertFalse(foliaMotionAllowed(true, true, true, false, true))
        assertFalse(PlayerBackgroundStyle.FOLIA_NOMAND_PAPER.foliaAnimated)
        assertFalse(PlayerBackgroundStyle.FOLIA_NOMAND_LENS.foliaAnimated)
    }
}
