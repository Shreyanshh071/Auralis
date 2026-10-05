package com.auralis.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.viewmodel.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/** Use the same snapping and neighboring artwork as the classic player. */
@Composable
internal fun AmbientArtworkPager(
    uiState: PlayerUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tracks = uiState.queue.ifEmpty { listOfNotNull(uiState.currentTrack) }
    val activeIndex = tracks.indexOfFirst { it.id == uiState.currentTrack?.id }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = activeIndex) { tracks.size.coerceAtLeast(1) }
    val currentIndex by rememberUpdatedState(activeIndex)
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    var userDrag by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) userDrag = true
            if (it is DragInteraction.Cancel) userDrag = false
        }
    }
    LaunchedEffect(uiState.currentTrack?.id, tracks.size) {
        if (!pager.isScrollInProgress && pager.currentPage != activeIndex) {
            pager.animateScrollToPage(activeIndex)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress to pager.settledPage }
            .distinctUntilChanged().collect { (scrolling, page) ->
                if (!scrolling && userDrag) {
                    userDrag = false
                    if (page > currentIndex) next()
                    else if (page < currentIndex) previous()
                    // Restore the actual page if skipping was blocked by room controls.
                    delay(500)
                    if (!pager.isScrollInProgress && !userDrag && pager.currentPage != currentIndex) {
                        pager.animateScrollToPage(currentIndex)
                    }
                }
            }
    }
    val context = LocalContext.current
    HorizontalPager(
        state = pager,
        key = { page -> "${tracks.getOrNull(page)?.id.orEmpty()}_$page" },
        beyondViewportPageCount = 1,
        userScrollEnabled = tracks.size > 1,
        flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = 0.35f),
        modifier = modifier.clip(RoundedCornerShape(18.dp))
    ) { page ->
        val track = tracks.getOrNull(page)
        val request = remember(context, track?.thumbnail) {
            ImageRequest.Builder(context).data(track?.thumbnail?.let { getHighResArtworkUrl(it) })
                .size(600, 600).allowHardware(true).crossfade(true).build()
        }
        AsyncImage(
            model = request,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val offset = abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                scaleX = 1f - offset * 0.15f
                scaleY = scaleX
                alpha = 1f - offset * 0.70f
            }.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.35f))
        )
    }
}
