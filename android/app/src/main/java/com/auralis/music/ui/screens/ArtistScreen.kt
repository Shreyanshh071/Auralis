package com.auralis.music.ui.screens

import com.auralis.music.ui.components.contextMenuAnchor
import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.SwipeableTrackContainer
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.bottomChromePadding
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.components.rememberPageHeaderGlass
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.glass.OptionalGlassCard
import com.auralis.music.ui.glass.OptionalGlassPage
import com.auralis.music.ui.glass.glassBarTint
import com.auralis.music.ui.glass.liquidGlass
import com.auralis.music.ui.glass.pageGlassActionColor
import com.auralis.music.ui.glass.pageGlassSelection
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private const val TOP_SONGS_SHOWN = 5
private val PillShape = RoundedCornerShape(26.dp)

/**
 * Artist page, with the same layout on the normal and liquid glass themes:
 * - A parallax portrait that melts into the page, with the name and audience below it
 * - A bar that stays pinned over the scroll and takes the artist's name once the portrait is gone
 * - Play, Shuffle, Radio and Subscribe
 * - Top songs, then every shelf the artist has (albums, singles, videos, featured on...)
 * - About, and fans might also like
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
    onPlayNextAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddToQueueAlbum: ((PlaylistResult) -> Unit)? = null,
    onShuffleAlbum: ((PlaylistResult) -> Unit)? = null,
    onDownloadAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddAlbumToPlaylist: ((String, PlaylistResult) -> Unit)? = null,
    onCreatePlaylistAndAddAlbum: ((String, PlaylistResult) -> Unit)? = null,
    onStartRadio: (Track) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
    onAlbumClick: (PlaylistResult) -> Unit = {},
    isAlbumPinned: ((String) -> Boolean)? = null,
    pinnedSpeedDialIds: Set<String> = emptySet(),
    onPinAlbumToSpeedDial: ((PlaylistResult) -> Unit)? = null,
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
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var selectedAlbumForMenu by remember { mutableStateOf<Pair<PlaylistResult, Boolean>?>(null) }

    val accent = MaterialTheme.dynamicPrimary
    val pageBackground = MaterialTheme.dynamicBackground
    val listState = rememberLazyListState()
    // Records the list alone, so the pinned bar's glass bends the page without sampling itself.
    val contentBackdrop = rememberLayerBackdrop()

    val openTopSongs = {
        onAlbumClick(
            PlaylistResult(
                id = "artist_top_songs:${artistPage.artist.id.ifBlank { artistPage.artist.name }}",
                title = "${artistPage.artist.name} - Top songs",
                author = artistPage.artist.name,
                thumbnail = artistPage.artist.thumbnail ?: artistPage.bannerUrl
            )
        )
    }

    BackHandler {
        onBack()
    }

    OptionalGlassPage(background = pageBackground, accent = accent,
        modifier = modifier.fillMaxSize().background(pageBackground)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(contentBackdrop),
            contentPadding = bottomChromePadding(),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            item(key = "hero", contentType = "hero") {
                ArtistHero(
                    artistPage = artistPage,
                    audience = listOfNotNull(
                        artistPage.monthlyAudience.takeIf { contentSettings.showMonthlyListeners },
                        artistPage.subscribers.takeIf { contentSettings.showArtistSubscriberCount }
                    ).joinToString(" · "),
                    parallaxOffset = {
                        if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 0f
                    }
                )
            }

            item(key = "actions", contentType = "actions") {
                ArtistActions(
                    isSubscribed = isSubscribed,
                    enabled = artistPage.topSongs.isNotEmpty(),
                    accent = accent,
                    onPlay = { artistPage.topSongs.firstOrNull()?.let { onTrackClick(it, artistPage.topSongs) } },
                    onShuffle = {
                        if (artistPage.topSongs.isNotEmpty()) {
                            val shuffled = artistPage.topSongs.shuffled()
                            onTrackClick(shuffled.first(), shuffled)
                        }
                    },
                    onRadio = { artistPage.topSongs.firstOrNull()?.let(onStartRadio) },
                    onToggleSubscribe = {
                        onToggleSubscribe(
                            com.auralis.music.domain.model.SavedArtist(
                                id = artistPage.artist.id,
                                name = artistPage.artist.name,
                                thumbnail = artistPage.bannerUrl ?: artistPage.artist.thumbnail,
                                subscribers = artistPage.subscribers ?: artistPage.artist.subscribers
                            )
                        )
                    },
                    modifier = Modifier.softenWhenLeaving()
                )
            }

            artistPage.latestRelease?.let { latest ->
                item(key = "latest_release", contentType = "latest_release") {
                    ArtistLatestReleaseCard(latest, accent, onClick = { onAlbumClick(latest.release) })
                }
            }

            item(key = "top_songs", contentType = "top_songs") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ArtistSectionHeader(str(R.string.top_songs_2), accent, onClick = openTopSongs,
                        actionDescription = str(R.string.view_all_top_songs))
                    when {
                        isLoading && artistPage.topSongs.isEmpty() -> Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = accent, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                        }
                        artistPage.topSongs.isEmpty() -> Column(
                            Modifier.fillMaxWidth().padding(24.dp),
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
                        else -> OptionalGlassCard(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = null,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                artistPage.topSongs.take(TOP_SONGS_SHOWN).forEachIndexed { index, track ->
                                    key(index, track.id) {
                                        ArtistTopSongRow(
                                            rank = index + 1,
                                            track = track,
                                            isCurrent = track.id == currentTrackId,
                                            isPlaying = isPlaying,
                                            accent = accent,
                                            onClick = { onTrackClick(track, artistPage.topSongs) },
                                            onShowOptions = { selectedTrackForMenu = track },
                                            onPlayNext = { onPlayNext(track) },
                                            onAddToQueue = { onAddToQueue(track) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (artistPage.albums.isNotEmpty()) {
                item(key = "albums", contentType = "releases") {
                    ArtistReleaseShelf(str(R.string.albums), artistPage.albums, str(R.string.album), accent,
                        onClick = onAlbumClick, onLongClick = { selectedAlbumForMenu = it to false })
                }
            }

            if (artistPage.singles.isNotEmpty()) {
                item(key = "singles", contentType = "releases") {
                    ArtistReleaseShelf(str(R.string.singles_eps), artistPage.singles, str(R.string.single), accent,
                        onClick = onAlbumClick, onLongClick = { selectedAlbumForMenu = it to true })
                }
            }

            artistPage.shelves.forEach { shelf ->
                if (shelf.tracks.isNotEmpty()) {
                    item(key = "shelf_tracks_${shelf.title}", contentType = "videos") {
                        ArtistVideoShelf(shelf.title, shelf.tracks, accent,
                            onClick = { onTrackClick(it, shelf.tracks) }, onLongClick = { selectedTrackForMenu = it })
                    }
                }
                if (shelf.playlists.isNotEmpty()) {
                    item(key = "shelf_playlists_${shelf.title}", contentType = "releases") {
                        ArtistReleaseShelf(shelf.title, shelf.playlists, "", accent,
                            onClick = onAlbumClick, onLongClick = null)
                    }
                }
            }

            // Settings → Content → Artist page decides whether the biography shows.
            val shownDescription = artistPage.description.takeIf { contentSettings.showArtistDescription }
            if (!shownDescription.isNullOrBlank()) {
                item(key = "about", contentType = "about") {
                    ArtistAbout(shownDescription, accent)
                }
            }

            if (artistPage.similarArtists.isNotEmpty()) {
                item(key = "similar", contentType = "similar") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ArtistSectionHeader(str(R.string.fans_might_also_like), accent)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(items = artistPage.similarArtists, key = { it.id }, contentType = { "artist" }) { similar ->
                                Column(
                                    modifier = Modifier.width(112.dp).clip(RoundedCornerShape(16.dp))
                                        .clickable { onOpenArtist(similar) }.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    ArtworkCard(
                                        url = similar.thumbnail,
                                        modifier = Modifier.size(100.dp).clip(CircleShape),
                                        cornerRadius = 50.dp,
                                        contentDescription = similar.name
                                    )
                                    Spacer(Modifier.height(8.dp))
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
                    }
                }
            }
        }

        ArtistFloatingButtons(
            backdrop = contentBackdrop,
            onBack = onBack,
            onShare = {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(
                        Intent.EXTRA_TEXT,
                        str(R.string.listen_to_x_on_auralis_music_https_music, artistPage.artist.name, artistPage.artist.id)
                    )
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, str(R.string.share_artist)))
            }
        )
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

@Composable
private fun ArtistHero(artistPage: ArtistPage, audience: String, parallaxOffset: () -> Float) {
    val context = LocalContext.current
    val isKanye = artistPage.artist.name.equals("Kanye West", ignoreCase = true) || artistPage.artist.name.equals("Ye", ignoreCase = true)
    val defaultKanye = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5c/Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg/1280px-Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"
    val rawBanner = artistPage.bannerUrl ?: artistPage.artist.thumbnail
    val banner = when {
        isKanye && (rawBanner.isNullOrBlank() || rawBanner.contains("IFlc3sf6sHV3TAZ_5vhyHQiKb9D4AdSlDkiTSgsRiicnzLASXwVr1n22EEg6Vtd2XBlyJslm8xlYiA")) -> defaultKanye
        else -> rawBanner
    }
    Box(Modifier.fillMaxWidth().height(430.dp)) {
        // The photo fades to nothing instead of to a colour, so it melts into the page's own
        // background on both themes, including the glass page's glow.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    translationY = parallaxOffset() * .45f
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(0.5f to Color.Black, 0.97f to Color.Transparent),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
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
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
            }
            // Keeps the status bar and pinned buttons readable on a bright photo.
            Box(
                Modifier.fillMaxWidth().height(130.dp).background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent))
                )
            )
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().softenWhenLeaving().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = artistPage.artist.name,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (audience.isNotBlank()) {
                Text(
                    text = audience,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Blurs and dims what's scrolling up towards the top of the page, so it melts away under the
 * floating buttons. Blur only on Android 12 and up. The state is read in the layer, so scrolling
 * doesn't recompose anything.
 */
@Composable
private fun Modifier.softenWhenLeaving(): Modifier {
    val density = LocalDensity.current
    val statusBar = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density).toFloat()
    val start = statusBar + with(density) { 170.dp.toPx() }
    val end = statusBar + with(density) { 30.dp.toPx() }
    val maxBlur = with(density) { 14.dp.toPx() }
    val top = remember { androidx.compose.runtime.mutableFloatStateOf(Float.MAX_VALUE) }
    return this
        .onGloballyPositioned { top.floatValue = it.positionInRoot().y }
        .graphicsLayer {
            val progress = ((start - top.floatValue) / (start - end)).coerceIn(0f, 1f)
            alpha = 1f - progress * .55f
            val radius = kotlin.math.round(progress * maxBlur)
            renderEffect = if (radius >= 1f && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal)
            } else null
        }
}

@Composable
internal fun ArtistActions(
    isSubscribed: Boolean,
    enabled: Boolean,
    accent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
    onToggleSubscribe: () -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val neutral = MaterialTheme.colorScheme.surfaceContainerHigh
    val neutralContent = pageGlassActionColor(MaterialTheme.colorScheme.onSurface)
    val onAccent = pageGlassActionColor(MaterialTheme.colorScheme.onPrimary)
    Row(
        modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(52.dp)
                .pageGlassSelection(PillShape, true, accent, accent)
                .tactileBounce(scaleDown = 0.95f, enabled = enabled) { onPlay() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = onAccent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Text(str(R.string.play), color = onAccent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        ArtistRoundAction(Icons.Default.Shuffle, str(R.string.shuffle), neutral, neutralContent, enabled, onShuffle)
        ArtistRoundAction(Icons.Default.Radio, str(R.string.radio), neutral, neutralContent, enabled, onRadio)
        ArtistRoundAction(
            icon = if (isSubscribed) Icons.Default.Check else Icons.Default.Add,
            description = str(if (isSubscribed) R.string.subscribed else R.string.subscribe),
            fill = if (isSubscribed) accent.copy(alpha = .22f) else neutral,
            content = if (isSubscribed) accent else neutralContent,
            enabled = true,
            onClick = onToggleSubscribe
        )
    }
}

@Composable
private fun ArtistRoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    fill: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(52.dp)
            .pageGlassSelection(CircleShape, true, fill, fill)
            .tactileBounce(scaleDown = 0.90f, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ArtistLatestReleaseCard(
    latest: com.auralis.music.domain.model.ArtistLatestRelease,
    accent: Color,
    onClick: () -> Unit
) {
    OptionalGlassCard(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkCard(
                url = latest.release.thumbnail ?: "",
                modifier = Modifier.size(92.dp),
                cornerRadius = 16.dp,
                contentDescription = latest.release.title
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = latest.year.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = latest.release.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = str(if (latest.isAlbum) R.string.latest_album else R.string.latest_single),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ArtistSectionHeader(
    title: String,
    accent: Color,
    onClick: (() -> Unit)? = null,
    actionDescription: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = accent,
            fontSize = 21.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = actionDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistTopSongRow(
    rank: Int,
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onShowOptions: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    SwipeableTrackContainer(onPlayNext = onPlayNext, onAddToQueue = onAddToQueue) {
        Row(
            Modifier
                .fillMaxWidth()
                .contextMenuAnchor { onClick() }
                .combinedClickable(onClick = onClick, onLongClick = onShowOptions)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (isCurrent && isPlaying) {
                    Icon(Icons.Default.GraphicEq, null, tint = accent, modifier = Modifier.size(18.dp))
                } else {
                    Text(
                        text = rank.toString(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (isCurrent) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            ArtworkCard(
                sizeToConstraints = true,
                url = track.thumbnail,
                modifier = Modifier.size(50.dp),
                cornerRadius = 10.dp,
                contentDescription = track.title
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isCurrent) accent else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val duration = if (track.duration > 0) {
                    " • %d:%02d".format(track.duration / 60, track.duration % 60)
                } else ""
                Text(
                    text = "${track.artist}$duration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onShowOptions) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistReleaseShelf(
    title: String,
    releases: List<PlaylistResult>,
    fallbackSubtitle: String,
    accent: Color,
    onClick: (PlaylistResult) -> Unit,
    onLongClick: ((PlaylistResult) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ArtistSectionHeader(title, accent)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items = releases, key = { it.id }, contentType = { "release" }) { release ->
                Column(
                    Modifier
                        .width(156.dp)
                        .contextMenuAnchor { onClick(release) }
                        .clip(RoundedCornerShape(18.dp))
                        .combinedClickable(
                            onClick = { onClick(release) },
                            onLongClick = onLongClick?.let { { it(release) } }
                        )
                        .padding(4.dp)
                ) {
                    ArtworkCard(
                        url = release.thumbnail ?: "",
                        modifier = Modifier.size(148.dp),
                        cornerRadius = 16.dp,
                        contentDescription = release.title
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = release.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    (release.author ?: fallbackSubtitle).takeIf { it.isNotBlank() }?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistVideoShelf(
    title: String,
    tracks: List<Track>,
    accent: Color,
    onClick: (Track) -> Unit,
    onLongClick: (Track) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ArtistSectionHeader(title, accent)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items = tracks, key = { it.id }, contentType = { "video" }) { track ->
                Column(
                    Modifier
                        .width(248.dp)
                        .contextMenuAnchor { onClick(track) }
                        .clip(RoundedCornerShape(18.dp))
                        .combinedClickable(onClick = { onClick(track) }, onLongClick = { onLongClick(track) })
                        .padding(4.dp)
                ) {
                    AsyncImage(
                        model = track.thumbnail,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(135.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                    Spacer(Modifier.height(8.dp))
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistAbout(description: String, accent: Color) {
    var expanded by remember(description) { mutableStateOf(false) }
    val expandable = description.length > 100
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ArtistSectionHeader(str(R.string.about), accent)
        OptionalGlassCard(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Column(
                Modifier
                    .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
                    .padding(18.dp)
                    .animateContentSize()
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 21.sp
                )
                if (expandable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = str(if (expanded) R.string.show_less else R.string.show_more),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
            }
        }
    }
}

/** Back on the left and the page's actions on the right, floating over the list with no bar. */
@Composable
internal fun BoxScope.ArtistFloatingButtons(
    backdrop: com.kyant.backdrop.Backdrop,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onMore: (() -> Unit)? = null
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        com.auralis.music.ui.glass.FloatingPageButton(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back), backdrop, onClick = onBack)
        Spacer(Modifier.weight(1f))
        com.auralis.music.ui.glass.FloatingPageButton(Icons.Default.Share, str(R.string.share), backdrop, onClick = onShare)
        if (onMore != null) com.auralis.music.ui.glass.FloatingPageButton(Icons.Default.MoreVert, str(R.string.options), backdrop, onClick = onMore)
    }
}
