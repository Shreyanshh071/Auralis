package com.auralis.music.ui.screens.wrapped

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.launch

private enum class WrappedPage {
    Intro, MinutesTease, Minutes,
    TotalSongs, TopSong, TopSongs,
    TotalAlbums, TopAlbum, TopAlbums,
    TotalArtists, TopArtist, TopArtists,
    Playlist, Conclusion
}

/**
 * Full-screen, swipe-up story of the current year's listening, opened from Stats.
 * [onSavePlaylist] gets the playlist title, its tracks and a cover URL, and calls back once saved.
 */
@Composable
fun WrappedScreen(
    state: WrappedState,
    onClose: () -> Unit,
    onSavePlaylist: (title: String, tracks: List<Track>, coverUrl: String?, onSaved: () -> Unit) -> Unit
) {
    BackHandler(onBack = onClose)

    // Immersive while open, like a story viewer.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val pages = remember { WrappedPage.entries }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val message = remember(state.isLoading, state.totalMinutes) { WrappedMessages.pick(state.totalMinutes) }
    var saveState by remember { mutableStateOf(PlaylistSaveState.Idle) }

    fun goTo(page: WrappedPage) {
        scope.launch { pagerState.animateScrollToPage(pages.indexOf(page)) }
    }

    WrappedBackground {
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            !state.hasData -> WrappedEmptyPage(state.year, onClose)
            else -> VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                val page = pages[index]
                val visible = pagerState.currentPage == index
                when (page) {
                    WrappedPage.Intro -> WrappedIntroPage(visible) { goTo(WrappedPage.MinutesTease) }
                    WrappedPage.MinutesTease -> WrappedMinutesTeasePage(message, visible) {
                        if (pagerState.currentPage == index) goTo(WrappedPage.Minutes)
                    }
                    WrappedPage.Minutes -> WrappedMinutesPage(message, state.totalMinutes, visible)

                    WrappedPage.TotalSongs -> WrappedCountPage(
                        str(R.string.you_ve_listened_to), state.uniqueSongCount, str(R.string.unique_songs),
                        listOf(FloatingShape.Line), visible
                    )
                    WrappedPage.TopSong -> {
                        val top = state.topSongs.firstOrNull()
                        WrappedHeroPage(
                            heading = str(R.string.your_most_played_song_is),
                            headingIsDisplay = false,
                            imageUrl = top?.track?.thumbnail,
                            imageShape = RoundedCornerShape(4.dp),
                            name = top?.track?.title,
                            caption = minutesCaption(str(R.string.you_ve_listened_for), top?.timeListenedMs ?: 0),
                            isVisible = visible
                        ) { Box(Modifier.fillMaxSize()) { CornerOutlines(visible) } }
                    }
                    WrappedPage.TopSongs -> WrappedTopFivePage(
                        heading = str(R.string.your_top_songs_of_the_year),
                        rows = state.topSongs.take(5).map { RankedRow(it.track.title, it.track.artist, it.track.thumbnail) },
                        imageShape = RoundedCornerShape(4.dp),
                        isVisible = visible
                    ) { FloatingShapes(count = 25, shapes = listOf(FloatingShape.Square)) }

                    WrappedPage.TotalAlbums -> WrappedCountPage(
                        str(R.string.you_ve_listened_to), state.uniqueAlbumCount, str(R.string.unique_albums),
                        listOf(FloatingShape.Circle), visible
                    )
                    WrappedPage.TopAlbum -> {
                        val top = state.topAlbums.firstOrNull()
                        WrappedHeroPage(
                            heading = str(R.string.your_top_album_is),
                            headingIsDisplay = true,
                            imageUrl = top?.thumbnailUrl,
                            imageShape = RoundedCornerShape(4.dp),
                            name = top?.title,
                            caption = minutesCaption(str(R.string.you_ve_listened_to_this_album_for), top?.timeListenedMs ?: 0),
                            isVisible = visible
                        ) { FloatingShapes(shapes = listOf(FloatingShape.Square)) }
                    }
                    WrappedPage.TopAlbums -> WrappedTopFivePage(
                        heading = str(R.string.your_top_5_albums),
                        rows = state.topAlbums.map { RankedRow(it.title, it.artist, it.thumbnailUrl) },
                        imageShape = RoundedCornerShape(4.dp),
                        isVisible = visible
                    ) { FloatingShapes(shapes = listOf(FloatingShape.Circle)) }

                    WrappedPage.TotalArtists -> WrappedCountPage(
                        str(R.string.you_listened_to), state.uniqueArtistCount, str(R.string.unique_artists),
                        listOf(FloatingShape.Circle, FloatingShape.Square), visible
                    )
                    WrappedPage.TopArtist -> {
                        val top = state.topArtists.firstOrNull()
                        WrappedHeroPage(
                            heading = str(R.string.your_top_artist_of_the_year_is),
                            headingIsDisplay = false,
                            imageUrl = top?.thumbnailUrl,
                            imageShape = CircleShape,
                            name = top?.name,
                            caption = minutesCaption(str(R.string.you_ve_listened_to_them_for), top?.timeListenedMs ?: 0),
                            isVisible = visible
                        ) { FloatingShapes(shapes = listOf(FloatingShape.Circle)) }
                    }
                    WrappedPage.TopArtists -> WrappedTopFivePage(
                        heading = str(R.string.your_top_artists_of_the_year),
                        rows = state.topArtists.map { RankedRow(it.name, minutesCaption("", it.timeListenedMs).trim(), it.thumbnailUrl) },
                        imageShape = CircleShape,
                        isVisible = visible
                    ) { FloatingShapes(shapes = listOf(FloatingShape.Line)) }

                    WrappedPage.Playlist -> WrappedPlaylistPage(
                        year = state.year,
                        coverUrls = state.topSongs.take(4).map { it.track.thumbnail },
                        saveState = saveState,
                        isVisible = visible
                    ) {
                        saveState = PlaylistSaveState.Saving
                        onSavePlaylist(
                            str(R.string.auralis_wrapped_x, state.year),
                            state.topSongs.map { it.track },
                            state.topSongs.firstOrNull()?.track?.thumbnail
                        ) { saveState = PlaylistSaveState.Saved }
                    }
                    WrappedPage.Conclusion -> WrappedConclusionPage(visible, onClose)
                }
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.close), tint = Color.White)
        }
    }
}
