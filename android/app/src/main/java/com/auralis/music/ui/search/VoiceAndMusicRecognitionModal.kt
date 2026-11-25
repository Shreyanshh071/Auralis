package com.auralis.music.ui.search

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recognition.RecognitionHistoryItem
import com.auralis.music.domain.recognition.RecognitionMode
import com.auralis.music.domain.recognition.RecognitionState
import com.auralis.music.domain.recognition.RecognitionStatus
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.rememberPageHeaderGlass
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.glass.FloatingPageButton
import com.auralis.music.ui.glass.LiquidGlassContext
import com.auralis.music.ui.glass.PageHeading
import com.auralis.music.ui.glass.glassBarTint
import com.auralis.music.ui.glass.glassContentColor
import com.auralis.music.ui.glass.glassSurfaceTint
import com.auralis.music.ui.glass.liquidGlass
import com.auralis.music.ui.glass.rememberGlassPress
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How long the identify pass records (AudioRecognitionManager stops at 6.2s). */
private const val CAPTURE_MILLIS = 6200

private val ErrorTone = Color(0xFFFF8A80)
private val SuccessTone = Color(0xFF81C784)

/**
 * Fullscreen voice search and ambient music recognition.
 *
 * Layout: the app's page heading (round back button, history across from it, large title), a
 * listening lens filling the middle, then one status card and a row of floating action capsules.
 * The lens carries everything the old pill and spinner repeated: a live spectrum ring driven by
 * the microphone level, an arc for the 6-second capture, and the result's artwork once found.
 *
 * Liquid glass: an animated aurora is recorded into this screen's own backdrop and the lens,
 * card, capsules and header buttons are glass over it, so the lens visibly bends the moving
 * light. Normal theme: the same pieces as solid tonal surfaces over a static glow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAndMusicRecognitionModal(
    state: RecognitionState,
    historyItems: List<RecognitionHistoryItem> = emptyList(),
    onModeSelect: (RecognitionMode) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onPlayIdentifiedTrack: (Track) -> Unit,
    onSearchQuery: (String) -> Unit,
    onClearHistory: () -> Unit = {},
    onRemoveHistoryItem: (String) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val primaryColor = MaterialTheme.dynamicPrimary
    val backgroundColor = MaterialTheme.dynamicBackground
    val surfaceColor = MaterialTheme.dynamicSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onBackground = MaterialTheme.dynamicOnBackground

    var showHistorySheet by remember { mutableStateOf(false) }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            onStartListening()
        }
    }

    fun requestAndStartListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            hasAudioPermission = true
            onStartListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        if (hasAudioPermission) {
            onStartListening()
        }
    }

    val isMusicMode = state.mode == RecognitionMode.MUSIC_IDENTIFY
    val isListening = state.status == RecognitionStatus.LISTENING
    val isProcessing = state.status == RecognitionStatus.PROCESSING
    val isSuccess = state.status == RecognitionStatus.SUCCESS
    val isError = state.status == RecognitionStatus.ERROR
    val isActive = (isListening && hasAudioPermission) || isProcessing

    // Automatically trigger search as soon as speech or a song is recognized without requiring manual clicks
    LaunchedEffect(isSuccess, state.recognizedText, state.identifiedTrack) {
        if (isSuccess) {
            val queryToSearch = when {
                state.identifiedTrack != null -> {
                    "${state.identifiedTrack.artist} ${state.identifiedTrack.title}".trim()
                }
                state.recognizedText.isNotBlank() -> {
                    state.recognizedText.trim().removeSurrounding("\"")
                }
                else -> ""
            }

            if (queryToSearch.isNotBlank()) {
                kotlinx.coroutines.delay(300)
                onSearchQuery(queryToSearch)
            }
        }
    }

    fun toggleListening() {
        when {
            !hasAudioPermission -> requestAndStartListening()
            isListening -> onStopListening()
            else -> onStartListening()
        }
    }

    fun openGoogleSoundSearch() {
        try {
            val intent = Intent("com.google.android.googlequicksearchbox.MUSIC_SEARCH").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            onDismiss()
        } catch (e: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/songsearch")).apply {
                    setPackage("com.google.android.googlequicksearchbox")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                onDismiss()
            } catch (e2: Exception) {}
        }
    }

    // One colour per state, shared by the aurora, lens, ring and card so they move together.
    val stateTone by animateColorAsState(
        targetValue = when {
            isError -> ErrorTone
            isSuccess -> SuccessTone
            else -> primaryColor
        },
        animationSpec = tween(420),
        label = "stateTone"
    )
    val secondTone = MaterialTheme.colorScheme.tertiary

    // Smoothed microphone level; the manager reports 0.15..1 every 40ms.
    val level = animateFloatAsState(
        targetValue = if (isListening && hasAudioPermission) state.audioLevel else 0f,
        animationSpec = tween(120),
        label = "micLevel"
    )

    // The 6-second capture, so the wait reads as progress instead of an open-ended spinner.
    val capture = remember { Animatable(0f) }
    LaunchedEffect(isListening, isMusicMode, hasAudioPermission) {
        if (isListening && isMusicMode && hasAudioPermission) {
            capture.snapTo(0f)
            capture.animateTo(1f, tween(CAPTURE_MILLIS, easing = LinearEasing))
        } else if (!isProcessing) {
            capture.snapTo(0f)
        }
    }

    // A clock for the moving pieces; it only runs while something is happening.
    val clock: State<Float> = if (isActive) {
        rememberInfiniteTransition(label = "recognitionClock").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
            label = "clock"
        )
    } else remember { mutableStateOf(0f) }

    val backdrop = rememberLayerBackdrop()
    val glass = rememberPageHeaderGlass(backdrop)

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lensBounds by remember { mutableStateOf(Rect.Zero) }

    // Solid opaque root container: intercepts clicks and prevents any underlying screens from bleeding through
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onGloballyPositioned { rootCoords = it }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // Consume all touches
            )
    ) {
        // ── SCENE: what the glass pieces see through. Static glow on the normal theme. ──
        RecognitionScene(
            modifier = Modifier
                .fillMaxSize()
                .then(if (glass != null) Modifier.layerBackdrop(backdrop) else Modifier),
            background = backgroundColor,
            tone = stateTone,
            secondTone = secondTone,
            lensBounds = { lensBounds },
            level = level,
            clock = clock,
            animated = glass != null
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── HEADING: the app's own page top, back on the left, history across from it ──
            PageHeading(
                title = if (isMusicMode) str(R.string.music_recognition) else str(R.string.speak_to_search_2),
                onBack = {
                    if (isListening) onStopListening()
                    onDismiss()
                },
                backdrop = backdrop
            ) {
                if (isMusicMode) {
                    FloatingPageButton(Icons.Default.History, str(R.string.history), backdrop) {
                        showHistorySheet = true
                    }
                } else {
                    FloatingPageButton(Icons.Default.Close, str(R.string.close), backdrop) {
                        if (isListening) onStopListening()
                        onDismiss()
                    }
                }
            }

            // ── LISTENING LENS ──
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                val side = minOf(maxWidth * 0.86f, maxHeight * 0.92f, 330.dp)
                ListeningLens(
                    modifier = Modifier
                        .size(side)
                        .onGloballyPositioned { coords ->
                            rootCoords?.takeIf { it.isAttached && coords.isAttached }?.let {
                                lensBounds = it.localBoundingBoxOf(coords, clipBounds = false)
                            }
                        },
                    glass = glass,
                    tone = stateTone,
                    level = level,
                    clock = clock,
                    capture = { capture.value },
                    showCapture = isMusicMode,
                    isListening = isListening && hasAudioPermission,
                    isProcessing = isProcessing,
                    icon = when {
                        !hasAudioPermission -> Icons.Default.Mic
                        isProcessing -> Icons.Default.Sync
                        isListening -> Icons.Default.GraphicEq
                        isError -> Icons.Default.Refresh
                        isSuccess -> Icons.Default.PlayArrow
                        else -> Icons.Default.Mic
                    },
                    artworkUrl = state.identifiedTrack?.thumbnail?.takeIf { isSuccess && it.isNotBlank() },
                    onClick = {
                        val track = state.identifiedTrack
                        if (isSuccess && track != null) {
                            onPlayIdentifiedTrack(track)
                            onDismiss()
                        } else toggleListening()
                    }
                )
            }

            // ── STATUS CARD ──
            val headline = when {
                !hasAudioPermission -> str(R.string.microphone_permission_required)
                isProcessing -> str(R.string.processing)
                isListening -> str(R.string.listening)
                isSuccess && state.identifiedTrack != null -> str(R.string.song_identified)
                isSuccess -> state.recognizedText.ifBlank { state.statusMessage }
                isError -> str(R.string.try_again)
                else -> str(R.string.tap_to_listen)
            }
            val detail = when {
                !hasAudioPermission -> null
                isProcessing -> if (isMusicMode) str(R.string.analyzing_audio_with_shazam) else state.statusMessage
                // Voice: the words heard so far, not a second "Listening…".
                isListening -> if (isMusicMode) str(R.string.listening_for_music_around_you) else state.recognizedText.ifBlank { null }
                isError -> state.statusMessage
                else -> null
            }

            StatusCard(
                glass = glass,
                tone = stateTone,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                val track = state.identifiedTrack
                if (isSuccess && track != null) {
                    IdentifiedTrackRow(
                        track = track,
                        tone = stateTone,
                        label = headline,
                        onPlay = {
                            onPlayIdentifiedTrack(track)
                            onDismiss()
                        }
                    )
                } else {
                    AnimatedContent(
                        targetState = headline to detail,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "statusText"
                    ) { (title, sub) ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isError) ErrorTone else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!sub.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = sub,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Recent finds, one tap from playing, instead of empty space under the button.
                if (isMusicMode && !isSuccess && historyItems.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    RecentFindsRow(
                        items = historyItems.take(5),
                        onOpenAll = { showHistorySheet = true },
                        onPlay = { item ->
                            onPlayIdentifiedTrack(item.toPlayableTrack())
                            onDismiss()
                        }
                    )
                }
            }

            // ── ACTION CAPSULES ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val cancel: () -> Unit = {
                    if (isListening) onStopListening()
                    onDismiss()
                }
                when {
                    isActive -> ActionCapsule(
                        str(R.string.cancel), Icons.Default.Close, glass, stateTone,
                        primary = false, onClick = cancel
                    )
                    isError -> {
                        ActionCapsule(
                            str(R.string.try_again), Icons.Default.Refresh, glass, primaryColor,
                            primary = true, onClick = { toggleListening() }
                        )
                        if (isMusicMode) ActionCapsule(
                            str(R.string.google_sound_search), Icons.Default.GraphicEq, glass, primaryColor,
                            primary = false, onClick = { openGoogleSoundSearch() }
                        ) else ActionCapsule(
                            str(R.string.close), Icons.Default.Close, glass, primaryColor,
                            primary = false, onClick = cancel
                        )
                    }
                    isSuccess -> ActionCapsule(
                        str(R.string.close), Icons.Default.Close, glass, stateTone,
                        primary = false, onClick = cancel
                    )
                    else -> {
                        ActionCapsule(
                            if (hasAudioPermission) str(R.string.tap_to_listen) else str(R.string.allow),
                            Icons.Default.Mic, glass, primaryColor,
                            primary = true, onClick = { toggleListening() }
                        )
                        ActionCapsule(
                            str(R.string.close), Icons.Default.Close, glass, primaryColor,
                            primary = false, onClick = cancel
                        )
                    }
                }
            }
        }

        // ── RECOGNITION HISTORY BOTTOM SHEET ──
        if (showHistorySheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            var searchQuery by remember { mutableStateOf("") }

            val filteredHistory = remember(historyItems, searchQuery) {
                if (searchQuery.isBlank()) historyItems
                else historyItems.filter {
                    it.title.contains(searchQuery, true) ||
                    it.artist.contains(searchQuery, true) ||
                    (it.album?.contains(searchQuery, true) == true)
                }
            }

            ModalBottomSheet(
                onDismissRequest = { showHistorySheet = false },
                sheetState = sheetState,
                containerColor = surfaceColor,
                dragHandle = null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = str(R.string.recognition_history),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = onBackground
                        )

                        if (historyItems.isNotEmpty()) {
                            TextButton(onClick = onClearHistory) {
                                Text(str(R.string.clear_all), color = Color(0xFFFF8A80))
                            }
                        }
                    }

                    if (historyItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(str(R.string.search_history_2), color = onBackground.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = primaryColor) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = str(R.string.clear), tint = onBackground)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = onBackground.copy(alpha = 0.2f),
                                focusedTextColor = onBackground,
                                unfocusedTextColor = onBackground
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (filteredHistory.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = onBackground.copy(alpha = 0.3f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = if (searchQuery.isBlank()) str(R.string.no_recognition_history_yet) else str(R.string.no_matches_found),
                                    color = onBackground.copy(alpha = 0.6f),
                                    fontSize = 16.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(filteredHistory, key = { it.trackId }) { item ->
                                val playableTrack = remember(item) {
                                    Track(
                                        id = item.trackId,
                                        title = item.title,
                                        artist = item.artist,
                                        thumbnail = item.coverArtHqUrl ?: item.coverArtUrl ?: "",
                                        duration = 200,
                                        source = com.auralis.music.domain.model.TrackSource.YOUTUBE
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onPlayIdentifiedTrack(playableTrack)
                                                showHistorySheet = false
                                                onDismiss()
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ArtworkCard(
                                            url = item.coverArtHqUrl ?: item.coverArtUrl,
                                            modifier = Modifier.size(52.dp),
                                            cornerRadius = 10.dp,
                                            contentDescription = item.title
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = onBackground,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = item.artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = onBackground.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            val relTime = DateUtils.getRelativeTimeSpanString(
                                                item.recognizedAtEpochMillis,
                                                System.currentTimeMillis(),
                                                DateUtils.MINUTE_IN_MILLIS
                                            )
                                            Text(
                                                text = relTime.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = primaryColor.copy(alpha = 0.8f)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                onPlayIdentifiedTrack(playableTrack)
                                                showHistorySheet = false
                                                onDismiss()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = str(R.string.play),
                                                tint = primaryColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { onRemoveHistoryItem(item.trackId) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = str(R.string.delete),
                                                tint = onBackground.copy(alpha = 0.4f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The lens disc's share of the lens area; the ring and capture arc live in the rest. */
private const val DISC_FRACTION = 0.64f

private fun RecognitionHistoryItem.toPlayableTrack() = Track(
    id = trackId,
    title = title,
    artist = artist,
    thumbnail = coverArtHqUrl ?: coverArtUrl ?: "",
    duration = 200,
    source = com.auralis.music.domain.model.TrackSource.YOUTUBE
)

private fun Color.readableOn(): Color = if (luminance() > 0.5f) Color.Black else Color.White

/**
 * The page behind everything: the app's corner glow, a halo where the lens sits that breathes
 * with the microphone, and (glass only) three slow lights orbiting under the lens for it to bend.
 */
@Composable
private fun RecognitionScene(
    modifier: Modifier,
    background: Color,
    tone: Color,
    secondTone: Color,
    lensBounds: () -> Rect,
    level: State<Float>,
    clock: State<Float>,
    animated: Boolean
) {
    Box(modifier.drawBehind {
        drawRect(background)
        drawRect(
            Brush.radialGradient(
                listOf(tone.copy(alpha = 0.20f), Color.Transparent),
                Offset(size.width * 0.12f, size.height * 0.10f),
                size.width * 0.95f
            )
        )
        drawRect(
            Brush.radialGradient(
                listOf(secondTone.copy(alpha = 0.10f), Color.Transparent),
                Offset(size.width * 0.95f, size.height * 0.80f),
                size.width * 1.1f
            )
        )
        val lens = lensBounds()
        if (lens.isEmpty) return@drawBehind
        val c = lens.center
        val r = lens.minDimension / 2f
        val lv = level.value
        val haloR = r * (1.05f + lv * 0.30f)
        drawCircle(
            Brush.radialGradient(
                listOf(tone.copy(alpha = 0.30f + lv * 0.18f), tone.copy(alpha = 0.08f), Color.Transparent),
                c,
                haloR
            ),
            haloR,
            c
        )
        if (animated) {
            val t = clock.value * 2f * PI.toFloat()
            for (i in 0 until 3) {
                val a = t * (if (i % 2 == 0) 1f else -1.3f) + i * 2.094f
                val orbit = r * (0.26f + 0.06f * i)
                val p = Offset(c.x + cos(a) * orbit, c.y + sin(a) * orbit * 0.85f)
                val light = if (i == 1) secondTone else tone
                val lr = r * (0.42f + lv * 0.20f)
                drawCircle(
                    Brush.radialGradient(listOf(light.copy(alpha = 0.60f), Color.Transparent), p, lr),
                    lr,
                    p
                )
            }
        }
    })
}

/**
 * The centre of the screen: a disc (glass or tonal) wrapped in a live spectrum ring and, for
 * music, an arc that fills over the 6-second capture. Shows the found song's artwork on success.
 */
@Composable
private fun ListeningLens(
    modifier: Modifier,
    glass: LiquidGlassContext?,
    tone: Color,
    level: State<Float>,
    clock: State<Float>,
    capture: () -> Float,
    showCapture: Boolean,
    isListening: Boolean,
    isProcessing: Boolean,
    icon: ImageVector,
    artworkUrl: String?,
    onClick: () -> Unit
) {
    val trackColor = glass?.let { glassContentColor(it.isDark) } ?: MaterialTheme.colorScheme.onSurface
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = size.minDimension / 2f
            val disc = outer * DISC_FRACTION
            val arcR = disc + 8.dp.toPx()
            val barStart = disc + 18.dp.toPx()
            val barMax = (outer - barStart - 2.dp.toPx()).coerceAtLeast(0f)
            val lv = level.value
            val t = clock.value
            val twoPi = 2f * PI.toFloat()

            if (showCapture) {
                val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(trackColor.copy(alpha = 0.10f), arcR, c, style = Stroke(3.dp.toPx()))
                val progress = if (isProcessing) 1f else capture()
                if (progress > 0f) drawArc(
                    color = tone,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(c.x - arcR, c.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = stroke
                )
            }

            val bars = 72
            val barWidth = 3.dp.toPx()
            val head = (t * 6f) % 1f
            for (i in 0 until bars) {
                val pos = i / bars.toFloat()
                val a = pos * twoPi - PI.toFloat() / 2f
                val frac = when {
                    isListening -> {
                        val n = (sin(i * 1.7f + t * twoPi * 7f) * sin(i * 0.63f - t * twoPi * 4f) + 1f) / 2f
                        0.08f + lv * (0.22f + 0.70f * n)
                    }
                    isProcessing -> {
                        val d = kotlin.math.abs(pos - head).let { min(it, 1f - it) }
                        0.08f + 0.55f * (1f - d * 7f).coerceAtLeast(0f)
                    }
                    else -> 0.05f
                }.coerceIn(0f, 1f)
                val length = barMax * frac
                val alpha = if (isListening || isProcessing) 0.30f + 0.65f * frac else 0.22f
                drawLine(
                    color = tone.copy(alpha = alpha.coerceAtMost(1f)),
                    start = Offset(c.x + cos(a) * barStart, c.y + sin(a) * barStart),
                    end = Offset(c.x + cos(a) * (barStart + length), c.y + sin(a) * (barStart + length)),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        val shape = CircleShape
        val discModifier = if (glass != null) {
            val press = rememberGlassPress()
            Modifier
                .fillMaxSize(DISC_FRACTION)
                .liquidGlass(
                    glass, shape,
                    tint = tone.copy(alpha = if (glass.isDark) 0.16f else 0.12f).compositeOver(glassBarTint(glass.isDark)),
                    press = press,
                    pressedScale = 1.05f,
                    blurRadius = 12.dp,
                    lensDepth = 30.dp
                )
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
        } else {
            Modifier
                .fillMaxSize(DISC_FRACTION)
                .clip(shape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            tone.copy(alpha = 0.40f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
                            tone.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
                .tactileBounce(scaleDown = 0.94f, onClick = onClick)
        }

        Box(discModifier, contentAlignment = Alignment.Center) {
            if (glass != null) {
                // Top sheen, like light catching the upper rim of a glass bead.
                Box(Modifier.fillMaxSize().clip(shape).drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (glass.isDark) 0.12f else 0.30f),
                            0.42f to Color.Transparent
                        )
                    )
                })
            }
            AnimatedContent(
                targetState = artworkUrl,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
                label = "lensContent"
            ) { url ->
                if (url != null) {
                    ArtworkCard(
                        url = url,
                        modifier = Modifier
                            .fillMaxSize(0.84f)
                            .clip(CircleShape),
                        cornerRadius = 400.dp,
                        contentDescription = null
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = trackColor,
                        modifier = Modifier
                            .size(52.dp)
                            .graphicsLayer {
                                // The glyph breathes with the room while listening.
                                val s = if (isListening) 1f + level.value * 0.18f else 1f
                                scaleX = s
                                scaleY = s
                            }
                    )
                }
            }
        }
    }
}

/** The one card under the lens: what is happening, and the song once it's found. */
@Composable
private fun StatusCard(
    glass: LiquidGlassContext?,
    tone: Color,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    val surface = if (glass != null) {
        modifier.liquidGlass(
            glass, shape,
            tint = glassSurfaceTint(glass.isDark).copy(alpha = if (glass.isDark) 0.30f else 0.48f),
            blurRadius = 20.dp,
            lensDepth = 8.dp,
            rimLens = true
        )
    } else {
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), shape)
    }
    Column(surface.padding(horizontal = 20.dp, vertical = 18.dp)) {
        // A short accent stroke ties the card to the lens colour (red on error, green when found).
        Box(
            Modifier
                .width(28.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(tone)
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun IdentifiedTrackRow(
    track: Track,
    tone: Color,
    label: String,
    onPlay: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ArtworkCard(
            url = track.thumbnail,
            modifier = Modifier.size(60.dp),
            cornerRadius = 14.dp,
            contentDescription = track.title
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = tone,
                maxLines = 1
            )
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(tone)
                .tactileBounce(scaleDown = 0.9f, onClick = onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow, str(R.string.play), tint = tone.readableOn(), modifier = Modifier.size(26.dp))
        }
    }
}

/** The latest finds as covers: tap one to play it, tap the label for the full history. */
@Composable
private fun RecentFindsRow(
    items: List<RecognitionHistoryItem>,
    onOpenAll: () -> Unit,
    onPlay: (RecognitionHistoryItem) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenAll)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = str(R.string.recognition_history),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                ArtworkCard(
                    url = item.coverArtHqUrl ?: item.coverArtUrl,
                    modifier = Modifier
                        .size(44.dp)
                        .tactileBounce(scaleDown = 0.9f) { onPlay(item) },
                    cornerRadius = 12.dp,
                    contentDescription = item.title
                )
            }
        }
    }
}

/** One floating capsule in the action row: glass over the scene, or a solid pill. */
@Composable
private fun RowScope.ActionCapsule(
    label: String,
    icon: ImageVector,
    glass: LiquidGlassContext?,
    tone: Color,
    primary: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .weight(1f)
        .height(52.dp)
    val surface = if (glass != null) {
        val press = rememberGlassPress()
        base
            .liquidGlass(
                glass, shape,
                tint = if (primary) tone.copy(alpha = if (glass.isDark) 0.30f else 0.24f).compositeOver(glassBarTint(glass.isDark))
                       else glassBarTint(glass.isDark),
                press = press,
                pressedScale = 1.04f,
                blurRadius = 14.dp,
                lensDepth = 14.dp
            )
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    } else {
        base
            .clip(shape)
            .background(if (primary) tone else MaterialTheme.colorScheme.surfaceContainerHighest)
            .tactileBounce(scaleDown = 0.95f, onClick = onClick)
    }
    val contentColor = glass?.let { glassContentColor(it.isDark) }
        ?: if (primary) tone.readableOn() else MaterialTheme.colorScheme.onSurface
    Row(
        surface.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
