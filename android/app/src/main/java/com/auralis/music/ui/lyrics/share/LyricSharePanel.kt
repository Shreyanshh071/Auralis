package com.auralis.music.ui.lyrics.share

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auralis.music.R
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.OutfitFontFamily
import com.auralis.music.ui.theme.PlusJakartaSansFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PanelColor = Color(0xFF1A1416)

/**
 * "Share lyrics" panel, docked to the bottom of the screen while lines are selected.
 *
 * It is a non-focusable popup rather than a modal sheet, so the lyrics above stay live: tapping
 * more lines adds them and the card preview follows. Back, swiping the panel down, or clearing
 * the selection closes it.
 *
 * @param onHeightChanged panel height in px (0 when hidden), so the lyrics list can pad itself
 *   and keep the selected lines scrollable above the panel.
 */
@Composable
fun LyricSharePanel(
    visible: Boolean,
    track: Track?,
    lines: List<String>,
    onDismiss: () -> Unit,
    onHeightChanged: (Int) -> Unit = {}
) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible && track != null && lines.isNotEmpty()

    // Keep the last content during the exit animation, after the selection has already cleared.
    var shownTrack by remember { mutableStateOf(track) }
    var shownLines by remember { mutableStateOf(lines) }
    if (track != null && lines.isNotEmpty() && (shownTrack != track || shownLines != lines)) {
        shownTrack = track
        shownLines = lines
    }

    val latestOnHeightChanged by rememberUpdatedState(onHeightChanged)
    LaunchedEffect(transition.targetState) {
        if (!transition.targetState) latestOnHeightChanged(0)
    }

    val sheetTrack = shownTrack
    if (sheetTrack == null || (!transition.currentState && !transition.targetState)) return

    val view = LocalView.current
    val density = LocalDensity.current
    val screenHeightPx = view.rootView.height
    val screenWidth = with(density) { view.rootView.width.toDp() }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val positionProvider = remember(screenHeightPx) { ScreenBottomPosition(screenHeightPx) }

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        AnimatedVisibility(
            visibleState = transition,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(220))
        ) {
            SharePanelContent(
                track = sheetTrack,
                lines = shownLines,
                bottomInset = navBottom,
                onDismiss = onDismiss,
                modifier = Modifier
                    .width(screenWidth)
                    .onSizeChanged { if (transition.targetState) latestOnHeightChanged(it.height) }
            )
        }
    }
}

@Composable
private fun SharePanelContent(
    track: Track,
    lines: List<String>,
    bottomInset: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    val fontResolver = LocalFontFamilyResolver.current
    val lyricTypeface by remember(fontResolver) { fontResolver.resolveAsTypeface(OutfitFontFamily, FontWeight.SemiBold) }
    val titleTypeface by remember(fontResolver) { fontResolver.resolveAsTypeface(OutfitFontFamily, FontWeight.Bold) }
    val bodyTypeface by remember(fontResolver) { fontResolver.resolveAsTypeface(PlusJakartaSansFontFamily, FontWeight.Medium) }

    var artwork by remember(track.id) { mutableStateOf<Bitmap?>(null) }
    var blurredArtwork by remember(track.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(track.id, track.thumbnail) {
        withContext(Dispatchers.IO) {
            try {
                val url = getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail
                if (url.isNotBlank()) {
                    val result = context.imageLoader.execute(
                        ImageRequest.Builder(context).data(url).allowHardware(false).build()
                    )
                    if (result is SuccessResult) {
                        val bmp = result.drawable.toBitmap()
                        blurredArtwork = LyricCardRenderer.prepareBackground(bmp)
                        artwork = bmp
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun render(): Bitmap = LyricCardRenderer.renderCard(
        context = context,
        trackTitle = track.title,
        artistName = track.artist,
        lines = lines,
        artworkBitmap = artwork,
        blurredBackground = blurredArtwork,
        lyricTypeface = lyricTypeface,
        titleTypeface = titleTypeface,
        bodyTypeface = bodyTypeface
    )

    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(lines, artwork, blurredArtwork, lyricTypeface, titleTypeface, bodyTypeface) {
        preview = withContext(Dispatchers.Default) { render() }
    }

    var busy by remember { mutableStateOf(false) }
    val dragOffset = remember { Animatable(0f) }
    val dismissThreshold = with(density) { 96.dp.toPx() }

    Column(
        modifier = modifier
            .graphicsLayer { translationY = dragOffset.value }
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(PanelColor)
            // Swallow taps on the panel's empty areas so they never reach the lyrics behind.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f)) }
                },
                onDragStopped = { velocity ->
                    if (dragOffset.value > dismissThreshold || velocity > 1800f) {
                        onDismiss()
                        dragOffset.snapTo(0f)
                    } else {
                        dragOffset.animateTo(0f)
                    }
                }
            )
            .padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.28f))
        )
        Text(
            text = str(R.string.share_lyrics),
            color = Color.White.copy(alpha = 0.92f),
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 18.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(220)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = preview
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = lines.joinToString("\n"),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bmp.width.toFloat() / bmp.height)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PanelButton(
                label = str(R.string.save),
                icon = Icons.Outlined.FileDownload,
                container = Color.White.copy(alpha = 0.09f),
                content = accent,
                busy = false,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (busy) return@PanelButton
                    busy = true
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) {
                            val card = render()
                            LyricCardRenderer.saveToGallery(context, card, track.title).also { card.recycle() }
                        }
                        busy = false
                        Toast.makeText(
                            context,
                            str(if (uri != null) R.string.saved_to_gallery else R.string.failed_to_save_image),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            PanelButton(
                label = str(R.string.share),
                icon = Icons.Outlined.Share,
                container = accent,
                content = onAccent,
                busy = busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (busy) return@PanelButton
                    busy = true
                    scope.launch {
                        val card = withContext(Dispatchers.Default) { render() }
                        try {
                            withContext(Dispatchers.IO) {
                                LyricCardRenderer.shareImage(context, card, track.title, track.artist)
                            }
                        } catch (_: Exception) {
                            Toast.makeText(context, str(R.string.failed_to_save_image), Toast.LENGTH_SHORT).show()
                        } finally {
                            card.recycle()
                            busy = false
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PanelButton(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = content, fontFamily = OutfitFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** Pins the popup to the bottom edge of the (edge-to-edge) activity window. */
private class ScreenBottomPosition(private val screenHeightPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val height = if (screenHeightPx > 0) screenHeightPx else windowSize.height
        return IntOffset(0, height - popupContentSize.height)
    }
}
