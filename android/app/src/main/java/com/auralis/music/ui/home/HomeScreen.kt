package com.auralis.music.ui.home

import com.auralis.music.ui.theme.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.auralis.music.R
import com.auralis.music.ui.components.rememberShimmerBrush
import com.auralis.music.ui.components.tactileBounce
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SystemUpdate
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.SpeedDialIdHelper
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.SwipeableTrackContainer
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.viewmodel.HomeUiState
import com.auralis.music.ui.viewmodel.SpeedDialItem
import com.auralis.music.ui.viewmodel.SpeedDialType

val MOOD_FILTER_PILLS = listOf(
    "Podcasts", "Romance", "Feel good", "Workout", "Relax", "Energize", "Focus", "Party", "Lo-Fi", "Rock"
)

val LIME_ACCENT = Color(0xFFD4E157)
val OLIVE_CARD_BG = Color(0xFF4A502E)

/**
 * Enhanced Jetpack Compose Home Screen incorporating the complete Metrolist 2-Phase Recommendation Engine:
 * - Top App Bar (Title + Utility Action Icons)
 * - Interactive Mood & YouTube Music Chips
 * - 3x3 Speed Dial Carousel with 3-dot pagination & 9th "Surprise Me" tile
 * - Daily Discover ("Because you loved [Seed]")
 * - Forgotten Favorites ("Rediscover what you used to love")
 * - Quick Picks with "Play all"
 * - Keep Listening / Heavy Rotation (last 2 weeks)
 * - Similar to [Artist/Song] Shelves
 * - Dynamic YouTube Music Carousel Shelves
 * - Trending Community Playlists
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    currentTrack: Track? = null,
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    favoriteTracks: List<Track> = emptyList(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onStartRadio: (Track) -> Unit = {},
    onOpenListenTogether: () -> Unit = {},
    onNavigateToExplore: () -> Unit = {},
    onMoodSelect: (String?) -> Unit = {},
    onChipToggle: (HomeChip?) -> Unit = {},
    onSurpriseMe: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onArtistClick: (Artist) -> Unit = {},
    onAlbumClick: (PlaylistResult) -> Unit = {},
    onUnpinSpeedDial: ((String) -> Unit)? = null,
    savedAlbums: List<com.auralis.music.domain.model.SavedAlbum> = emptyList(),
    isAlbumPinned: ((String) -> Boolean)? = null,
    onPinAlbumToSpeedDial: ((PlaylistResult) -> Unit)? = null,
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    onToggleSaveAlbum: ((com.auralis.music.domain.model.SavedAlbum) -> Unit)? = null,
    onShuffleAlbum: ((PlaylistResult) -> Unit)? = null,
    onPlayNextAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddToQueueAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddAlbumToPlaylist: ((String, PlaylistResult) -> Unit)? = null,
    onCreatePlaylistAndAddAlbum: ((String, PlaylistResult) -> Unit)? = null,
    onDownloadAlbum: ((PlaylistResult) -> Unit)? = null,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var selectedAlbumForMenu by remember { mutableStateOf<PlaylistResult?>(null) }
    var activeMood by remember { mutableStateOf<String?>(null) }
    // Observe dynamic theme tokens at root of HomeScreen so dynamic theme transitions
    // immediately recompose the screen and visible elements without requiring scroll.
    val themePrimary = MaterialTheme.dynamicPrimary
    val themeBackground = MaterialTheme.dynamicBackground
    val themeOnBackground = MaterialTheme.dynamicOnBackground

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBackground)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides themeOnBackground
        ) {
            val bottomPad = if (currentTrack != null) 240.dp else 140.dp
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 0.dp, bottom = bottomPad)
            ) {
                // ================================================================
                // 1. TOP APP BAR: "Home" Title + Action Icons
                // ================================================================
                item(key = "home_top_bar", contentType = "header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(start = 16.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_auralis_header_logo),
                                contentDescription = "Auralis Logo",
                                colorFilter = ColorFilter.tint(themeOnBackground),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "Home",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = themeOnBackground,
                                fontSize = 26.sp
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onOpenStats,
                                modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                            ) {
                                Icon(
                                    Icons.Default.Equalizer,
                                    contentDescription = "Stats",
                                    tint = themeOnBackground.copy(alpha = 0.85f)
                                )
                            }
                            IconButton(
                                onClick = onOpenHistory,
                                modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                            ) {
                                Icon(Icons.Default.History, contentDescription = "History", tint = themeOnBackground.copy(alpha = 0.85f))
                            }
                            IconButton(
                                onClick = onOpenListenTogether,
                                modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                            ) {
                                Icon(Icons.Default.Groups, contentDescription = "Listen Together", tint = themeOnBackground.copy(alpha = 0.85f))
                            }
                            IconButton(
                                onClick = onOpenProfile,
                                modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                            ) {
                                Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = themeOnBackground.copy(alpha = 0.85f))
                            }
                        }
                    }
                }

            // ── SKELETON GHOST TILES ON INITIAL LOAD ──
            if (uiState.isLoading && uiState.speedDialPages.isEmpty()) {
                item(key = "home_skeleton", contentType = "skeleton") {
                    HomeGhostTilesSkeleton(
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                // ================================================================
                // 3. SPEED DIAL (3x3 Grid Carousel with 3 Pagination Dots)
                // ================================================================
                if (uiState.speedDialPages.isNotEmpty()) {
                    item(key = "home_speed_dial", contentType = "speed_dial") {
                        Text(
                            text = "Speed dial",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = themePrimary,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                        )

                        val pagerState = rememberPagerState(pageCount = { uiState.speedDialPages.size.coerceAtMost(3) })

                        Column(modifier = Modifier.fillMaxWidth()) {
                            HorizontalPager(
                                state = pagerState,
                                key = { pageIndex ->
                                    SpeedDialIdHelper.computePageContentKey(
                                        pageIndex,
                                        uiState.speedDialPages.getOrNull(pageIndex)
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                pageSpacing = 16.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                            ) { pageIndex ->
                                val items = uiState.speedDialPages.getOrNull(pageIndex) ?: emptyList()
                                key(SpeedDialIdHelper.computePageContentKey(pageIndex, items)) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                for (row in 0 until 3) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        for (col in 0 until 3) {
                                            val itemIndex = row * 3 + col
                                            val item = items.getOrNull(itemIndex)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                            ) {
                                                if (item != null && item.type != SpeedDialType.PLACEHOLDER) {
                                                    SpeedDialTile(
                                                        item = item,
                                                        modifier = Modifier.fillMaxSize(),
                                                        onClick = {
                                                            android.util.Log.d("AuralisPlayback", "[SpeedDial Tap] item='${item.name}' (${item.id}, type=${item.type})")
                                                            when (item.type) {
                                                                SpeedDialType.TRACK -> {
                                                                    val canonicalId = SpeedDialIdHelper.getCanonicalTrackId(item.id) ?: item.id
                                                                    val trk = item.track ?: com.auralis.music.domain.model.Track(
                                                                        id = canonicalId,
                                                                        title = item.name,
                                                                        artist = item.artistQuery ?: "",
                                                                        thumbnail = item.image ?: ""
                                                                    )
                                                                    val speedDialTracks = items.filter { it.type == SpeedDialType.TRACK }.map { dialItem ->
                                                                        dialItem.track ?: com.auralis.music.domain.model.Track(
                                                                            id = SpeedDialIdHelper.getCanonicalTrackId(dialItem.id) ?: dialItem.id,
                                                                            title = dialItem.name,
                                                                            artist = dialItem.artistQuery ?: "",
                                                                            thumbnail = dialItem.image ?: ""
                                                                        )
                                                                    }.ifEmpty { listOf(trk) }
                                                                    val queueToPlay = if (speedDialTracks.any { it.id == trk.id }) speedDialTracks else listOf(trk) + speedDialTracks
                                                                    onTrackClick(trk, queueToPlay)
                                                                }
                                                                SpeedDialType.ALBUM -> {
                                                                    val alb = item.album ?: com.auralis.music.domain.model.PlaylistResult(
                                                                        id = item.id.removePrefix("album-"),
                                                                        title = item.name,
                                                                        author = item.artistQuery,
                                                                        thumbnail = item.image ?: ""
                                                                    )
                                                                    onAlbumClick(alb)
                                                                }
                                                                SpeedDialType.ARTIST -> {
                                                                    onArtistClick(
                                                                        Artist(
                                                                            id = if (item.id.startsWith("UC")) item.id else "",
                                                                            name = item.name,
                                                                            thumbnail = item.image
                                                                        )
                                                                    )
                                                                }
                                                                SpeedDialType.SURPRISE -> {
                                                                    onSurpriseMe()
                                                                }
                                                                SpeedDialType.MORE -> {
                                                                    onNavigateToExplore()
                                                                }
                                                                else -> {}
                                                            }
                                                        },
                                                        onLongClick = {
                                                            when (item.type) {
                                                                SpeedDialType.ALBUM -> {
                                                                    selectedAlbumForMenu = item.album ?: PlaylistResult(
                                                                        id = item.id.removePrefix("album-"),
                                                                        title = item.name,
                                                                        author = null,
                                                                        thumbnail = item.image ?: ""
                                                                    )
                                                                }
                                                                SpeedDialType.TRACK -> {
                                                                    item.track?.let { trk ->
                                                                        selectedTrackForMenu = trk
                                                                    }
                                                                }
                                                                else -> {
                                                                    if (item.isPinned) {
                                                                        onUnpinSpeedDial?.invoke(item.id)
                                                                        Toast.makeText(context, "Unpinned \"${item.name}\"", Toast.LENGTH_SHORT).show()
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 3 Pagination Dots
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(uiState.speedDialPages.size.coerceAtMost(3)) { idx ->
                                val isCurrent = pagerState.currentPage == idx
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(if (isCurrent) 7.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(if (isCurrent) themePrimary else themeOnBackground.copy(alpha = 0.25f))
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }

            // ================================================================
            // 4. QUICK PICKS (Directly below Speed Dial - 4 Rows per column with "Play all")
            // ================================================================
            if (uiState.quickPicks.isNotEmpty()) {
                item(key = "home_quick_picks", contentType = "quick_picks") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Quick picks",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = themePrimary,
                            fontSize = 20.sp
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, themeOnBackground.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                                .clickable {
                                    if (uiState.quickPicks.isNotEmpty()) {
                                        val shown = uiState.quickPicks.take(16)
                                        onTrackClick(shown.first(), shown)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Play all",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = themeOnBackground
                            )
                        }
                    }

                    // Exactly 4 pages of 4 (16 songs), even when more picks were fetched.
                    val quickPickPages = remember(uiState.quickPicks) {
                        uiState.quickPicks.take(16).chunked(4)
                    }
                    val quickPicksPagerState = rememberPagerState { quickPickPages.size }

                    // 4-Row Snapping Pager of Songs (Eliminates half-scrolled stray 3-dots)
                    HorizontalPager(
                        state = quickPicksPagerState,
                        key = { pageIndex -> pageIndex },
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        pageSpacing = 16.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                    ) { pageIndex ->
                        val pageTracks = quickPickPages[pageIndex]
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            pageTracks.forEach { track ->
                                val isCurrent = track.id == currentTrackId
                                // No row swipe here: with "swipe left/right to queue/play next" on,
                                // the rows swallowed every sideways swipe, so the 4 pages could
                                // never be reached. Play next / Add to queue stay in the long-press menu.
                                SwipeableTrackContainer(
                                    onPlayNext = null,
                                    onAddToQueue = null
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .combinedClickable(
                                                onClick = { onTrackClick(track, uiState.quickPicks.take(16)) },
                                                onLongClick = { selectedTrackForMenu = track }
                                            )
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ArtworkCard(
                                            sizeToConstraints = true,
                                            url = track.thumbnail,
                                            modifier = Modifier.size(48.dp),
                                            cornerRadius = 8.dp,
                                            contentDescription = track.title
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = track.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isCurrent) themePrimary else themeOnBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = track.artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = themeOnBackground.copy(alpha = 0.65f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = { selectedTrackForMenu = track }) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Options",
                                                tint = themeOnBackground.copy(alpha = 0.65f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (quickPickPages.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        // Page dots, same style as Speed dial, so the swipeable pages are discoverable.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            repeat(quickPickPages.size) { idx ->
                                val isCurrentPage = quickPicksPagerState.currentPage == idx
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(if (isCurrentPage) 7.dp else 5.dp)
                                        .clip(CircleShape)
                                        .background(if (isCurrentPage) themePrimary else themeOnBackground.copy(alpha = 0.25f))
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }

            // ================================================================
            // 5. KEEP LISTENING SECTION (Directly below Quick Picks)
            // ================================================================
            val keepList = if (uiState.keepListening.isNotEmpty()) uiState.keepListening else uiState.recentTracks.map { it.track }
            if (keepList.isNotEmpty()) {
                item(key = "home_keep_listening", contentType = "keep_listening") {
                    Text(
                        text = "Keep listening",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = themePrimary,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(
                            items = keepList,
                            key = { it.id },
                            contentType = { "track" }
                        ) { track ->
                            Column(
                                modifier = Modifier
                                    .width(115.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        onClick = { onTrackClick(track, listOf(track)) },
                                        onLongClick = { selectedTrackForMenu = track }
                                    )
                                    .padding(4.dp)
                            ) {
                                ArtworkCard(
                                    url = track.thumbnail,
                                    modifier = Modifier
                                        .size(115.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    cornerRadius = 14.dp,
                                    contentDescription = track.title
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = themeOnBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = themeOnBackground.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }

            // ================================================================
            // 7. SIMILAR RECOMMENDATION SHELVES ("Similar to...")
            // ================================================================
            uiState.similarRecommendations.forEachIndexed { idx, simRec ->
                if (simRec.items.isNotEmpty()) {
                    item(key = "sim_rec_${simRec.seedTitle}_${simRec.artistId ?: ""}_$idx", contentType = "similar_shelf") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val targetArtistName = simRec.artistName ?: simRec.seedTitle
                                    val targetArtist = Artist(
                                        id = simRec.artistId ?: targetArtistName,
                                        name = targetArtistName,
                                        thumbnail = simRec.seedThumbnail,
                                        query = targetArtistName
                                    )
                                    onArtistClick(targetArtist)
                                }
                                .padding(horizontal = 18.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (simRec.seedThumbnail != null) {
                                    ArtworkCard(
                                        url = simRec.seedThumbnail,
                                        modifier = Modifier.size(28.dp).clip(CircleShape),
                                        cornerRadius = 14.dp,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Column {
                                    Text(
                                        text = "Similar to",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = themeOnBackground.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = simRec.seedTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = themePrimary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "View Artist Profile",
                                tint = themeOnBackground.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(
                                items = simRec.items,
                                key = { it.id },
                                contentType = { "track" }
                            ) { track ->
                                Column(
                                    modifier = Modifier
                                        .width(115.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        // Long-press opens the song's ⋮ menu, like every other Home shelf.
                                        .combinedClickable(
                                            onClick = { onTrackClick(track, simRec.items) },
                                            onLongClick = { selectedTrackForMenu = track }
                                        )
                                        .padding(4.dp)
                                ) {
                                    ArtworkCard(
                                        url = track.thumbnail,
                                        modifier = Modifier.size(115.dp).clip(RoundedCornerShape(12.dp)),
                                        cornerRadius = 12.dp,
                                        contentDescription = track.title
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = themeOnBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = themeOnBackground.copy(alpha = 0.65f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                    }
                }
            }

            // ================================================================
            // 8. DYNAMIC YOUTUBE MUSIC CAROUSEL SHELVES (FEmusic_home)
            // ================================================================
            uiState.dynamicSections.forEachIndexed { sIdx, section ->
                if (section.items.isNotEmpty() || section.albums.isNotEmpty()) {
                    item(key = "dyn_section_${section.title}_$sIdx", contentType = "dynamic_shelf") {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = themePrimary,
                                fontSize = 20.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                            )
                            section.subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = themeOnBackground.copy(alpha = 0.65f),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                                )
                            }

                            if (section.items.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(
                                        items = section.items,
                                        key = { it.id },
                                        contentType = { "track" }
                                    ) { track ->
                                        Column(
                                            modifier = Modifier
                                                .width(120.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .combinedClickable(
                                                    onClick = { onTrackClick(track, section.items) },
                                                    onLongClick = { selectedTrackForMenu = track }
                                                )
                                                .padding(4.dp)
                                        ) {
                                            ArtworkCard(
                                                url = track.thumbnail,
                                                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)),
                                                cornerRadius = 12.dp,
                                                contentDescription = track.title
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = track.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = themeOnBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = track.artist,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = themeOnBackground.copy(alpha = 0.65f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            if (section.albums.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    items(
                                        items = section.albums,
                                        key = { it.id },
                                        contentType = { "album" }
                                    ) { album ->
                                        Column(
                                            modifier = Modifier
                                                .width(120.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { onAlbumClick(album) }
                                                .padding(4.dp)
                                        ) {
                                            ArtworkCard(
                                                url = album.thumbnail ?: "",
                                                modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)),
                                                cornerRadius = 12.dp,
                                                contentDescription = album.title
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = album.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = themeOnBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = album.author ?: "Album",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = themeOnBackground.copy(alpha = 0.65f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                    }
                }
            }
        }
    }
}

    // Options Menu Bottom Sheet
    selectedTrackForMenu?.let { track ->
        val isFav = favoriteTracks.any { it.id == track.id }
        val isPinned = isTrackPinned?.invoke(track.id) ?: uiState.pinnedSpeedDialIds.contains(track.id)
        TrackOptionsMenu(
            track = track,
            isFavorite = isFav,
            userPlaylists = userPlaylists,
            onToggleFavorite = { onFavoriteToggle(track) },
            onPlayNext = { onPlayNext(track) },
            onAddToQueue = { onAddToQueue(track) },
            onStartRadio = { onStartRadio(track) },
            isPinned = isPinned,
            onPinToSpeedDial = { onPinTrackToSpeedDial?.invoke(track) },
            onGoToArtist = {
                onArtistClick(Artist(id = "", name = track.artist))
            },
            onGoToAlbum = { albumId, albumTitle, albumArtist, albumArt ->
                val cached = com.auralis.music.data.network.AlbumMetadataResolver.getCached(track.title, track.artist)
                onAlbumClick(
                    PlaylistResult(
                        id = albumId ?: cached?.albumId ?: "album-${track.id}",
                        title = albumTitle,
                        author = albumArtist ?: cached?.artistName ?: track.artist,
                        thumbnail = albumArt ?: cached?.albumArt ?: track.thumbnail
                    )
                )
            },
            onAddToPlaylist = { playlist -> onAddToPlaylist(playlist.id, track) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAdd(title, track) },
            isInListenTogetherRoom = isInListenTogetherRoom,
            onRecommendToRoom = onRecommendToRoom,
            onDismiss = { selectedTrackForMenu = null }
        )
    }

    // Album Options Menu Bottom Sheet
    selectedAlbumForMenu?.let { album ->
        val isSaved = savedAlbums.any { it.id == album.id || it.title.equals(album.title, ignoreCase = true) }
        val cleanAlbumId = album.id.removePrefix("album-").removePrefix("VL")
        val isPinned = uiState.pinnedSpeedDialIds.any {
            val id = it.removePrefix("album-").removePrefix("VL")
            id == cleanAlbumId
        } || (isAlbumPinned?.invoke(album.id) == true)
        com.auralis.music.ui.components.AlbumOptionsMenu(
            album = album,
            isSingleOrEp = false,
            isFavorite = isSaved,
            userPlaylists = userPlaylists,
            isPinned = isPinned,
            onToggleFavorite = {
                val savedAlbum = com.auralis.music.domain.model.SavedAlbum(
                    id = album.id,
                    title = album.title,
                    artist = album.author ?: "Various Artists",
                    thumbnail = album.thumbnail
                )
                onToggleSaveAlbum?.invoke(savedAlbum)
            },
            onShuffle = { onShuffleAlbum?.invoke(album) },
            onShare = {
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, album.title)
                    putExtra(
                        android.content.Intent.EXTRA_TEXT,
                        "Check out the album '${album.title}' by ${album.author ?: "Various Artists"} on Auralis Music!\nhttps://music.youtube.com/playlist?list=${album.id.removePrefix("VL")}\n\nDownload Auralis App: https://auralis-self-nu.vercel.app/"
                    )
                }
                context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Album"))
            },
            onPlayNext = { onPlayNextAlbum?.invoke(album) },
            onAddToQueue = { onAddToQueueAlbum?.invoke(album) },
            onAddToPlaylist = { playlist -> onAddAlbumToPlaylist?.invoke(playlist.id, album) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAddAlbum?.invoke(title, album) },
            onPinToSpeedDial = { onPinAlbumToSpeedDial?.invoke(album) },
            onDownload = { onDownloadAlbum?.invoke(album) },
            onViewArtist = {
                album.author?.let { onArtistClick(Artist(id = "", name = it)) }
            },
            onDismiss = { selectedAlbumForMenu = null }
        )
    }
}

// ============================================================================
// 🔲 SPEED DIAL TILE (Artist Circle, Track Square, 5-Dice Surprise Tile)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialTile(
    item: SpeedDialItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    if (item.type == SpeedDialType.PLACEHOLDER) {
        Box(modifier = modifier)
        return
    }

    // 9th Tile: 3-Dot Diagonal Dice Pattern with Dynamic Glow Background ("Surprise Me")
    if (item.type == SpeedDialType.SURPRISE || item.type == SpeedDialType.MORE) {
        val primary = MaterialTheme.dynamicPrimary
        val secondary = MaterialTheme.dynamicSecondary
        val tertiary = MaterialTheme.dynamicTertiary
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant

        val surface = MaterialTheme.colorScheme.surface

        // Multi-tone dynamic gradient with corner glows matching reference photo
        val gradientBrush = Brush.linearGradient(
            colors = listOf(
                primary.copy(alpha = 0.38f),
                surface,
                tertiary.copy(alpha = 0.30f)
            ),
            start = Offset(0f, 0f),
            end = Offset(300f, 300f)
        )

        val dotColor = primary.copy(alpha = 0.88f)

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(surface)
                .background(gradientBrush)
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                primary.copy(alpha = 0.45f),
                                outlineVariant.copy(alpha = 0.20f),
                                tertiary.copy(alpha = 0.40f)
                            )
                        )
                    ),
                    RoundedCornerShape(14.dp)
                )
                .clickable(onClick = onClick)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            DiceThreePattern(dotColor = dotColor)
        }
        return
    }

    // Artist Tile (Full Rounded Card + Name + Right Chevron)
    if (item.type == SpeedDialType.ARTIST) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
        ) {
            if (!item.image.isNullOrBlank()) {
                ArtworkCard(
                    url = item.image,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 14.dp,
                    contentDescription = item.name
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                startY = 50f
                            )
                        )
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        return
    }

    // Track Tile (Full Square Album Cover + Title Overlay)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)), RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        if (!item.image.isNullOrBlank()) {
            ArtworkCard(
                url = item.image,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 14.dp,
                contentDescription = item.name
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.40f),
                                Color.Black.copy(alpha = 0.90f)
                            ),
                            startY = 40f
                        )
                    )
            )
        }

        Text(
            text = item.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )

        if (item.isPinned) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}

/**
 * Renders the 3-dot diagonal dice pattern for the Surprise Me tile.
 */
@Composable
private fun DiceThreePattern(dotColor: Color) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            DiceDot(color = dotColor)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            DiceDot(color = dotColor)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            DiceDot(color = dotColor)
        }
    }
}

@Composable
private fun DiceDot(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Shimmer Ghost Tiles Skeleton Loader:
 * Displays sleek placeholder tiles during cold start before feed data resolves,
 * preventing sudden layout jumps or blank grey boxes.
 */
@Composable
fun HomeGhostTilesSkeleton(modifier: Modifier = Modifier) {
    val shimmerBrush = rememberShimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // 1. Shimmer Speed Dial Section (3x3 Grid)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmerBrush)
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in 0 until 3) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (col in 0 until 3) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(shimmerBrush)
                            )
                        }
                    }
                }
            }
        }

        // 2. Shimmer Shelf Section ("Similar to...")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .width(150.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerBrush)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) {
                    Column(
                        modifier = Modifier.width(115.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(115.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(shimmerBrush)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush)
                        )
                    }
                }
            }
        }
    }
}
