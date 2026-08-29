package com.auralis.music.ui.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.auralis.music.ui.components.tactileBounce

/**
 * Pixel-Perfect Fullscreen Voice & Ambient Music Recognition Screen.
 * 100% solid, non-transparent background matching the app's dynamic greenish/Material You palette.
 * Implements smooth concentric wave ripple animations, tactile listening orb,
 * and persistent recognition history bottom sheet.
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
    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onBackground = MaterialTheme.colorScheme.onBackground
    val onSurface = MaterialTheme.colorScheme.onSurface

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

    val isListening = state.status == RecognitionStatus.LISTENING
    val isProcessing = state.status == RecognitionStatus.PROCESSING
    val isSuccess = state.status == RecognitionStatus.SUCCESS
    val isError = state.status == RecognitionStatus.ERROR

    // Solid opaque root container: intercepts clicks and prevents any underlying screens from bleeding through
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {} // Consume all touches
            )
    ) {
        // Dynamic ambient radial glow synchronized with app theme centered on the orb
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val center = Offset(width * 0.5f, height * 0.50f)

            // Solid opaque base layer
            drawRect(color = backgroundColor)

            // Ambient theme glow behind orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.35f),
                        primaryColor.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = width * 0.75f
                ),
                center = center,
                radius = width * 0.75f
            )
        }

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP NAVIGATION BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(surfaceVariant)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onBackground,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Text(
                    text = if (state.mode == RecognitionMode.MUSIC_IDENTIFY) "Music Recognition" else "Speak to Search",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 18.sp
                )

                if (state.mode == RecognitionMode.MUSIC_IDENTIFY) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(surfaceVariant)
                            .clickable { showHistorySheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "History",
                            tint = onBackground,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(surfaceVariant)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = onBackground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ── CENTERED RECOGNITION CONTENT ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── SUBTITLE & STATUS ──
                Text(
                    text = if (!hasAudioPermission) "Microphone permission required"
                           else if (isListening) "Listening for music around you..."
                           else if (isProcessing) "Analyzing audio with Shazam..."
                           else if (isSuccess) state.statusMessage
                           else if (isError) state.statusMessage
                           else "Tap to listen",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isError) Color(0xFFFF8A80)
                            else if (isSuccess) primaryColor
                            else onBackground.copy(alpha = 0.70f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(28.dp))

                // ── CENTER CONCENTRIC WAVE RIPPLE LISTENING ORB ──
                AnimatedListeningOrb(
                    modifier = Modifier.size(260.dp),
                    isListening = isListening && hasAudioPermission,
                    isProcessing = isProcessing,
                    isError = isError,
                    isSuccess = isSuccess,
                    audioLevel = state.audioLevel,
                    primaryColor = primaryColor,
                    surfaceVariant = surfaceVariant,
                    onClick = {
                        if (!hasAudioPermission) {
                            requestAndStartListening()
                        } else if (isListening) {
                            onStopListening()
                        } else {
                            onStartListening()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(28.dp))

                // ── STATUS PILL (Matching Dynamic Theme) ──
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = primaryColor.copy(alpha = 0.16f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .clickable {
                            if (!hasAudioPermission) requestAndStartListening()
                            else if (isListening) onStopListening()
                            else onStartListening()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                isProcessing -> Icons.Default.Sync
                                isListening -> Icons.Default.GraphicEq
                                isSuccess -> Icons.Default.PlayArrow
                                isError -> Icons.Default.Refresh
                                else -> Icons.Default.Mic
                            },
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = when {
                                isProcessing -> "Processing..."
                                isListening -> "Listening..."
                                isSuccess -> "Song Identified"
                                isError -> "Try Again"
                                else -> "Tap to Listen"
                            },
                            color = primaryColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── ROTATING GEOMETRIC FLOWER / SPINNER INDICATOR ──
                if (isListening || isProcessing) {
                    val infinite = rememberInfiniteTransition(label = "flowerRotate")
                    val rotation by infinite.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(animation = tween(4000, easing = LinearEasing)),
                        label = "flowerRot"
                    )

                    Canvas(
                        modifier = Modifier
                            .size(34.dp)
                            .rotate(rotation)
                    ) {
                        val r = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.85f),
                            radius = r * 0.70f,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                        for (i in 0 until 6) {
                            val angle = (i * 60f) * (Math.PI / 180f).toFloat()
                            val petCenter = Offset(
                                center.x + kotlin.math.cos(angle) * (r * 0.45f),
                                center.y + kotlin.math.sin(angle) * (r * 0.45f)
                            )
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.40f),
                                radius = r * 0.35f,
                                center = petCenter,
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }

                // ── CANCEL / ACTION BUTTON ──
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, onBackground.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .clickable {
                            if (isListening) onStopListening()
                            onDismiss()
                        }
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isListening) "Cancel" else "Close",
                            color = onBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                // ── IDENTIFIED TRACK RESULT CARD ──
                if (isSuccess && state.identifiedTrack != null) {
                    val track = state.identifiedTrack
                    Spacer(modifier = Modifier.height(24.dp))

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPlayIdentifiedTrack(track)
                                    onDismiss()
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkCard(
                                url = track.thumbnail,
                                modifier = Modifier.size(64.dp),
                                cornerRadius = 12.dp,
                                contentDescription = track.title
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onBackground.copy(alpha = 0.70f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(primaryColor),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                // ── GOOGLE SOUND SEARCH FALLBACK ──
                if (isError && state.mode == RecognitionMode.MUSIC_IDENTIFY) {
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = {
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
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryColor),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Google Sound Search",
                            color = onBackground,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
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
                            text = "Recognition History",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = onBackground
                        )

                        if (historyItems.isNotEmpty()) {
                            TextButton(onClick = onClearHistory) {
                                Text("Clear All", color = Color(0xFFFF8A80))
                            }
                        }
                    }

                    if (historyItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search history...", color = onBackground.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = primaryColor) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = onBackground)
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
                                    text = if (searchQuery.isBlank()) "No recognition history yet" else "No matches found",
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
                                                contentDescription = "Play",
                                                tint = primaryColor,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { onRemoveHistoryItem(item.trackId) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete",
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

@Composable
private fun AnimatedListeningOrb(
    modifier: Modifier,
    isListening: Boolean,
    isProcessing: Boolean,
    isError: Boolean,
    isSuccess: Boolean,
    audioLevel: Float,
    primaryColor: Color,
    surfaceVariant: Color,
    onClick: () -> Unit
) {
    val ringProgress: Float
    val ringProgress2: Float

    if (isListening) {
        val infinite = rememberInfiniteTransition(label = "orbPulse")
        val animatedRingProgress by infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(1700, easing = LinearEasing)),
            label = "ring1"
        )
        val animatedRingProgress2 by infinite.animateFloat(
            initialValue = 0.25f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(animation = tween(2100, easing = LinearEasing)),
            label = "ring2"
        )
        ringProgress = animatedRingProgress
        ringProgress2 = animatedRingProgress2
    } else {
        ringProgress = 0f
        ringProgress2 = 0f
    }

    val orbScale by animateFloatAsState(
        targetValue = if (isListening) (1.0f + (audioLevel * 0.12f)).coerceIn(1.02f, 1.15f) else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "orbScale"
    )

    val baseColor = when {
        isProcessing -> primaryColor
        isListening -> primaryColor
        isError -> Color(0xFFFF8A80)
        isSuccess -> Color(0xFF81C784)
        else -> primaryColor
    }

    val containerBrush = remember(baseColor, isListening) {
        Brush.radialGradient(
            colors = listOf(
                baseColor.copy(alpha = if (isListening) 0.65f else 0.45f),
                baseColor.copy(alpha = if (isListening) 0.30f else 0.18f),
                Color(0xFF141712)
            )
        )
    }

    val density = LocalDensity.current
    val ringStrokeWidth = remember(density) { with(density) { 10.dp.toPx() } }
    val ringStroke = remember(ringStrokeWidth) { Stroke(width = ringStrokeWidth, cap = StrokeCap.Round) }

    Box(
        modifier = modifier
            .scale(orbScale)
            .clip(CircleShape)
            .background(containerBrush)
            .tactileBounce(scaleDown = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            if (isListening) {
                drawListeningRing(center, r, ringProgress, baseColor, ringStroke)
                drawListeningRing(center, r, ringProgress2, baseColor.copy(alpha = 0.85f), ringStroke)
            }

            val glow = baseColor.copy(alpha = if (isListening) 0.35f else 0.18f)
            drawCircle(glow, radius = r * 0.88f, center = center)
            drawCircle(baseColor.copy(alpha = 0.12f), radius = r * 0.76f, center = center)
        }

        val icon = when {
            isProcessing -> Icons.Default.Sync
            isListening -> Icons.Default.GraphicEq
            isError -> Icons.Default.Refresh
            isSuccess -> Icons.Default.PlayArrow
            else -> Icons.Default.Mic
        }

        val iconAlpha by animateFloatAsState(
            targetValue = if (isProcessing) 0.9f else 1f,
            animationSpec = tween(180),
            label = "iconAlpha"
        )

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(54.dp)
                .alpha(iconAlpha),
            tint = Color.White
        )
    }
}

private fun DrawScope.drawListeningRing(
    center: Offset,
    baseRadius: Float,
    progress: Float,
    color: Color,
    stroke: Stroke
) {
    val p = progress.coerceIn(0f, 1f)
    val radius = baseRadius * (0.55f + 0.60f * p)
    val alpha = (1f - p) * 0.70f
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = radius,
        center = center,
        style = stroke
    )
}
