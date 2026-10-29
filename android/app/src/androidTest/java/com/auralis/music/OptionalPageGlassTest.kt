package com.auralis.music

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.StatsRepository
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.screens.ListenTogetherSheet
import com.auralis.music.ui.screens.StatsScreen
import com.auralis.music.ui.viewmodel.ListenTogetherUiState
import com.auralis.music.ui.viewmodel.StatsViewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OptionalPageGlassTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val accent=Color(0xFFB4D18A)

    @Test fun glassPlaylistListScrollsBothWaysAndRowsRemainClickable() {
        val playlists=List(80) { Playlist("list-$it","Playlist $it") }
        val shown=mutableStateOf(true)
        val visuals=com.auralis.music.ui.player.PlayerModalVisualState()
        var added=""
        compose.setContent {
            val backdrop=rememberLayerBackdrop()
            val glass=remember(backdrop) { LiquidGlassContext(backdrop,true,mutableStateOf(0f)) }
            MaterialTheme(colorScheme=darkColorScheme(primary=accent)) {
                CompositionLocalProvider(LocalLiquidGlass provides glass,
                    com.auralis.music.ui.player.LocalPlayerModalVisualState provides visuals) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                        drawRect(Brush.linearGradient(listOf(Color(0xFF492F70),Color(0xFF193D47),Color(0xFF231C2D))))
                    }
                    if(shown.value) com.auralis.music.ui.components.PlaylistPickerBottomSheet(Track("t","Test track","Artist"),
                        playlists,onAddToPlaylist={added=it.id},onCreatePlaylistAndAdd={},onDismiss={shown.value=false})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Covered decorative animation must pause",visuals.animationsActive) }
        val list=compose.onNode(hasScrollToIndexAction())
        repeat(4) { list.performTouchInput { swipeUp(durationMillis=350) } }
        list.performScrollToIndex(65)
        compose.onNodeWithText("Playlist 65").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("list-65",added) }
        repeat(3) { list.performTouchInput { swipeDown(durationMillis=350) } }
        list.performScrollToIndex(0)
        compose.onNodeWithText("Playlist 0").assertIsDisplayed()
        val folder=compose.activity.getExternalFilesDir("optional-glass-checks")!!.apply { mkdirs() }
        // PixelCopy on a Compose modal root can copy the underlying Activity window.
        val bitmap=checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(folder,"playlist-soft-rows.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        compose.runOnIdle { shown.value=false }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Closing the picker restores visual animation",visuals.animationsActive) }
    }

    @Test fun playlistSheetSelectionWorksWithGlassOnAndOff() {
        val enabled=mutableStateOf(false)
        var additions=0
        compose.setContent {
            Harness(enabled.value) {
                com.auralis.music.ui.components.PlaylistPickerBottomSheet(Track("t","Test track","Artist"),
                    listOf(Playlist("p","Glass playlist")),onAddToPlaylist={additions++},onCreatePlaylistAndAdd={},onDismiss={})
            }
        }
        compose.onNodeWithText("Glass playlist").assertIsDisplayed()
        compose.runOnIdle { enabled.value=true }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("Glass playlist").assertHasClickAction().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1,additions) }
    }

    @Composable private fun Harness(enabled: Boolean, content: @Composable () -> Unit) {
        val backdrop=rememberLayerBackdrop()
        val glass=remember(backdrop) { LiquidGlassContext(backdrop,true,mutableStateOf(0f)) }
        MaterialTheme(colorScheme=darkColorScheme(primary=accent)) {
            CompositionLocalProvider(LocalLiquidGlass provides if(enabled) glass else null) { content() }
        }
    }

    private fun capture(name: String): IntArray {
        compose.waitForIdle()
        val image=compose.onNodeWithTag("page").captureToImage()
        val map=image.toPixelMap()
        val pixels=IntArray(map.width*map.height) { map[it%map.width,it/map.width].toArgb() }
        val folder=compose.activity.getExternalFilesDir("optional-glass-checks")!!.apply { mkdirs() }
        val bitmap=Bitmap.createBitmap(pixels,map.width,map.height,Bitmap.Config.ARGB_8888)
        java.io.File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        return pixels
    }

    @Test fun glassIsOptionalAndTurningItOffRestoresTheSameCardPixels() {
        val enabled=mutableStateOf(false)
        compose.setContent {
            Harness(enabled.value) {
                OptionalGlassPage(Color(0xFF11150D),accent,Modifier.size(280.dp,300.dp).background(Color(0xFF11150D)).testTag("page")) {
                    OptionalGlassCard(RoundedCornerShape(24.dp),Color(0xFF22271B),
                        androidx.compose.foundation.BorderStroke(1.dp,Color.Gray),Modifier.padding(24.dp).fillMaxSize()) {
                        Text("Listening overview",Modifier.padding(20.dp))
                    }
                }
            }
        }
        val off=capture("card-off")
        compose.runOnIdle { enabled.value=true }
        assertFalse("Glass must visibly alter the surface",off.contentEquals(capture("card-on")))
        compose.runOnIdle { enabled.value=false }
        assertArrayEquals("Turning glass off restores the original surface",off,capture("card-off-restored"))
    }

    @Test fun listenTogetherJoinHostAndRulesStillWorkWithGlass() {
        val enabled=mutableStateOf(false)
        val ui=mutableStateOf(ListenTogetherUiState(myDisplayName="Glass test"))
        var joined=""; var hosted=0
        compose.setContent {
            Harness(enabled.value) {
                ListenTogetherSheet(ui.value,null,false,emptyList(),0L,
                    onNameChange={ ui.value=ui.value.copy(myDisplayName=it) },
                    onCreateRoom={ _,_,_,_-> hosted++ },onJoinRoom={joined=it},onLeaveRoom={},
                    onHostSettingsChange={ui.value=ui.value.copy(hostSettings=it)},onDismiss={},
                    modifier=Modifier.testTag("page"))
            }
        }
        val off=capture("listen-join-off")
        compose.runOnIdle { enabled.value=true }
        assertFalse(off.contentEquals(capture("listen-join-on")))
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("abc123")
        compose.onNodeWithText(compose.activity.getString(R.string.connect_sync_now)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("ABC123",joined) }
        compose.onNodeWithText(compose.activity.getString(R.string.host_room)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.require_approval)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(ui.value.hostSettings.requireApproval) }
        compose.onNodeWithText(compose.activity.getString(R.string.create_broadcast_room)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,hosted) }
        capture("listen-host-on")
        compose.runOnIdle { enabled.value=false }
        capture("listen-host-off")
    }

    @Test fun statsCardsRenderWithGlassAndFiltersRemainInteractive() {
        val enabled=mutableStateOf(false)
        val model=StatsViewModel(FixtureStats())
        val store=ViewModelStore().apply { put("stats",model) }
        try {
            compose.setContent {
                Harness(enabled.value) {
                    StatsScreen(model,onDismiss={},onPlayTrack={_,_->},onArtistClick={},modifier=Modifier.testTag("page"))
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Glass test track").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(1200)
            val off=capture("stats-off")
            compose.runOnIdle { enabled.value=true }
            assertFalse(off.contentEquals(capture("stats-on")))
            compose.onNodeWithText("1 month").performClick()
            compose.runOnIdle { assertEquals(1,model.selectedChipIndex.value) }
        } finally { compose.runOnUiThread { store.clear() } }
    }

    private class FixtureStats: StatsRepository {
        override suspend fun logPlaybackEvent(track: Track, playTimeMs: Long) {}
        override fun observeStatsOverview(fromTimestamp: Long,toTimestamp: Long)=flowOf(StatsOverview(3_600_000L,12,5,3))
        override fun observeTopSongs(fromTimestamp: Long,toTimestamp: Long,limit: Int)=flowOf(listOf(
            SongStat(Track("fixture","Glass test track","Test artist"),12,120_000L)))
        override fun observeTopArtists(fromTimestamp: Long,toTimestamp: Long,limit: Int)=flowOf(emptyList<ArtistStat>())
        override fun observeTopAlbums(fromTimestamp: Long,toTimestamp: Long,limit: Int)=flowOf(emptyList<AlbumStat>())
        override fun observeFirstEventTimestamp()=flowOf<Long?>(System.currentTimeMillis()-7*86_400_000L)
        override suspend fun removeEstimatedListens() {}
        override suspend fun mergeChunkedListens() {}
        override suspend fun clearListeningStats() {}
    }
}
