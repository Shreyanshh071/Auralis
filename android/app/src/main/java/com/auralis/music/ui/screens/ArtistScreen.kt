package com.auralis.music.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import coil.request.ImageRequest
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.tactileBounce

private val LIME_ACCENT: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val DARK_BG: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val PILL_BG: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/**
 * Pure Jetpack Compose Artist Screen matching the exact YouTube Music layout:
 * - Immersive portrait photo header with dark gradient scrim
 * - Back button and Share link
 * - Artist title, Subscribed pill, Radio pill, and Shuffle FAB
 * - About section (Subscribers, description, expandable "Show more")
 * - Top songs ranked list with durations and track options menu
 * - Discography shelves (Albums, Singles, and Similar artists)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistScreen(
    artistPage: ArtistPage,
    isLoading: Boolean,
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    favoriteTracks: List<Track> = emptyList(),
    savedArtists: List<com.auralis.music.domain.model.SavedArtist> = emptyList(),
    onToggleSubscribe: (com.auralis.music.domain.model.SavedArtist) -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onStartRadio: (Track) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
    onAlbumClick: (com.auralis.music.domain.model.PlaylistResult) -> Unit = {},
    onBack: () -> Unit,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isSubscribed = savedArtists.any { it.id == artistPage.artist.id || it.name.equals(artistPage.artist.name, ignoreCase = true) }
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
        val artistBottomPad = if (currentTrackId != null) 180.dp else 100.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = artistBottomPad)
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
                    val isKanye = artistPage.artist.name.equals("Kanye West", ignoreCase = true) || artistPage.artist.name.equals("Ye", ignoreCase = true)
                    val defaultKanye = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5c/Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg/1280px-Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"
                    val rawBanner = artistPage.bannerUrl ?: artistPage.artist.thumbnail
                    val banner = when {
                        isKanye && (rawBanner.isNullOrBlank() || rawBanner.contains("IFlc3sf6sHV3TAZ_5vhyHQiKb9D4AdSlDkiTSgsRiicnzLASXwVr1n22EEg6Vtd2XBlyJslm8xlYiA")) -> defaultKanye
                        else -> rawBanner
                    }
                    if (!banner.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(getHighResArtworkUrl(banner))
                                .crossfade(true)
                                .build(),
                            contentDescription = artistPage.artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(MaterialTheme.colorScheme.primaryContainer, DARK_BG)
                                    )
                                )
                        )
                    }

                    // Multi-stop cinema gradient scrim to melt photo into page background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.45f to Color.Black.copy(alpha = 0.35f),
                                    0.80f to Color.Black.copy(alpha = 0.85f),
                                    1.0f to DARK_BG
                                )
                            )
                    )

                    // Top navigation bar (Back + Share)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = {
                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(
                                        android.content.Intent.EXTRA_TEXT,
                                        "Listen to ${artistPage.artist.name} on Auralis Music\nhttps://music.youtube.com/channel/${artistPage.artist.id}\n\nDownload Auralis App: https://auralis-self-nu.vercel.app/"
                                    )
                                    type = "text/plain"
                                }
                                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share Artist"))
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White
                            )
                        }
                    }

                    // Artist Name Title (Anchored at bottom-left of hero portrait)
                    Text(
                        text = artistPage.artist.name,
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
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
                    // Subscribe / Follow Button (Synced with Library)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (isSubscribed) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, if (isSubscribed) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                            .clickable {
                                onToggleSubscribe(
                                    com.auralis.music.domain.model.SavedArtist(
                                        id = artistPage.artist.id,
                                        name = artistPage.artist.name,
                                        thumbnail = artistPage.bannerUrl ?: artistPage.artist.thumbnail,
                                        subscribers = artistPage.subscribers ?: artistPage.artist.subscribers
                                    )
                                )
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (isSubscribed) "✓ Subscribed" else "+ Subscribe",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSubscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Shuffle / Quick Play Floating Button
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
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
                            tint = MaterialTheme.colorScheme.onPrimary,
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
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 17.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        artistPage.subscribers?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }

                        artistPage.monthlyAudience?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }

                        artistPage.description?.let { bio ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = bio,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = artistPage.topSongs,
                    key = { it.id },
                    contentType = { "track" }
                ) { track ->
                    val isCurrent = track.id == currentTrackId

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onTrackClick(track, artistPage.topSongs) },
                                onLongClick = { selectedTrackForMenu = track }
                            )
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
                                color = if (isCurrent) LIME_ACCENT else MaterialTheme.colorScheme.onBackground,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = { selectedTrackForMenu = track }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        items(
                            items = artistPage.albums,
                            key = { it.id },
                            contentType = { "album" }
                        ) { album ->
                            Column(
                                modifier = Modifier
                                    .width(135.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onAlbumClick(album) }
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
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = album.author ?: "Album",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        items(
                            items = artistPage.singles,
                            key = { it.id },
                            contentType = { "single" }
                        ) { single ->
                            Column(
                                modifier = Modifier
                                    .width(135.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onAlbumClick(single) }
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
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = single.author ?: "Single",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        items(
                            items = artistPage.similarArtists,
                            key = { it.id },
                            contentType = { "artist" }
                        ) { similar ->
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
                                    color = MaterialTheme.colorScheme.onBackground,
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
        val isFav = favoriteTracks.any { it.id == track.id }
        TrackOptionsMenu(
            track = track,
            isFavorite = isFav,
            userPlaylists = userPlaylists,
            onToggleFavorite = { onFavoriteToggle(track) },
            onPlayNext = { onPlayNext(track) },
            onAddToQueue = { onAddToQueue(track) },
            onStartRadio = { onStartRadio(track) },
            onGoToArtist = null, // Already on ArtistScreen
            onAddToPlaylist = { playlist -> onAddToPlaylist(playlist.id, track) },
            onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAdd(title, track) },
            isInListenTogetherRoom = isInListenTogetherRoom,
            onRecommendToRoom = onRecommendToRoom,
            onDismiss = { selectedTrackForMenu = null }
        )
    }
}
