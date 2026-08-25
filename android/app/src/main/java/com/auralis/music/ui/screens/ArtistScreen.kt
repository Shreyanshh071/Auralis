package com.auralis.music.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.tactileBounce

private val LIME_ACCENT = Color(0xFFD4E157)
private val DARK_BG = Color(0xFF0E0F0C)
private val PILL_BG = Color(0xFF1B1D16)

/**
 * Pure Jetpack Compose Artist Screen matching the exact YouTube Music layout:
 * - Immersive portrait photo header with dark gradient scrim
 * - Back button and Share link
 * - Artist title, Subscribed pill, Radio pill, and Shuffle FAB
 * - About section (Subscribers, description, expandable "Show more")
 * - Top songs ranked list with durations and track options menu
 * - Discography shelves (Albums, Singles, and Similar artists)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artistPage: ArtistPage,
    isLoading: Boolean,
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onOpenArtist: (Artist) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isSubscribed by remember { mutableStateOf(false) }
    var isBioExpanded by remember { mutableStateOf(false) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    BackHandler {
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DARK_BG)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            // ================================================================
            // 1. IMMERSIVE HERO HEADER (Artist Portrait Photo + Dark Scrim)
            // ================================================================
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    val imageUrl = artistPage.bannerUrl ?: (if (artistPage.artist.id.startsWith("UC") && !artistPage.artist.thumbnail.isNullOrBlank() && !artistPage.artist.thumbnail.contains("i.ytimg.com")) artistPage.artist.thumbnail else null)
                    if (!imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = artistPage.artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF202319))
                        )
                    }

                    // Scrim gradient overlay (Fade to black at bottom)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.45f),
                                        Color.Transparent,
                                        DARK_BG.copy(alpha = 0.85f),
                                        DARK_BG
                                    ),
                                    startY = 0f,
                                    endY = Float.POSITIVE_INFINITY
                                )
                            )
                    )

                    // Top Action Bar Overlay
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, artistPage.artist.name)
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Check out ${artistPage.artist.name} on YouTube Music: https://music.youtube.com/channel/${artistPage.artist.id}"
                                    )
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Artist"))
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.35f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Artist Title positioned at the bottom of the hero image
                    Text(
                        text = artistPage.artist.name,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 32.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }

            // ================================================================
            // 2. ACTION ROW ([ Subscribed ]  [ Radio ]  [ Shuffle FAB ])
            // ================================================================
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Subscribe / Follow Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (isSubscribed) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                            .clickable { isSubscribed = !isSubscribed }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (isSubscribed) "Subscribed" else "Subscribe",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // Radio Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                            .clickable {
                                if (artistPage.topSongs.isNotEmpty()) {
                                    onTrackClick(artistPage.topSongs.first(), artistPage.topSongs.shuffled())
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Radio",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Shuffle / Quick Play Floating Button
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD4E157))
                            .tactileBounce(scaleDown = 0.90f) {
                                if (artistPage.topSongs.isNotEmpty()) {
                                    val shuffled = artistPage.topSongs.shuffled()
                                    onTrackClick(shuffled.first(), shuffled)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }

            // ================================================================
            // 3. ABOUT SECTION (Subscribers, Monthly Audience, Bio)
            // ================================================================
            if (!artistPage.description.isNullOrBlank() || !artistPage.subscribers.isNullOrBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                    ) {
                        Text(
                            text = "About",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 17.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        artistPage.subscribers?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.70f),
                                fontSize = 14.sp
                            )
                        }

                        artistPage.monthlyAudience?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 13.sp
                            )
                        }

                        artistPage.description?.let { bio ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = bio,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = if (isBioExpanded) 20 else 3,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 20.sp,
                                fontSize = 13.5.sp
                            )

                            if (bio.length > 100) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isBioExpanded) "Show less" else "Show more",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = LIME_ACCENT,
                                    modifier = Modifier
                                        .clickable { isBioExpanded = !isBioExpanded }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                    }
                }
            }

            // ================================================================
            // 4. TOP SONGS SECTION (Ranked Track List)
            // ================================================================
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Top songs",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LIME_ACCENT,
                        fontSize = 20.sp
                    )

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (isLoading && artistPage.topSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = LIME_ACCENT,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                }
            } else if (artistPage.topSongs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No songs found for this artist.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                items(artistPage.topSongs, key = { it.id }) { track ->
                    val isCurrent = track.id == currentTrackId

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackClick(track, artistPage.topSongs) }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkCard(
                            url = track.thumbnail,
                            modifier = Modifier.size(48.dp),
                            cornerRadius = 8.dp,
                            contentDescription = track.title
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent) LIME_ACCENT else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            val durationStr = if (track.duration > 0) {
                                val mins = track.duration / 60
                                val secs = track.duration % 60
                                " • %d:%02d".format(mins, secs)
                            } else ""

                            Text(
                                text = "${track.artist}$durationStr",
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

                item {
                    Spacer(modifier = Modifier.height(18.dp))
                }
            }

            // ================================================================
            // 5. ALBUMS & SINGLES DISCOGRAPHY CAROUSELS
            // ================================================================
            if (artistPage.albums.isNotEmpty()) {
                item {
                    Text(
                        text = "Albums",
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
                        items(artistPage.albums, key = { it.id }) { album ->
                            Column(
                                modifier = Modifier
                                    .width(135.dp)
                                    .padding(4.dp)
                            ) {
                                ArtworkCard(
                                    url = album.thumbnail ?: "",
                                    modifier = Modifier.size(135.dp),
                                    cornerRadius = 12.dp,
                                    contentDescription = album.title
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = album.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = album.author ?: "Album",
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

            if (artistPage.singles.isNotEmpty()) {
                item {
                    Text(
                        text = "Singles & EPs",
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
                        items(artistPage.singles, key = { it.id }) { single ->
                            Column(
                                modifier = Modifier
                                    .width(135.dp)
                                    .padding(4.dp)
                            ) {
                                ArtworkCard(
                                    url = single.thumbnail ?: "",
                                    modifier = Modifier.size(135.dp),
                                    cornerRadius = 12.dp,
                                    contentDescription = single.title
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = single.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = single.author ?: "Single",
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
            // 6. FANS MIGHT ALSO LIKE (Similar Artists)
            // ================================================================
            if (artistPage.similarArtists.isNotEmpty()) {
                item {
                    Text(
                        text = "Fans might also like",
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
                        items(artistPage.similarArtists, key = { it.id }) { similar ->
                            Column(
                                modifier = Modifier
                                    .width(105.dp)
                                    .clickable { onOpenArtist(similar) }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                ArtworkCard(
                                    url = similar.thumbnail,
                                    modifier = Modifier
                                        .size(90.dp)
                                        .clip(CircleShape),
                                    cornerRadius = 45.dp,
                                    contentDescription = similar.name
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = similar.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }

    // Options Menu Bottom Sheet
    selectedTrackForMenu?.let { track ->
        TrackOptionsMenu(
            track = track,
            isFavorite = false,
            userPlaylists = userPlaylists,
            onToggleFavorite = { onFavoriteToggle(track) },
            onPlayNext = {},
            onAddToQueue = {},
            onAddToPlaylist = { playlist -> onAddToPlaylist(playlist.id, track) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAdd(title, track) },
            onDismiss = { selectedTrackForMenu = null }
        )
    }
}
