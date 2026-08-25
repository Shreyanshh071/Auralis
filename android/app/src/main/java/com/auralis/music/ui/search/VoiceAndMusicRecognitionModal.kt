package com.auralis.music.ui.search

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recognition.RecognitionMode
import com.auralis.music.domain.recognition.RecognitionState
import com.auralis.music.domain.recognition.RecognitionStatus
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.tactileBounce

val RECOG_LIME = Color(0xFFD4E157)
val RECOG_OLIVE = Color(0xFF434A29)

/**
 * Pixel-Perfect Fullscreen Voice Search & Ambient Music Identification Modal.
 * Integrates Android's native runtime permission dialog for RECORD_AUDIO.
 */
@Composable
fun VoiceAndMusicRecognitionModal(
    state: RecognitionState,
    onModeSelect: (RecognitionMode) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onPlayIdentifiedTrack: (Track) -> Unit,
    onSearchQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Native Android Runtime Permission Launcher Popup
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasAudioPermission = isGranted
        if (isGranted) {
            onStartListening()
        }
    }

    fun requestAndStartListening() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = granted
        if (granted) {
            onStartListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-request permission and start listening upon opening
    LaunchedEffect(state.mode) {
        if (!hasAudioPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (state.status == RecognitionStatus.IDLE) {
            onStartListening()
        }
    }

    // Auto-search if voice recognized text
    LaunchedEffect(state.status, state.recognizedText) {
        if (state.mode == RecognitionMode.VOICE_SEARCH &&
            state.status == RecognitionStatus.SUCCESS &&
            state.recognizedText.isNotBlank()
        ) {
            onSearchQuery(state.recognizedText)
            onDismiss()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (state.status == RecognitionStatus.LISTENING) 1.25f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0C0D0A).copy(alpha = 0.98f))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP BAR: MODE SELECTOR & CLOSE BUTTON ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Segmented Pill (Voice Search vs Music Identify)
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF1B1D16))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (state.mode == RecognitionMode.VOICE_SEARCH) RECOG_LIME else Color.Transparent)
                            .clickable { onModeSelect(RecognitionMode.VOICE_SEARCH) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Voice Search",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (state.mode == RecognitionMode.VOICE_SEARCH) Color.Black else Color.White
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (state.mode == RecognitionMode.MUSIC_IDENTIFY) RECOG_LIME else Color.Transparent)
                            .clickable { onModeSelect(RecognitionMode.MUSIC_IDENTIFY) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Recognize Music",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (state.mode == RecognitionMode.MUSIC_IDENTIFY) Color.Black else Color.White
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // ── TITLE / STATUS TEXT ──
            Text(
                text = if (state.mode == RecognitionMode.VOICE_SEARCH) "Speak music name" else "Listening for music...",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 24.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (!hasAudioPermission) "Microphone permission required to listen" else state.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // ── CENTER ANIMATED PULSE RINGS & MICROPHONE/MUSIC BUTTON ──
            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer animated ripple ring
                if (state.status == RecognitionStatus.LISTENING && hasAudioPermission) {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(RECOG_LIME.copy(alpha = 0.10f))
                    )

                    Box(
                        modifier = Modifier
                            .size(170.dp)
                            .scale(pulseScale * 0.9f)
                            .clip(CircleShape)
                            .background(RECOG_LIME.copy(alpha = 0.18f))
                    )
                }

                // Center Tactile Floating Button
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(if (state.status == RecognitionStatus.LISTENING && hasAudioPermission) RECOG_LIME else RECOG_OLIVE)
                        .tactileBounce(scaleDown = 0.90f) {
                            if (!hasAudioPermission) {
                                requestAndStartListening()
                            } else if (state.status == RecognitionStatus.LISTENING) {
                                onStopListening()
                            } else {
                                onStartListening()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.status == RecognitionStatus.PROCESSING) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(42.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state.mode == RecognitionMode.VOICE_SEARCH) Icons.Default.Mic else Icons.Default.GraphicEq,
                            contentDescription = "Listen",
                            tint = if (state.status == RecognitionStatus.LISTENING && hasAudioPermission) Color.Black else Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            // Live recognized transcript text
            if (state.recognizedText.isNotBlank()) {
                Spacer(modifier = Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1B1D16))
                        .border(1.dp, RECOG_LIME.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "\"${state.recognizedText}\"",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RECOG_LIME,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── IDENTIFIED MUSIC RESULT CARD ──
            if (state.identifiedTrack != null) {
                val track = state.identifiedTrack
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1B1D16))
                        .border(1.dp, RECOG_LIME, RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkCard(
                            url = track.thumbnail,
                            modifier = Modifier.size(60.dp),
                            cornerRadius = 12.dp,
                            contentDescription = track.title
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = {
                                onPlayIdentifiedTrack(track)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RECOG_LIME),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Permission Request or Retry Button
            if (!hasAudioPermission) {
                Button(
                    onClick = { requestAndStartListening() },
                    colors = ButtonDefaults.buttonColors(containerColor = RECOG_LIME),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow Microphone Access", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))
            } else if (state.status == RecognitionStatus.ERROR) {
                OutlinedButton(
                    onClick = { requestAndStartListening() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RECOG_LIME),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = RECOG_LIME, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Tap to Retry", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
