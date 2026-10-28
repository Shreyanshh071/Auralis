package com.auralis.music.windows.home

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import com.auralis.music.ui.components.DropdownMenuItem
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.launch

/** An artist the listener plays, with their own play count, recent pattern stats, and photo. */
internal data class FamiliarArtist(
    val name: String,
    val plays: Int,
    val id: String = "",
    val photo: String? = null,
    val subscribers: String? = null,
    val recentPlays: Int = plays,
    val weekPlays: Int = 0,
    val totalPlays: Int = plays,
    val patternScore: Double = plays.toDouble(),
    val lastPlayedAt: Long = 0L,
    /** Time spent listening to this artist (ms), measured. */
    val listenedMs: Long = 0L
)

internal enum class FamiliarArtistTimeRange { RECENT, WEEK, ALL_TIME }

internal const val MIN_FAMILIAR_ARTISTS = 5
internal const val MIN_FAMILIAR_ARTIST_LISTEN_MS = 5 * 60_000L

/** Keep the existing artist posters; reveal them only after enough measured listening. */
internal fun eligibleFamiliarArtistStats(stats: List<FamiliarArtistStats>): List<FamiliarArtistStats> {
    val qualified = stats.filter { it.listenedMs >= MIN_FAMILIAR_ARTIST_LISTEN_MS }
        .distinctBy { SearchQueryMatcher.normalize(cleanFamiliarArtistName(it.name)) }
    return qualified.takeIf { it.size >= MIN_FAMILIAR_ARTISTS }.orEmpty()
}

internal data class FamiliarArtistStats(
    val name: String,
    val totalPlays: Int,
    val weekPlays: Int,
    val recentPlays: Int,
    val patternScore: Double,
    val lastPlayedAt: Long = 0L,
    val listenedMs: Long = 0L
)

private val ArtistSplitRegex = Regex("""\s*(?:,|&| feat\.? | ft\.? | x )\s*""", RegexOption.IGNORE_CASE)

internal fun cleanFamiliarArtistName(raw: String): String {
    return raw.split(ArtistSplitRegex)
        .firstOrNull()?.trim()?.removeSuffix(" - Topic") ?: raw.trim()
}

/**
 * Computes artist stats weighted by the listener's active listening pattern.
 * Recent listens (especially within the last 3 to 7 days) receive strong recency multipliers,
 * ensuring that current rotations dynamically lead while preserving historical anchors.
 */
internal fun computeFamiliarArtistStats(
    listens: List<DesktopListeningStats.Listen>,
    blockedTracks: (Track) -> Boolean = { false },
    now: Long = System.currentTimeMillis()
): List<FamiliarArtistStats> {
    val qualifying = listens.filter {
        (it.countsAsPlay || it.timeMs >= 15_000L) && !blockedTracks(it.track)
    }

    val grouped = qualifying.groupBy {
        SearchQueryMatcher.normalize(cleanFamiliarArtistName(it.track.artist))
    }.filterKeys {
        it.isNotBlank() && it != "unknown artist" && it != "various artists"
    }

    return grouped.map { (_, groupListens) ->
        val displayName = cleanFamiliarArtistName(groupListens.first().track.artist)
        var total = 0
        var week = 0
        var recent = 0
        var score = 0.0

        for (l in groupListens) {
            val isPlay = l.countsAsPlay
            val isIntentional = isPlay || l.timeMs >= 15_000L
            if (isPlay) total++

            val ageMs = (now - l.startedAt).coerceAtLeast(0L)
            val ageDays = ageMs / 86_400_000.0

            if (isIntentional) {
                if (ageDays <= 7.0) week++
                if (ageDays <= 30.0) recent++

                val recencyMultiplier = when {
                    ageDays <= 3.0 -> 5.0
                    ageDays <= 7.0 -> 3.5
                    ageDays <= 30.0 -> 2.0
                    ageDays <= 90.0 -> 1.0
                    else -> 0.35
                }
                val durationMultiplier = (l.timeMs / 60_000.0).coerceIn(0.5, 2.0)
                score += (recencyMultiplier * durationMultiplier)
            }
        }

        val finalTotal = if (total > 0) total else groupListens.size

        FamiliarArtistStats(
            name = displayName,
            totalPlays = finalTotal,
            weekPlays = week,
            recentPlays = recent,
            patternScore = score,
            lastPlayedAt = groupListens.maxOf { it.startedAt },
            listenedMs = groupListens.sumOf { it.timeMs }
        )
    }
}

/**
 * What the poster's two buttons do, after Li Music's: subscribe (the YouTube account's own
 * subscription, so only when signed in) and the ⋯ menu (radio, shortcut, share, library, block).
 * `subscribed` is null while unknown (signed out, or the artist page hasn't answered yet).
 */
internal class FamiliarArtistActions(
    val subscribed: (FamiliarArtist) -> Boolean?,
    val subscribeBusy: (FamiliarArtist) -> Boolean,
    val toggleSubscribe: (FamiliarArtist) -> Unit,
    val startRadio: (FamiliarArtist) -> Unit,
    val isShortcut: (FamiliarArtist) -> Boolean,
    val toggleShortcut: (FamiliarArtist) -> Unit,
    val share: (FamiliarArtist) -> Unit,
    val inLibrary: (FamiliarArtist) -> Boolean,
    val toggleLibrary: (FamiliarArtist) -> Unit,
    val block: (FamiliarArtist) -> Unit,
)

/**
 * Familiar Artists: total listening time first, then play count for equal times.
 */
@Composable
internal fun FamiliarArtistsRow(
    artists: List<FamiliarArtist>,
    onOpen: (FamiliarArtist) -> Unit,
    actions: FamiliarArtistActions,
    /** Offscreen renders only: show the buttons without hovering. */
    showControls: Boolean = false
) {
    if (artists.size < 3) return
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Rank using measured time, before formatting it into hours and minutes.
    val sortedArtists = remember(artists) { rankFamiliarArtists(artists) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Person, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Text(desktopText("Familiar Artists"), style = MaterialTheme.typography.titleLarge.copy(fontSize = 25.sp, lineHeight = 31.sp), fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(8.dp))
            if (list.canScrollBackward) {
                IconButton(onClick = {
                    scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
                }) {
                    Icon(Icons.Outlined.ChevronLeft, desktopText("Previous artists"))
                }
            }
            if (list.canScrollForward) {
                IconButton(onClick = {
                    scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex + 3).coerceAtMost(sortedArtists.take(10).lastIndex)) }
                }) {
                    Icon(Icons.Outlined.ChevronRight, desktopText("More artists"))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = ((maxWidth - 60.dp) / 6).coerceIn(180.dp, 252.dp)
            LazyRow(state = list, modifier = Modifier.fillMaxWidth().mouseDragScroll(list), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(sortedArtists.take(10)) { index, artist ->
                    ArtistPoster(artist, index + 1, FamiliarArtistTimeRange.ALL_TIME, { onOpen(artist) }, actions, Modifier.width(cardWidth), showControls)
                }
            }
        }
    }
}

private val PosterPhotoAlignment = BiasAlignment(0f, -0.56f)
private val GlassButtonSize = 32.dp
private val GlassInset = 8.dp
private val GlassGap = 4.dp

@Composable
private fun ArtistPoster(
    artist: FamiliarArtist,
    rank: Int,
    timeRange: FamiliarArtistTimeRange = FamiliarArtistTimeRange.RECENT,
    onOpen: () -> Unit,
    actions: FamiliarArtistActions,
    modifier: Modifier,
    showControls: Boolean = false
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    // On hover only (or while the menu is open): six posters each carrying two discs would cover the faces.
    val controls by animateFloatAsState(if (hovered || menuOpen || showControls) 1f else 0f, tween(150), label = "posterControls")
    val photo = artist.photo?.let { com.auralis.music.ui.components.getHighResArtworkUrl(it) ?: it }
    BoxWithConstraints(modifier.aspectRatio(4f / 5f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .hoverable(hover).clickable(onClick = onOpen)) {
        val posterWidth = maxWidth; val posterHeight = maxHeight
        if (photo != null) {
            FamiliarArtistPhoto(photo, artist.name, Modifier.fillMaxSize())
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Person, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Black.copy(alpha = .2f), 1f to Color.Black.copy(alpha = .85f))))
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(rank.toString().padStart(2, '0'), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Text(artist.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))

            // Plays and time listened: what the ranking is made of.
            val plays = artist.totalPlays
            val playText = buildString {
                append(desktopText(if (plays == 1) "%1\$s play" else "%1\$s plays", plays))
                formatListened(artist.listenedMs)?.let { append(" · ").append(it) }
            }
            Text(playText, color = Color.White.copy(alpha = .65f), fontSize = 12.sp,
                maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        if (controls > 0.01f) Row(Modifier.align(Alignment.TopEnd).padding(top = GlassInset, end = GlassInset).alpha(controls),
            horizontalArrangement = Arrangement.spacedBy(GlassGap)) {
            val subscribed = actions.subscribed(artist)
            val busy = actions.subscribeBusy(artist)
            // Pulses while the subscription is being written, as Li Music's button does.
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "subscribeBusy").animateFloat(1f, .45f,
                androidx.compose.animation.core.infiniteRepeatable(tween(600), androidx.compose.animation.core.RepeatMode.Reverse),
                label = "subscribeBusyAlpha")
            // Each button's left edge on the poster, so its frosted glass shows the photo right behind it.
            val subscribeLeft = posterWidth - GlassInset - GlassButtonSize * 2 - GlassGap
            val menuLeft = posterWidth - GlassInset - GlassButtonSize
            GlassButton(photo, posterWidth, posterHeight, subscribeLeft, GlassInset,
                if (subscribed == true) "Unsubscribe from ${artist.name}" else "Subscribe to ${artist.name}",
                if (subscribed == true) Icons.Outlined.HowToReg else Icons.Outlined.PersonAddAlt,
                tint = if (subscribed == true) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .8f),
                modifier = Modifier.alpha(if (busy) pulse else 1f)) { if (!busy) actions.toggleSubscribe(artist) }
            Box {
                GlassButton(photo, posterWidth, posterHeight, menuLeft, GlassInset, "More options for ${artist.name}",
                    Icons.Outlined.MoreVert, tint = Color.White.copy(alpha = .8f)) { menuOpen = true }
                ArtistMenu(artist, menuOpen, { menuOpen = false }, actions)
            }
        }
    }
}

/** Decode verified disk portraits directly; Coil's URL fetcher does not handle every Windows file URI. */
@Composable
internal fun FamiliarArtistPhoto(photo: String, name: String, modifier: Modifier = Modifier) {
    if (photo.startsWith("file:")) {
        val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, photo) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { javax.imageio.ImageIO.read(java.io.File(java.net.URI(photo)))?.toComposeImageBitmap() }.getOrNull()
            }
        }
        bitmap?.let { androidx.compose.foundation.Image(it, name, modifier,
            contentScale = ContentScale.Crop, alignment = PosterPhotoAlignment) }
    } else AsyncImage(photo, name, contentScale = ContentScale.Crop, alignment = PosterPhotoAlignment, modifier = modifier)
}

/**
 * A round, frosted button over the poster (Li Music's `bg-black/40 backdrop-blur-sm`): the same
 * photo, blurred and placed so it lines up with the poster underneath, under a 40% black tint.
 */
@Composable
private fun GlassButton(photo: String?, posterWidth: Dp, posterHeight: Dp, left: Dp, top: Dp, label: String,
    icon: ImageVector, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(modifier.size(GlassButtonSize).clip(CircleShape).hoverable(hover).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        // The photo pre-blurred once and shared by both buttons: blurring a whole poster-sized
        // copy per button on every frame cost a lot of GPU time with several posters on screen.
        val blurred by com.auralis.music.windows.player.rememberBlurredArtwork(photo, 6f / posterWidth.value.coerceAtLeast(1f))
        blurred?.let { picture ->
            androidx.compose.foundation.Image(picture, null, contentScale = ContentScale.Crop, alignment = PosterPhotoAlignment,
                modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(posterWidth, posterHeight).offset(-left, -top))
        }
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (hovered) .6f else .4f))
            .border(0.5.dp, Color.White.copy(alpha = .08f), CircleShape))
        Icon(icon, label, Modifier.size(18.dp), tint = if (hovered && tint != Color.White) tint.copy(alpha = 1f) else tint)
    }
}

/** The ⋯ menu: Start radio, Add to shortcuts, Share, Save to library, Block artist (Li Music's artist menu). */
@Composable
internal fun ArtistMenu(artist: FamiliarArtist, expanded: Boolean, dismiss: () -> Unit, actions: FamiliarArtistActions) {
    AnimatedMenu(expanded, dismiss) {
        @Composable
        fun item(text: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(
            text = { Text(text, fontSize = 14.sp, color = Color(0xFFE8E4EB)) },
            leadingIcon = { Icon(icon, null, Modifier.size(18.dp), tint = Color(0xFFBDB7C5)) },
            onClick = { dismiss(); action() },
            modifier = Modifier.height(38.dp))
        item("Start radio", Icons.Outlined.Radio) { actions.startRadio(artist) }
        if (actions.isShortcut(artist)) item("Remove from shortcuts", Icons.Outlined.DashboardCustomize) { actions.toggleShortcut(artist) }
        else item("Add to shortcuts", Icons.Outlined.Dashboard) { actions.toggleShortcut(artist) }
        item("Share", Icons.Outlined.Share) { actions.share(artist) }
        if (actions.inLibrary(artist)) item("Remove from library", Icons.Outlined.BookmarkRemove) { actions.toggleLibrary(artist) }
        else item("Save to library", Icons.Outlined.LibraryAdd) { actions.toggleLibrary(artist) }
        item("Block artist", Icons.Outlined.PersonOff) { actions.block(artist) }
    }
}

/**
 * Material's dropdown, but closing as it opens: Material 3 grows the menu in over 120ms and then
 * drops it with a 75ms fade, which reads as no closing animation at all. Here it shrinks back into
 * its corner by the anchor while fading, over the same time it took to open.
 */
@Composable
private fun AnimatedMenu(expanded: Boolean, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val visibility = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    var above by remember { mutableStateOf(false) }
    val provider = remember(margin) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize): androidx.compose.ui.unit.IntOffset {
                // Under the button and starting at its left edge (like Material's), above it if there's no room.
                val fitsBelow = anchorBounds.bottom + popupContentSize.height + margin <= windowSize.height
                above = !fitsBelow
                val x = anchorBounds.left.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val y = (if (fitsBelow) anchorBounds.bottom else anchorBounds.top - popupContentSize.height)
                    .coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    val origin = androidx.compose.ui.graphics.TransformOrigin(0f, if (above) 1f else 0f)
    androidx.compose.ui.window.Popup(popupPositionProvider = provider, onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = expanded)) {
        androidx.compose.animation.AnimatedVisibility(
            visibleState = visibility,
            enter = androidx.compose.animation.fadeIn(tween(90)) +
                androidx.compose.animation.scaleIn(tween(120, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
                    initialScale = .8f, transformOrigin = origin),
            exit = androidx.compose.animation.fadeOut(tween(120)) +
                androidx.compose.animation.scaleOut(tween(120, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                    targetScale = .8f, transformOrigin = origin)
        ) {
            // The same liquid glass as the song and playlist menus: the page behind, blurred and refracted.
            com.auralis.music.windows.LiquidPopupSurface(Modifier.padding(4.dp)) {
                Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp), content = content)
            }
        }
    }
}

/** Exact measured time wins; play count only breaks equal-time ties. */
internal fun rankFamiliarArtists(artists: List<FamiliarArtist>): List<FamiliarArtist> =
    artists.sortedWith(compareByDescending<FamiliarArtist> { it.listenedMs }.thenByDescending { it.totalPlays })

/** Apply the same priority before limiting candidates for portrait lookup. */
internal fun rankFamiliarArtistStats(stats: List<FamiliarArtistStats>): List<FamiliarArtistStats> =
    stats.sortedWith(compareByDescending<FamiliarArtistStats> { it.listenedMs }.thenByDescending { it.totalPlays })

/** "3h 12m" / "42m"; null under a minute. */
internal fun formatListened(ms: Long): String? {
    val minutes = ms / 60_000L
    if (minutes < 1) return null
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
