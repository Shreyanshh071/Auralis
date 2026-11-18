package com.auralis.music

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.ImmersivePlayerContainer
import com.auralis.music.ui.player.NowPlayingTab
import com.auralis.music.ui.viewmodel.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersiveScrubberGestureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var pager: PagerState
    private var songSwipeStarts = 0
    private val seeks = mutableListOf<Long>()
    private val style = mutableStateOf("Wavy")
    private val inlineLyrics = mutableStateOf(false)

    private fun showPlayer() {
        val tracks = (0..2).map { Track(id = "gesture-$it", title = "Song $it", artist = "Artist") }
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme {
                pager = rememberPagerState(initialPage = 1, pageCount = { tracks.size })
                // Nonzero origin and bottom chrome exercise the root/local coordinate conversion.
                Box(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 38.dp)) {
                    ImmersivePlayerContainer(
                        track = tracks[1],
                        uiState = PlayerUiState(currentTrack = tracks[1], showInlineLyrics = inlineLyrics.value),
                        currentTab = NowPlayingTab.PLAYER,
                        onTabChange = {},
                        pagerState = pager,
                        queue = tracks,
                        currentTrackIndex = 1,
                        seekBarPositionState = mutableStateOf(30_000L),
                        totalDurationMs = 180_000L,
                        isScrubbing = false,
                        onScrubbing = { _, _ -> },
                        onSeekTo = { seeks += it },
                        onHeroSwipeStart = { songSwipeStarts++ },
                        onPlayPauseClick = {}, onNextClick = {}, onPreviousClick = {},
                        onToggleFavorite = {}, onDismiss = {}, onSelectQueueTrack = {},
                        onShowTrackOptions = {}, onShowSleepDialog = {}, onShowOutputPicker = {},
                        lyricsPositionState = mutableStateOf(30_000L), lyricsClockSource = null,
                        onLyricsOffsetChange = {}, onSearchLyricsManually = {},
                        sliderStyle = style.value
                    )
                }
            }
        } }
    }

    @Test fun scrubbingBothDirectionsSeeksWithoutMovingTheSongPager() {
        showPlayer()
        for (showLyrics in listOf(false, true)) {
            for (sliderStyle in listOf("Default", "Wavy", "Slim", "Squiggly")) {
                compose.runOnIdle { style.value = sliderStyle; inlineLyrics.value = showLyrics }
                compose.waitForIdle()
                // The interactive track is the first 44dp of the slider; timestamps are below it.
                val trackY = with(compose.density) { 22.dp.toPx() }
                for ((start, end) in listOf(.15f to .85f, .85f to .15f)) {
                    val before = seeks.size
                    compose.onNodeWithTag("immersiveScrubber").performTouchInput {
                        swipe(Offset(width * start, trackY), Offset(width * end, trackY), 600)
                    }
                    compose.runOnIdle {
                        assertEquals("One seek per scrub", before + 1, seeks.size)
                        assertEquals((180_000 * end).toLong().toDouble(), seeks.last().toDouble(), 3_000.0)
                        assertEquals("Scrubbing must not claim a song swipe", 0, songSwipeStarts)
                        assertEquals(1, pager.currentPage)
                        assertEquals(0f, pager.currentPageOffsetFraction, .001f)
                    }
                }
            }
        }
    }

    @Test fun swipingAboveTheScrubberStillChangesPagerPage() {
        showPlayer()
        val slider = compose.onNodeWithTag("immersiveScrubber").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            val y = slider.top / 2f
            swipe(Offset(width * .85f, y), Offset(width * .15f, y), 600)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(songSwipeStarts > 0)
            assertEquals(2, pager.settledPage)
            assertTrue(seeks.isEmpty())
        }
    }
}
