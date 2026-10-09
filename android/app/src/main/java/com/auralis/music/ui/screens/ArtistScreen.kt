package com.auralis.music.ui.screens

import com.auralis.music.ui.components.contextMenuAnchor
import com.auralis.music.R
import com.auralis.music.ui.i18n.str

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.auralis.music.ui.components.SwipeableTrackContainer
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.specularHighlight
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.components.bottomChromePadding
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance

private val LIME_ACCENT: Color
    @Composable get() = MaterialTheme.dynamicPrimary
private val DARK_BG: Color
    @Composable get() = MaterialTheme.dynamicBackground
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
    loadFailed: Boolean = false,
    onRetry: () -> Unit = {},
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    favoriteTracks: List<Track> = emptyList(),
    savedArtists: List<com.auralis.music.domain.model.SavedArtist> = emptyList(),
    savedAlbums: List<com.auralis.music.domain.model.SavedAlbum> = emptyList(),
    onToggleSubscribe: (com.auralis.music.domain.model.SavedArtist) -> Unit = {},
    onToggleSaveAlbum: (com.auralis.music.domain.model.SavedAlbum) -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onPlayNextAlbum: ((com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    onAddToQueueAlbum: ((com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    onShuffleAlbum: ((com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    onDownloadAlbum: ((com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    onAddAlbumToPlaylist: ((String, com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    onCreatePlaylistAndAddAlbum: ((String, com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    onStartRadio: (Track) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
    onAlbumClick: (com.auralis.music.domain.model.PlaylistResult) -> Unit = {},
    isAlbumPinned: ((String) -> Boolean)? = null,
    pinnedSpeedDialIds: Set<String> = emptySet(),
    onPinAlbumToSpeedDial: ((com.auralis.music.domain.model.PlaylistResult) -> Unit)? = null,
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
    onBack: () -> Unit,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val contentSettings by com.auralis.music.data.datastore.ContentSettingsStore.current.collectAsState()
    val isSubscribed = savedArtists.any { it.id == artistPage.artist.id || it.name.equals(artistPage.artist.name, ignoreCase = true) }
    var isBioExpanded by remember { mutableStateOf(false) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var selectedAlbumForMenu by remember { mutableStateOf<Pair<com.auralis.music.domain.model.PlaylistResult, Boolean>?>(null) }

    val isLiquidGlass = LocalLiquidGlass.current != null
    val themePrimary = MaterialTheme.dynamicPrimary
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val glassSpecularRim = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) listOf(
                Color.White.copy(alpha = 0.45f),
                Color.White.copy(alpha = 0.15f),
                Color.White.copy(alpha = 0.05f)
            ) else listOf(
                Color.White.copy(alpha = 0.95f),
                Color.White.copy(alpha = 0.50f),
                Color.Black.copy(alpha = 0.08f)
            )
        )
    }
    val glassActiveRim = remember(themePrimary) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.80f),
                themePrimary.copy(alpha = 0.40f),
                Color.White.copy(alpha = 0.20f)
            )
        )
    }
    val glassPillBg = if (isDark) Color(0xFF1E1E22).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.70f)
    val glassActiveBg = remember(themePrimary) {
        Brush.verticalGradient(
            listOf(
                themePrimary.copy(alpha = 0.95f),
                themePrimary.copy(alpha = 0.72f)
            )
        )
    }

    BackHandler {
        onBack()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DARK_BG)
    ) {
        val stableTopSongKeys = remember(artistPage.topSongs) {
            val counts = HashMap<String, Int>()
            artistPage.topSongs.map { track ->
                val count = counts[track.id] ?: 0
                counts[track.id] = count + 1
                if (count == 0) track.id else "${track.id}__dup$count"
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = bottomChromePadding()
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
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    val topButtonBaseModifier = if (isLiquidGlass) {
                        Modifier
                            .size(40.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = CircleShape,
                                ambientColor = Color.Black.copy(alpha = 0.45f),
                                spotColor = Color.Black.copy(alpha = 0.35f)
                            )
                            .clip(CircleShape)
                            .background(glassPillBg)
                            .border(
                                width = 1.dp,
                                brush = glassSpecularRim,
                                shape = CircleShape
                            )
                            .specularHighlight(CircleShape, highlightAlpha = 0.30f)
                    } else {
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                    }

                    Box(
                        modifier = topButtonBaseModifier.tactileBounce(scaleDown = 0.88f) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = str(R.string.back),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box(
                        modifier = topButtonBaseModifier.tactileBounce(scaleDown = 0.88f) {
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    str(R.string.listen_to_x_on_auralis_music_https_music, artistPage.artist.name, artistPage.artist.id)
                                )
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, str(R.string.share_artist)))
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = str(R.string.share),
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
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
                    val subscribeShape = RoundedCornerShape(24.dp)
                    val subscribeAction = {
                        onToggleSubscribe(
                            com.auralis.music.domain.model.SavedArtist(
                                id = artistPage.artist.id,
                                name = artistPage.artist.name,
                                thumbnail = artistPage.bannerUrl ?: artistPage.artist.thumbnail,
                                subscribers = artistPage.subscribers ?: artistPage.artist.subscribers
                            )
                        )
                    }

                    if (isLiquidGlass) {
                        Box(
                            modifier = Modifier
                                .shadow(elevation = 4.dp, shape = subscribeShape)
                                .clip(subscribeShape)
                                .background(if (isSubscribed) themePrimary.copy(alpha = 0.22f) else glassPillBg)
                                .border(1.dp, if (isSubscribed) glassActiveRim else glassSpecularRim, subscribeShape)
                                .specularHighlight(subscribeShape, highlightAlpha = if (isSubscribed) 0.30f else 0.22f)
                                .tactileBounce(scaleDown = 0.94f) { subscribeAction() }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (isSubscribed) str(R.string.subscribed) else str(R.string.subscribe),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSubscribed) themePrimary else Color.White
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clip(subscribeShape)
                                .background(if (isSubscribed) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, if (isSubscribed) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant, subscribeShape)
                                .clickable { subscribeAction() }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = if (isSubscribed) str(R.string.subscribed) else str(R.string.subscribe),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSubscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Shuffle / Quick Play Floating Button (Liquid Glass enabled)
                    val shuffleAction = {
                        if (artistPage.topSongs.isNotEmpty()) {
                            val shuffled = artistPage.topSongs.shuffled()
                            onTrackClick(shuffled.first(), shuffled)
                        }
                    }

                    if (isLiquidGlass) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .shadow(
                                    elevation = 10.dp,
                                    shape = CircleShape,
                                    ambientColor = themePrimary.copy(alpha = 0.50f),
                                    spotColor = Color.Black.copy(alpha = 0.35f)
                                )
                                .clip(CircleShape)
                                .background(glassActiveBg)
                                .border(1.2.dp, glassActiveRim, CircleShape)
                                .specularHighlight(CircleShape, highlightAlpha = 0.40f)
                                .tactileBounce(scaleDown = 0.90f) { shuffleAction() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = str(R.string.shuffle),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .tactileBounce(scaleDown = 0.90f) { shuffleAction() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = str(R.string.shuffle),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }

            // ================================================================
            // 3. ABOUT SECTION (Subscribers, Monthly Audience, Bio)
            // ================================================================
            // Settings → Content → Artist page decides which of the three show.
            val shownSubscribers = artistPage.subscribers.takeIf { contentSettings.showArtistSubscriberCount }
            val shownMonthly = artistPage.monthlyAudience.takeIf { contentSettings.showMonthlyListeners }
            val shownDescription = artistPage.description.takeIf { contentSettings.showArtistDescription }
            if (!shownDescription.isNullOrBlank() || !shownSubscribers.isNullOrBlank() || !shownMonthly.isNullOrBlank()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp)
                    ) {
                        Text(
                            text = str(R.string.about),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 17.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        shownSubscribers?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }

                        shownMonthly?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }

                        shownDescription?.let { bio ->
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
                                    text = if (isBioExpanded) str(R.string.show_less) else str(R.string.show_more),
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
                val onOpenTopSongs = {
                    onAlbumClick(
                        com.auralis.music.domain.model.PlaylistResult(
                            id = "artist_top_songs:${artistPage.artist.id.ifBlank { artistPage.artist.name }}",
                            title = "${artistPage.artist.name} - Top songs",
                            author = artistPage.artist.name,
                            thumbnail = artistPage.artist.thumbnail ?: artistPage.bannerUrl
                        )
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenTopSongs() }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = str(R.string.top_songs_2),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LIME_ACCENT,
                        fontSize = 20.sp
                    )

                    IconButton(
                        onClick = { onOpenTopSongs() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = str(R.string.view_all_top_songs),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = str(if (loadFailed) R.string.artist_load_failed else R.string.no_songs_found_for_this_artist),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (loadFailed) {
                            TextButton(onClick = onRetry) { Text(str(R.string.retry)) }
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = artistPage.topSongs,
                    key = { index, track -> stableTopSongKeys.getOrElse(index) { "${track.id}_$index" } },
                    contentType = { _, _ -> "track" }
                ) { index, track ->
                    val isCurrent = track.id == currentTrackId

                    SwipeableTrackContainer(
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .contextMenuAnchor { onTrackClick(track, artistPage.topSongs) }
                                .combinedClickable(
                                    onClick = { onTrackClick(track, artistPage.topSongs) },
                                    onLongClick = { selectedTrackForMenu = track }
                                )
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkCard(
                                sizeToConstraints = true,
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
                                    contentDescription = str(R.string.options),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
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
                        text = str(R.string.albums),
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
                                    .contextMenuAnchor { onAlbumClick(album) }
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { onAlbumClick(album) },
                                        onLongClick = { selectedAlbumForMenu = album to false }
                                    )
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
                                    text = album.author ?: str(R.string.album),
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
                        text = str(R.string.singles_eps),
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
                                    .contextMenuAnchor { onAlbumClick(single) }
                                    .clip(RoundedCornerShape(12.dp))
                                    .combinedClickable(
                                        onClick = { onAlbumClick(single) },
                                        onLongClick = { selectedAlbumForMenu = single to true }
                                    )
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
                                    text = single.author ?: str(R.string.single),
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
                        text = str(R.string.fans_might_also_like),
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
        val isPinned = isTrackPinned?.invoke(track.id) ?: pinnedSpeedDialIds.contains(track.id)
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
            onGoToArtist = null, // Already on ArtistScreen
            onGoToAlbum = { albumId, albumTitle, albumArtist, albumArt ->
                val cached = com.auralis.music.data.network.AlbumMetadataResolver.getCached(track.title, track.artist, track.album)
                onAlbumClick(
                    com.auralis.music.domain.model.PlaylistResult(
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

    // Album / Single Options Menu Bottom Sheet
    selectedAlbumForMenu?.let { (album, isSingle) ->
        val isSaved = savedAlbums.any { it.id == album.id || it.title.equals(album.title, ignoreCase = true) }
        val cleanAlbumId = album.id.removePrefix("album-").removePrefix("VL")
        val isPinned = pinnedSpeedDialIds.any {
            val id = it.removePrefix("album-").removePrefix("VL")
            id == cleanAlbumId
        } || (isAlbumPinned?.invoke(album.id) == true)
        com.auralis.music.ui.components.AlbumOptionsMenu(
            album = album,
            isSingleOrEp = isSingle,
            isFavorite = isSaved,
            userPlaylists = userPlaylists,
            isPinned = isPinned,
            onToggleFavorite = {
                val savedAlbum = com.auralis.music.domain.model.SavedAlbum(
                    id = album.id,
                    title = album.title,
                    artist = album.author ?: artistPage.artist.name,
                    thumbnail = album.thumbnail
                )
                onToggleSaveAlbum(savedAlbum)
            },
            onShuffle = {
                onShuffleAlbum?.invoke(album)
            },
            onShare = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, album.title)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        str(R.string.check_out_the_album_x_by_x_on_auralis_mu, album.title, album.author ?: artistPage.artist.name, album.id.removePrefix("VL"))
                    )
                }
                context.startActivity(Intent.createChooser(shareIntent, str(R.string.share_album)))
            },
            onPlayNext = {
                onPlayNextAlbum?.invoke(album)
            },
            onAddToQueue = {
                onAddToQueueAlbum?.invoke(album)
            },
            onAddToPlaylist = { playlist ->
                onAddAlbumToPlaylist?.invoke(playlist.id, album)
            },
            onCreatePlaylistAndAdd = { title ->
                onCreatePlaylistAndAddAlbum?.invoke(title, album)
            },
            onPinToSpeedDial = {
                onPinAlbumToSpeedDial?.invoke(album)
            },
            onDownload = {
                onDownloadAlbum?.invoke(album)
            },
            onViewArtist = {
                onOpenArtist(com.auralis.music.domain.model.Artist(id = "", name = album.author ?: artistPage.artist.name))
            },
            onDismiss = { selectedAlbumForMenu = null }
        )
    }
}
