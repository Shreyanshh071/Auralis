package com.auralis.music.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
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
    onArtistClick: (Artist) -> Unit = {},
    onAlbumClick: (PlaylistResult) -> Unit = {},
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var activeMood by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val bottomPad = if (currentTrack != null) 180.dp else 100.dp
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = bottomPad)
        ) {
            // ================================================================
            // 1. TOP APP BAR: "Home" Title + 4 Action Icons
            // ================================================================
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Home",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 26.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onOpenHistory,
                            modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                        ) {
                            Icon(Icons.Default.History, contentDescription = "History", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                        }
                        IconButton(
                            onClick = onOpenListenTogether,
                            modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = "Listen Together", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                        }
                        IconButton(
                            onClick = onOpenProfile,
                            modifier = Modifier.tactileBounce(scaleDown = 0.90f)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Profile", tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
                        }
                    }
                }
            }

            // ── SKELETON GHOST TILES ON INITIAL LOAD ──
            if (uiState.isLoading && uiState.speedDialPages.isEmpty()) {
                item {
                    HomeGhostTilesSkeleton(
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                // ================================================================
                // 3. SPEED DIAL (3x3 Grid Carousel with 3 Pagination Dots)
                // ================================================================
                if (uiState.speedDialPages.isNotEmpty()) {
                    item {
                        Text(
                            text = "Speed dial",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
                        )

                        val pagerState = rememberPagerState(pageCount = { uiState.speedDialPages.size.coerceAtMost(3) })

                    val speedDialThemeKey = MaterialTheme.colorScheme.background.hashCode() xor MaterialTheme.colorScheme.primary.hashCode()

                    Column(modifier = Modifier.fillMaxWidth()) {
                        HorizontalPager(
                            state = pagerState,
                            key = { pageIndex -> "$pageIndex-$speedDialThemeKey" },
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
                                        .background(if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))
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
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 20.sp
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
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
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    val quickPickPages = remember(uiState.quickPicks) {
                        uiState.quickPicks.chunked(4)
                    }
                    val quickPicksPagerState = rememberPagerState { quickPickPages.size }
                    val quickPicksThemeKey = MaterialTheme.colorScheme.background.hashCode() xor MaterialTheme.colorScheme.primary.hashCode()

                    // 4-Row Snapping Pager of Songs (Eliminates half-scrolled stray 3-dots)
                    HorizontalPager(
                        state = quickPicksPagerState,
                        key = { pageIndex -> "$pageIndex-$quickPicksThemeKey" },
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
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
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = track.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { selectedTrackForMenu = track }) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Options",
                                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
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
                        color = MaterialTheme.colorScheme.primary,
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
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
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
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                    )
                                    Text(
                                        text = simRec.seedTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "View Artist Profile",
                                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
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
                                        .clickable { onTrackClick(track, simRec.items) }
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
                                        color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
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
            uiState.dynamicSections.forEach { section ->
                if (section.items.isNotEmpty() || section.albums.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 20.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                            )
                            section.subtitle?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
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
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = track.artist,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
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
                                                color = MaterialTheme.colorScheme.onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = album.author ?: "Album",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
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

    // 9th Tile: 3-Dot Diagonal Dice Pattern with Dynamic Glow Background ("Surprise Me")
    if (item.type == SpeedDialType.SURPRISE || item.type == SpeedDialType.MORE) {
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.secondary
        val tertiary = MaterialTheme.colorScheme.tertiary
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant

        // Multi-tone dynamic gradient with corner glows matching reference photo
        val gradientBrush = Brush.linearGradient(
            colors = listOf(
                primary.copy(alpha = 0.38f),
                Color(0xFF131217),
                tertiary.copy(alpha = 0.30f)
            ),
            start = Offset(0f, 0f),
            end = Offset(300f, 300f)
        )

        val dotColor = primary.copy(alpha = 0.88f)

        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF121116))
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
                .tactileBounce(scaleDown = 0.88f, onClick = onClick)
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
