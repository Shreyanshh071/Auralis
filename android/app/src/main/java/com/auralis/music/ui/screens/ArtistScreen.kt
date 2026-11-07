package com.auralis.music.ui.screens

import com.auralis.music.ui.components.contextMenuAnchor
import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
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
import com.auralis.music.ui.glass.underStatusBar
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private const val TOP_SONGS_SHOWN = 5
private val PillShape = RoundedCornerShape(26.dp)

/** The sharp photo dissolves into the blur before the name starts, eased so no line shows. */
private val HeroPhotoFade = Brush.verticalGradient(
    0.62f to Color.Black,
    0.78f to Color.Black.copy(alpha = .62f),
    0.92f to Color.Black.copy(alpha = .14f),
    1f to Color.Transparent
)

/** The blur under the name melts into the page. */
private val HeroFade = Brush.verticalGradient(
    0.8f to Color.Black,
    0.92f to Color.Black.copy(alpha = .55f),
    1f to Color.Transparent
)

/** Room under the photo for the name and audience. */
private val HeroTextSpace = 84.dp
private val HeroPhotoMaxHeight = 520.dp

/**
 * Artist page, with the same layout on the normal and liquid glass themes:
 * - A parallax portrait that melts into the page, with the name and audience below it
 * - A bar that stays pinned over the scroll and takes the artist's name once the portrait is gone
 * - Play, Shuffle, Radio and Subscribe
 * - Top songs, then every shelf the artist has (albums, singles, featured on...)
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
    // "Show all" on the Albums or Singles row: the whole discography, over this page.
    var allReleases by remember(artistPage.artist.id) { mutableStateOf<AllReleases?>(null) }

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

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    allReleases?.let { shown ->
        // Registered after the page's own handler, so Back closes the grid first.
        BackHandler { allReleases = null }
        ArtistAllReleasesPage(
            artistName = artistPage.artist.name,
            title = str(if (shown.isSingles) R.string.singles_eps else R.string.albums),
            endpoint = shown.endpoint,
            preview = if (shown.isSingles) artistPage.singles else artistPage.albums,
            fallbackSubtitle = str(if (shown.isSingles) R.string.single else R.string.album),
            accent = accent,
            background = pageBackground,
            onBack = { allReleases = null },
            onClick = onAlbumClick,
            onLongClick = { selectedAlbumForMenu = it to shown.isSingles },
            modifier = modifier
        )
    }

    if (allReleases == null) OptionalGlassPage(background = pageBackground, accent = accent,
        modifier = modifier.underStatusBar().fillMaxSize().background(pageBackground)) {
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
                        onClick = onAlbumClick, onLongClick = { selectedAlbumForMenu = it to false },
                        onShowAll = artistPage.albumsMore?.let { more -> { allReleases = AllReleases(more, isSingles = false) } })
                }
            }

            if (artistPage.singles.isNotEmpty()) {
                item(key = "singles", contentType = "releases") {
                    ArtistReleaseShelf(str(R.string.singles_eps), artistPage.singles, str(R.string.single), accent,
                        onClick = onAlbumClick, onLongClick = { selectedAlbumForMenu = it to true },
                        onShowAll = artistPage.singlesMore?.let { more -> { allReleases = AllReleases(more, isSingles = true) } })
                }
            }

            artistPage.shelves.forEach { shelf ->
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

        // The scrolling page continues behind the system icons without a separate tint strip.
        ArtistFloatingButtons(
            topInset = statusBarHeight,
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
    val pageBackground = MaterialTheme.dynamicBackground
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Square like the photos themselves, so nothing is cropped, with the text below it on the blur.
    val photoHeight = maxWidth.coerceAtMost(HeroPhotoMaxHeight)
    Box(Modifier.fillMaxWidth().height(photoHeight + HeroTextSpace)) {
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
                    drawRect(brush = HeroFade, blendMode = BlendMode.DstIn)
                }
        ) {
            // The old photo stays underneath while a better one fades in, so the swap never blinks.
            Crossfade(targetState = banner, modifier = Modifier.fillMaxSize(), animationSpec = tween(600), label = "artistPhoto") { photo ->
                Box(Modifier.fillMaxSize()) {
                    if (!photo.isNullOrBlank()) {
                        val request = ImageRequest.Builder(context)
                            .data(getHighResArtworkUrl(photo))
                            .crossfade(true)
                            .build()
                        // Blur only on Android 12 and up; below that the photo fades to the page.
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            AsyncImage(
                                model = request,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().blur(48.dp, BlurredEdgeTreatment.Rectangle)
                            )
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = artistPage.artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(photoHeight)
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(brush = HeroPhotoFade, blendMode = BlendMode.DstIn)
                                }
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer))
                    }
                }
            }
            // Calms a bright blur so the name stays readable.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.6f to pageBackground.copy(alpha = 0f),
                        0.84f to pageBackground.copy(alpha = .5f)
                    )
                )
            )
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
    actionDescription: String? = null,
    /** Spelled out before the chevron, for rows whose header would otherwise not read as a button. */
    actionLabel: String? = null
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
        if (onClick != null && actionLabel != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
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
    onLongClick: ((PlaylistResult) -> Unit)?,
    onShowAll: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ArtistSectionHeader(title, accent, onClick = onShowAll,
            actionDescription = onShowAll?.let { str(R.string.show_all) },
            actionLabel = onShowAll?.let { str(R.string.show_all) })
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

/** Which full list is open over the artist page. */
private data class AllReleases(val endpoint: com.auralis.music.domain.model.ArtistReleasesEndpoint, val isSingles: Boolean)

/**
 * Every album (or single) the artist has, as a grid. The row's preview shows at once and is
 * replaced by the full list when it arrives; a failed load keeps the preview and offers Retry.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistAllReleasesPage(
    artistName: String,
    title: String,
    endpoint: com.auralis.music.domain.model.ArtistReleasesEndpoint,
    preview: List<PlaylistResult>,
    fallbackSubtitle: String,
    accent: Color,
    background: Color,
    onBack: () -> Unit,
    onClick: (PlaylistResult) -> Unit,
    onLongClick: (PlaylistResult) -> Unit,
    modifier: Modifier = Modifier
) {
    var releases by remember(endpoint) { mutableStateOf<List<PlaylistResult>?>(null) }
    var failed by remember(endpoint) { mutableStateOf(false) }
    var attempt by remember(endpoint) { mutableIntStateOf(0) }
    LaunchedEffect(endpoint, attempt) {
        failed = false
        val loaded = try {
            com.auralis.music.data.network.LocalizedContent.run {
                com.auralis.music.data.network.InnerTubeClient().getArtistReleases(endpoint, artistName)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
        if (loaded.isNullOrEmpty()) failed = true else releases = loaded
    }
    val shown = releases ?: preview
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    OptionalGlassPage(background = background, accent = accent,
        modifier = modifier.underStatusBar().fillMaxSize().background(background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back), tint = MaterialTheme.colorScheme.onBackground)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artistName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (releases == null && !failed) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (failed && releases == null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(str(R.string.artist_releases_load_failed), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { attempt++ }) { Text(str(R.string.retry)) }
                }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = bottomChromePadding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(shown.size, key = { shown[it].id }, contentType = { "release" }) { index ->
                    val release = shown[index]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .contextMenuAnchor { onClick(release) }
                            .clip(RoundedCornerShape(18.dp))
                            .combinedClickable(onClick = { onClick(release) }, onLongClick = { onLongClick(release) })
                            .padding(4.dp)
                    ) {
                        ArtworkCard(
                            sizeToConstraints = true,
                            url = release.thumbnail ?: "",
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            cornerRadius = 16.dp,
                            contentDescription = release.title
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(release.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(release.author ?: fallbackSubtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
    onMore: (() -> Unit)? = null,
    /** For a page that runs behind the status bar, where the system inset is already used up. */
    topInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = topInset)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        com.auralis.music.ui.glass.FloatingPageButton(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back), backdrop, overArtwork = true, onClick = onBack)
        Spacer(Modifier.weight(1f))
        com.auralis.music.ui.glass.FloatingPageButton(Icons.Default.Share, str(R.string.share), backdrop, overArtwork = true, onClick = onShare)
        if (onMore != null) com.auralis.music.ui.glass.FloatingPageButton(Icons.Default.MoreVert, str(R.string.options), backdrop, overArtwork = true, onClick = onMore)
    }
}
