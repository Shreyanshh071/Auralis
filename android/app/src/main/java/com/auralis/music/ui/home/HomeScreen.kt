package com.auralis.music.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.TrackOptionsMenu
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
    onArtistClick: (Artist) -> Unit = {},
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var activeMood by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F0C))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 120.dp)
        ) {
            // ================================================================
            // 1. TOP APP BAR: "Home" Title + 4 Action Icons
            // ================================================================
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Home",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 26.sp
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onOpenHistory) {
                            Icon(Icons.Default.History, contentDescription = "History", tint = Color.White.copy(alpha = 0.85f))
                        }
                        IconButton(onClick = onOpenListenTogether) {
                            Icon(Icons.Default.Groups, contentDescription = "Listen Together", tint = Color.White.copy(alpha = 0.85f))
                        }
                        IconButton(onClick = onOpenProfile) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = Color.White.copy(alpha = 0.85f))
                        }
                    }
                }
            }

            // ================================================================
            // 3. SPEED DIAL (3x3 Grid Carousel with 3 Pagination Dots)
            // ================================================================
            if (uiState.speedDialPages.isNotEmpty()) {
                item {
                    Text(
                        text = "Speed dial",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LIME_ACCENT,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )

                    val pagerState = rememberPagerState(pageCount = { uiState.speedDialPages.size.coerceAtMost(3) })

                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            pageSpacing = 16.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                        ) { pageIndex ->
                            val items = uiState.speedDialPages[pageIndex]
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
                                                            when (item.type) {
                                                                SpeedDialType.TRACK -> {
                                                                    item.track?.let { onTrackClick(it, listOf(it)) }
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
                                                            if (item.type == SpeedDialType.TRACK && item.track != null) {
                                                                selectedTrackForMenu = item.track
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
                                        .background(if (isCurrent) LIME_ACCENT else Color.White.copy(alpha = 0.3f))
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
                item {
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
                            color = LIME_ACCENT,
                            fontSize = 20.sp
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                                .clickable {
                                    if (uiState.quickPicks.isNotEmpty()) {
                                        onTrackClick(uiState.quickPicks.first(), uiState.quickPicks)
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Play all",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }

                    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                    val quickPickRowWidth = (screenWidth - 48.dp).coerceAtLeast(280.dp)

                    // 4-Row Horizontal Grid of Songs (Matching Photo 2)
                    LazyHorizontalGrid(
                        rows = GridCells.Fixed(4),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(265.dp)
                    ) {
                        items(uiState.quickPicks, key = { it.id }) { track ->
                            val isCurrent = track.id == currentTrackId
                            Row(
                                modifier = Modifier
                                    .width(quickPickRowWidth)
                                    .clip(RoundedCornerShape(10.dp))
                                    .combinedClickable(
                                        onClick = { onTrackClick(track, uiState.quickPicks) },
                                        onLongClick = { selectedTrackForMenu = track }
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
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
                                        color = if (isCurrent) LIME_ACCENT else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { selectedTrackForMenu = track }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Options",
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
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
                item {
                    Text(
                        text = "Keep listening",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LIME_ACCENT,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(keepList, key = { it.id }) { track ->
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
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
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
            // 6. TRENDING COMMUNITY PLAYLISTS
            // ================================================================
            if (uiState.communityPlaylists.isNotEmpty()) {
                item {
                    Text(
                        text = "Trending community playlists",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LIME_ACCENT,
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                    )

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(uiState.communityPlaylists, key = { it.id }) { pl ->
                            Column(
                                modifier = Modifier
                                    .width(135.dp)
                                    .clickable { onNavigateToExplore() }
                                    .padding(4.dp)
                            ) {
                                ArtworkCard(
                                    url = pl.thumbnail ?: "",
                                    modifier = Modifier.size(135.dp).clip(RoundedCornerShape(12.dp)),
                                    cornerRadius = 12.dp,
                                    contentDescription = pl.title
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = pl.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pl.author ?: "Community",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
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
            uiState.similarRecommendations.forEach { simRec ->
                if (simRec.items.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
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
                                        color = Color.White.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = simRec.seedTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = LIME_ACCENT
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(simRec.items, key = { it.id }) { track ->
                                Column(
                                    modifier = Modifier
                                        .width(115.dp)
                                        .clip(RoundedCornerShape(12.dp))
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
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.6f),
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
            // 7. DYNAMIC YOUTUBE MUSIC CAROUSEL SHELVES (FEmusic_home)
            // ================================================================
            uiState.dynamicSections.forEach { section ->
                if (section.items.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = LIME_ACCENT,
                                fontSize = 20.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                            )
                            section.subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
                                )
                            }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(section.items, key = { it.id }) { track ->
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
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = track.artist,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }
            }

        }

        // ====================================================================
        // 9. FLOATING ACTION BUTTONS (MIC & SHUFFLE, BOTTOM RIGHT)
        // ====================================================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Voice / Mic Floating Button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF383D24))
                    .clickable { onNavigateToExplore() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice Search",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Quick Shuffle Floating Button
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF4A502E))
                    .clickable {
                        if (uiState.quickPicks.isNotEmpty()) {
                            onTrackClick(uiState.quickPicks.shuffled().first(), uiState.quickPicks.shuffled())
                        } else {
                            onSurpriseMe()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Quick Shuffle",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }

    // Options Menu Bottom Sheet
    selectedTrackForMenu?.let { track ->
        val isFav = favoriteTracks.any { it.id == track.id }
        TrackOptionsMenu(
            track = track,
            isFavorite = isFav,
            userPlaylists = userPlaylists,
            onToggleFavorite = { onFavoriteToggle(track) },
            onPlayNext = { onPlayNext(track) },
            onAddToQueue = { onAddToQueue(track) },
            onStartRadio = { onStartRadio(track) },
            onGoToArtist = {
                onArtistClick(Artist(id = "", name = track.artist))
            },
            onAddToPlaylist = { playlist -> onAddToPlaylist(playlist.id, track) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAdd(title, track) },
            isInListenTogetherRoom = isInListenTogetherRoom,
            onRecommendToRoom = onRecommendToRoom,
            onDismiss = { selectedTrackForMenu = null }
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

    // 9th Tile: 5-Dot Dice Pattern ("Surprise Me")
    if (item.type == SpeedDialType.SURPRISE || item.type == SpeedDialType.MORE) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF383D24))
                .clickable(onClick = onClick)
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            DiceFivePattern()
        }
        return
    }

    // Artist Tile (Full Rounded Card + Name + Right Chevron)
    if (item.type == SpeedDialType.ARTIST) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1B1D16))
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
            .background(Color(0xFF1B1D16))
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
    }
}

/**
 * Renders the 5-dot dice pattern for the 9th Speed Dial tile.
 */
@Composable
private fun DiceFivePattern() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            DiceDot()
            DiceDot()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            DiceDot()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            DiceDot()
            DiceDot()
        }
    }
}

@Composable
private fun DiceDot() {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFFDCE775))
    )
}
