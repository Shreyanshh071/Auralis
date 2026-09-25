package com.auralis.music.ui.screens

import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistStat
import com.auralis.music.domain.model.OptionStats
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SongStat
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPalette
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.viewmodel.StatsViewModel
import java.util.Locale
import com.auralis.music.ui.components.bottomChromePadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onDismiss: () -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onArtistClick: (Artist) -> Unit,
    userPlaylists: List<Playlist> = emptyList(),
    favoriteTracks: List<Track> = emptyList(),
    onFavoriteToggle: (Track) -> Unit = {},
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    hasActiveMiniPlayer: Boolean = false,
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedOption by viewModel.selectedOption.collectAsState()
    val selectedChipIndex by viewModel.selectedChipIndex.collectAsState()
    val firstEventTs by viewModel.firstEventTimestamp.collectAsState()
    val overview by viewModel.statsOverview.collectAsState()
    val topSongs by viewModel.topSongs.collectAsState()
    val topArtists by viewModel.topArtists.collectAsState()
    val topSong = topSongs.firstOrNull()
    val topArtist = topArtists.firstOrNull()

    var showOptionDropdown by remember { mutableStateOf(false) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    val isDark = MaterialTheme.dynamicSurface.luminance() < 0.5f
    val themePrimary = MaterialTheme.dynamicPrimary
    val themeBackground = MaterialTheme.dynamicBackground
    val surfaceCardColor = MaterialTheme.colorScheme.surfaceContainer
    val surfaceHighColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceHighestColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val cardBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.35f else 0.45f)
    val textPrimary = MaterialTheme.dynamicOnSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant

    val dateChips = remember(selectedOption, firstEventTs) {
        viewModel.generateDateChips(firstEventTs)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(themeBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Stats",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.tactileBounce(0.88f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = themeBackground
                ),
                modifier = Modifier.statusBarsPadding()
            )

            // Period Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dropdown Chip
                Box {
                    Surface(
                        onClick = { showOptionDropdown = true },
                        shape = CircleShape,
                        color = surfaceHighColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
                        modifier = Modifier.tactileBounce(0.92f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val optionLabel = when (selectedOption) {
                                OptionStats.CONTINUOUS -> "Continuous"
                                OptionStats.WEEKS -> "Weeks"
                                OptionStats.MONTHS -> "Months"
                                OptionStats.YEARS -> "Years"
                            }
                            Text(
                                text = optionLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                ),
                                color = textPrimary
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showOptionDropdown,
                        onDismissRequest = { showOptionDropdown = false }
                    ) {
                        OptionStats.entries.forEach { opt ->
                            DropdownMenuItem(
                                text = {
                                    val name = when (opt) {
                                        OptionStats.CONTINUOUS -> "Continuous"
                                        OptionStats.WEEKS -> "Weeks"
                                        OptionStats.MONTHS -> "Months"
                                        OptionStats.YEARS -> "Years"
                                    }
                                    Text(
                                        text = name,
                                        fontWeight = if (opt == selectedOption) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    viewModel.selectOption(opt)
                                    showOptionDropdown = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Scrollable row of period pills
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 16.dp)
                ) {
                    items(dateChips, key = { "${selectedOption.name}_${it.first}" }) { (idx, label) ->
                        val isSelected = idx == selectedChipIndex
                        val pillBg = if (isSelected) {
                            themePrimary
                        } else {
                            surfaceHighColor
                        }
                        val pillTextColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            textSecondary
                        }

                        Surface(
                            onClick = { viewModel.selectChip(idx) },
                            shape = CircleShape,
                            color = pillBg,
                            border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor) else null,
                            modifier = Modifier.tactileBounce(0.92f)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.5.sp
                                ),
                                color = pillTextColor,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }

            // Main Stats Content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = bottomChromePadding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // Section: Your Highlights (if any top artist or song)
                if (topArtist != null || topSong != null) {
                    item(key = "your_highlights") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = themePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Your Highlights",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    ),
                                    color = themePrimary
                                )
                            }

                            // Card 1: Top Artist
                            topArtist?.let { artist ->
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = surfaceCardColor,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(CircleShape)
                                                .background(surfaceHighestColor),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!artist.thumbnailUrl.isNullOrBlank() && !artist.thumbnailUrl.contains("i.ytimg.com/vi/")) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(artist.thumbnailUrl)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = artist.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Rounded.Person,
                                                    contentDescription = artist.name,
                                                    tint = textSecondary,
                                                    modifier = Modifier.size(34.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Top Artist",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = textSecondary,
                                                fontSize = 12.5.sp
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = artist.name,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.5.sp
                                                ),
                                                color = textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${artist.songsPlayedCount} songs played • ${makeDurationString(artist.timeListenedMs)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = textSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        // Star Action Button
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(surfaceHighestColor)
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = { onArtistClick(Artist(id = "", name = artist.name, thumbnail = artist.thumbnailUrl)) }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Star,
                                                contentDescription = "View Artist",
                                                tint = themePrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Card 2: Top Song
                            topSong?.let { song ->
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = surfaceCardColor,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(song.track.thumbnail)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = song.track.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(surfaceHighestColor)
                                        )

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Top Song",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = textSecondary,
                                                fontSize = 12.5.sp
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = song.track.title,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.5.sp
                                                ),
                                                color = textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${song.playCount} plays • ${makeDurationString(song.timeListenedMs)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = textSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        // Play Action Button
                                        Box(
                                            modifier = Modifier
                                                .size(42.dp)
                                                .clip(CircleShape)
                                                .background(themePrimary)
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = { onPlayTrack(song.track, listOf(song.track)) }
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PlayArrow,
                                                contentDescription = "Play Song",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section: Listening Overview
                item(key = "listening_overview") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Listening Overview",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = themePrimary
                        )

                        // Hero Card: Total Time Listened
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = surfaceCardColor,
                            border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 22.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The Auralis mark, turning slowly and steadily (one turn per 12s).
                                // Rotation is read in the draw phase, so the spin never recomposes.
                                val logoSpin = androidx.compose.animation.core.rememberInfiniteTransition(label = "statsLogoSpin")
                                val logoRotation = logoSpin.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                        animation = androidx.compose.animation.core.tween(
                                            durationMillis = 12_000,
                                            easing = androidx.compose.animation.core.LinearEasing
                                        )
                                    ),
                                    label = "statsLogoRotation"
                                )
                                // A well so the mark reads cleanly against the card: darker than the
                                // card in dark theme, lighter than it in light theme (a dark well on
                                // a light card read as a plain grey blob, not a well).
                                val isDarkTheme = MaterialTheme.dynamicPalette.isDark
                                val logoWellColor = if (isDarkTheme) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.55f)
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(CircleShape)
                                        .background(logoWellColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(com.auralis.music.R.drawable.ic_auralis_header_logo),
                                        contentDescription = "Auralis",
                                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(textPrimary),
                                        modifier = Modifier
                                            .size(60.dp)
                                            .graphicsLayer {
                                                // The mark's visual centre (alpha centroid of the three
                                                // lobes) sits 7.3% below the PNG's centre. Spinning about
                                                // the PNG centre made it wobble off-centre, so rotate
                                                // about the centroid and lift it into the circle's middle.
                                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, LOGO_VISUAL_CENTER_Y)
                                                translationY = -(LOGO_VISUAL_CENTER_Y - 0.5f) * size.height
                                                rotationZ = logoRotation.value
                                            }
                                    )
                                }

                                Spacer(modifier = Modifier.width(22.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Total Time Listened",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = textSecondary,
                                        fontSize = 13.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = makeDurationString(overview.totalPlayTimeMs),
                                        style = MaterialTheme.typography.headlineLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 36.sp,
                                            letterSpacing = (-1).sp
                                        ),
                                        color = textPrimary
                                    )
                                }
                            }
                        }

                        // Row of 3 mini metric cards
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Card 1: Songs
                            MetricMiniCard(
                                icon = Icons.Rounded.MusicNote,
                                count = overview.songsCount,
                                label = "Songs",
                                isDark = isDark,
                                cardBg = surfaceCardColor,
                                borderColor = cardBorderColor,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                iconTint = themePrimary,
                                iconBg = surfaceHighestColor,
                                modifier = Modifier.weight(1f)
                            )

                            // Card 2: Artists
                            MetricMiniCard(
                                icon = Icons.Rounded.Headphones,
                                count = overview.artistsCount,
                                label = "Artists",
                                isDark = isDark,
                                cardBg = surfaceCardColor,
                                borderColor = cardBorderColor,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                iconTint = themePrimary,
                                iconBg = surfaceHighestColor,
                                modifier = Modifier.weight(1f)
                            )

                            // Card 3: Albums
                            MetricMiniCard(
                                icon = Icons.Rounded.Album,
                                count = overview.albumsCount,
                                label = "Albums",
                                isDark = isDark,
                                cardBg = surfaceCardColor,
                                borderColor = cardBorderColor,
                                textPrimary = textPrimary,
                                textSecondary = textSecondary,
                                iconTint = themePrimary,
                                iconBg = surfaceHighestColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Section: Top Songs
                if (topSongs.isNotEmpty()) {
                    item(key = "top_songs_header") {
                        Text(
                            text = "Top Songs",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = themePrimary
                        )
                    }

                    itemsIndexed(topSongs, key = { _, s -> "song_${s.track.id}" }) { index, songStat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    onPlayTrack(songStat.track, topSongs.map { it.track })
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(songStat.track.thumbnail)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = songStat.track.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(surfaceHighestColor)
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${index + 1}. ",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        ),
                                        color = themePrimary
                                    )
                                    Text(
                                        text = songStat.track.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        ),
                                        color = textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                val timesLabel = if (songStat.playCount == 1) "1 time" else "${songStat.playCount} times"
                                Text(
                                    text = "$timesLabel • ${makeDurationString(songStat.timeListenedMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondary,
                                    fontSize = 12.sp
                                )
                            }

                            IconButton(
                                onClick = { selectedTrackForMenu = songStat.track },
                                modifier = Modifier.tactileBounce(0.88f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = textSecondary
                                )
                            }
                        }
                    }
                }

                // Section: Top Artists
                if (topArtists.isNotEmpty()) {
                    item(key = "top_artists_header") {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Top Artists",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = themePrimary
                        )
                    }

                    itemsIndexed(topArtists, key = { _, a -> "artist_${a.name}" }) { _, artistStat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    onArtistClick(Artist(id = "", name = artistStat.name, thumbnail = artistStat.thumbnailUrl))
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(surfaceHighestColor),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!artistStat.thumbnailUrl.isNullOrBlank() && !artistStat.thumbnailUrl.contains("i.ytimg.com/vi/")) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(artistStat.thumbnailUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = artistStat.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = artistStat.name,
                                        tint = textSecondary,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artistStat.name,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    ),
                                    color = textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                val timesLabel = if (artistStat.songsPlayedCount == 1) "1 song" else "${artistStat.songsPlayedCount} songs"
                                Text(
                                    text = "$timesLabel • ${makeDurationString(artistStat.timeListenedMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // Empty state if no stats at all
                if (overview.totalPlayTimeMs <= 0 && topSongs.isEmpty() && topArtists.isEmpty()) {
                    item(key = "empty_stats") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Headphones,
                                contentDescription = null,
                                tint = themePrimary.copy(alpha = 0.60f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "No listening stats yet",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = textPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Play your favorite music and your listening habits will appear here!",
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondary
                            )
                        }
                    }
                }
            }
        }

        // Floating Action Button: Shuffle Top Songs (Bottom Right)
        AnimatedVisibility(
            visible = topSongs.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = if (hasActiveMiniPlayer) 150.dp else 90.dp)
        ) {
            Surface(
                onClick = {
                    val shuffled = topSongs.map { it.track }.shuffled()
                    if (shuffled.isNotEmpty()) {
                        onPlayTrack(shuffled.first(), shuffled)
                    }
                },
                shape = CircleShape,
                color = themePrimary,
                shadowElevation = 8.dp,
                modifier = Modifier.tactileBounce(0.92f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Shuffle",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // Track Options Bottom Menu
        selectedTrackForMenu?.let { trk ->
            val isFav = favoriteTracks.any { it.id == trk.id }
            TrackOptionsMenu(
                track = trk,
                isFavorite = isFav,
                userPlaylists = userPlaylists,
                onToggleFavorite = { onFavoriteToggle(trk) },
                onPlayNext = {
                    onPlayNext(trk)
                    selectedTrackForMenu = null
                },
                onAddToQueue = {
                    onAddToQueue(trk)
                    selectedTrackForMenu = null
                },
                onGoToArtist = {
                    onArtistClick(Artist(id = "", name = trk.artist))
                    selectedTrackForMenu = null
                },
                onAddToPlaylist = { playlist ->
                    onAddToPlaylist(playlist.id, trk)
                    selectedTrackForMenu = null
                },
                onCreatePlaylistAndAdd = { title ->
                    onCreatePlaylistAndAdd(title, trk)
                    selectedTrackForMenu = null
                },
                isPinned = isTrackPinned?.invoke(trk.id) == true,
                onPinToSpeedDial = { onPinTrackToSpeedDial?.invoke(trk) },
                onDismiss = { selectedTrackForMenu = null }
            )
        }
    }
}

@Composable
private fun MetricMiniCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int,
    label: String,
    isDark: Boolean,
    cardBg: Color,
    borderColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    iconTint: Color = textPrimary,
    iconBg: Color = cardBg,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = cardBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = modifier.height(78.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = textPrimary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = textSecondary,
                    fontSize = 11.5.sp
                )
            }
        }
    }
}

fun makeDurationString(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSec = millis / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

/** Vertical position of the Auralis mark's visual centre within ic_auralis_header_logo (measured). */
private const val LOGO_VISUAL_CENTER_Y = 0.573f
