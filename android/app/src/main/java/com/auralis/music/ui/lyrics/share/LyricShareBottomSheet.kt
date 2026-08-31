package com.auralis.music.ui.lyrics.share

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.auralis.music.R
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.getHighResArtworkUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricShareBottomSheet(
    track: Track,
    initialLyricsText: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var lyricsText by remember { mutableStateOf(initialLyricsText) }
    var isEditingText by remember { mutableStateOf(false) }
    var editTextBuffer by remember { mutableStateOf(initialLyricsText) }

    var selectedStyle by remember { mutableStateOf(LyricCardStyle.BLUR) }

    // Curated color swatches matching modern music card aesthetic
    val backgroundColors = remember {
        listOf(
            Color(0xFF4F46E5), // Electric Indigo
            Color(0xFFBE185D), // Velvet Crimson / Rose
            Color(0xFF0F766E), // Deep Emerald Teal
            Color(0xFF1E3A8A), // Royal Midnight Blue
            Color(0xFF7C3AED), // Vivid Purple
            Color(0xFFE2C48D), // Warm Champagne Gold
            Color(0xFFB45309), // Amber Sunset
            Color(0xFF18181B)  // Obsidian Dark
        )
    }

    val textColors = remember {
        listOf(
            Color(0xFFFFFFFF), // Pure White
            Color(0xFFE2C48D), // Champagne Gold
            Color(0xFF64B5F6), // Sky Blue
            Color(0xFF00E676), // Vibrant Neon Green
            Color(0xFF121417), // Charcoal Black
            Color(0xFF4A90E2)  // Electric Blue
        )
    }

    val secondaryTextColors = remember {
        listOf(
            Color(0xB3FFFFFF), // Translucent White
            Color(0xFF8F9FB5), // Slate Silver
            Color(0xFFC4B290), // Muted Gold
            Color(0xFF757575), // Graphite Gray
            Color(0xFF00C853)  // Accent Green
        )
    }

    var selectedBgColor by remember { mutableStateOf(backgroundColors[0]) }
    var selectedTextColor by remember { mutableStateOf(textColors[0]) }
    var selectedSecondaryTextColor by remember { mutableStateOf(secondaryTextColors[0]) }

    var artworkBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // Load high-res artwork Bitmap for export and blur preview
    LaunchedEffect(track.thumbnail) {
        withContext(Dispatchers.IO) {
            try {
                val url = getHighResArtworkUrl(track.thumbnail) ?: track.thumbnail
                if (!url.isNullOrBlank()) {
                    val loader = context.imageLoader
                    val req = ImageRequest.Builder(context)
                        .data(url)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(req)
                    if (result is SuccessResult) {
                        artworkBitmap = result.drawable.toBitmap()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = Color(0xFF191714),
        contentColor = Color.White,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "Customize colors",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            // Segmented style picker
            Text(
                text = "Player background style",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF2C251F))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    LyricCardStyle.BLUR to "Blur",
                    LyricCardStyle.GRADIENT to "Gradient",
                    LyricCardStyle.SOLID to "Solid"
                ).forEach { (style, label) ->
                    val isSelected = selectedStyle == style
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Color(0xFF4A3E33) else Color.Transparent)
                            .clickable { selectedStyle = style }
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            ),
                            color = if (isSelected) Color(0xFFE5C89C) else Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── LIVE CARD PREVIEW ──
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(28.dp))
            ) {
                // Background Layer
                when (selectedStyle) {
                    LyricCardStyle.SOLID -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(selectedBgColor)
                        )
                    }
                    LyricCardStyle.BLUR -> {
                        if (track.thumbnail.isNotBlank()) {
                            AsyncImage(
                                model = track.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(16.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(selectedBgColor)
                            )
                        }
                        // Luminous ambient translucent scrim
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Black.copy(alpha = 0.06f),
                                            Color.Black.copy(alpha = 0.18f),
                                            Color.Black.copy(alpha = 0.38f)
                                        )
                                    )
                                )
                        )
                    }
                    LyricCardStyle.GRADIENT -> {
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(selectedBgColor.toArgb(), hsv)
                        val hue = hsv[0]
                        val sat = hsv[1].coerceAtLeast(0.70f)
                        val valBri = hsv[2].coerceIn(0.40f, 0.90f)

                        val topColor = Color(android.graphics.Color.HSVToColor(floatArrayOf((hue + 14f) % 360f, sat, (valBri * 1.12f).coerceIn(0.50f, 0.95f))))
                        val midColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, valBri)))
                        val botColor = Color(android.graphics.Color.HSVToColor(floatArrayOf((hue - 16f + 360f) % 360f, (sat * 1.1f).coerceIn(0.75f, 1.0f), (valBri * 0.32f).coerceIn(0.12f, 0.35f))))

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        0.0f to topColor,
                                        0.45f to midColor,
                                        1.0f to botColor
                                    )
                                )
                        )
                    }
                }

                // Card Content Overlay
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Header: Artwork + Title + Artist
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AsyncImage(
                            model = track.thumbnail,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(0.5.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
                                color = selectedTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = selectedSecondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Center: Editable Massive Punchy Lyric Text (Capped at 5 lines)
                    val cleanLines = lyricsText.lines().map { it.trim() }.filter { it.isNotBlank() }.take(5)
                    val lineCount = cleanLines.size
                    val dynamicSp = when {
                        lineCount <= 2 -> 34.sp
                        lineCount <= 4 -> 27.sp
                        else -> 22.sp
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clickable {
                                editTextBuffer = lyricsText
                                isEditingText = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cleanLines.joinToString("\n"),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = dynamicSp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.5).sp,
                                lineHeight = (dynamicSp.value * 1.24f).sp,
                                textAlign = TextAlign.Center,
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.35f),
                                    blurRadius = 6f
                                )
                            ),
                            color = selectedTextColor,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                    }

                    // Footer: Auralis Brand Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.ic_notification),
                                contentDescription = "Auralis Logo",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Auralis",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = selectedTextColor.copy(alpha = 0.90f)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // Tap to edit prompt hint
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    editTextBuffer = lyricsText
                                    isEditingText = true
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Lyric",
                                tint = selectedTextColor.copy(alpha = 0.90f),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Edit",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = selectedTextColor.copy(alpha = 0.90f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── COLOR SWATCHES ──
            // 1. Background Color Swatches
            Text(
                text = "Background color",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                backgroundColors.forEach { color ->
                    ColorSwatch(
                        color = color,
                        isSelected = selectedBgColor == color,
                        onClick = { selectedBgColor = color }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Text Color Swatches
            Text(
                text = "Text color",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                textColors.forEach { color ->
                    ColorSwatch(
                        color = color,
                        isSelected = selectedTextColor == color,
                        onClick = { selectedTextColor = color }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Secondary Text Color Swatches
            Text(
                text = "Secondary text color",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                secondaryTextColors.forEach { color ->
                    ColorSwatch(
                        color = color,
                        isSelected = selectedSecondaryTextColor == color,
                        onClick = { selectedSecondaryTextColor = color }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── ACTION BUTTONS: SAVE & SHARE ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Save Button
                OutlinedButton(
                    onClick = {
                        if (!isExporting) {
                            isExporting = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val cardBitmap = LyricCardRenderer.renderCard(
                                    context = context,
                                    trackTitle = track.title,
                                    artistName = track.artist,
                                    lyricsText = lyricsText,
                                    artworkBitmap = artworkBitmap,
                                    style = selectedStyle,
                                    backgroundColor = selectedBgColor.toArgb(),
                                    textColor = selectedTextColor.toArgb(),
                                    secondaryTextColor = selectedSecondaryTextColor.toArgb()
                                )
                                val uri = LyricCardRenderer.saveToGallery(context, cardBitmap, track.title)
                                cardBitmap.recycle()
                                withContext(Dispatchers.Main) {
                                    isExporting = false
                                    if (uri != null) {
                                        Toast.makeText(context, "Saved to Gallery! 📷", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFE2C48D)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Save to Gallery",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save", fontWeight = FontWeight.SemiBold)
                }

                // Share Button
                Button(
                    onClick = {
                        if (!isExporting) {
                            isExporting = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val cardBitmap = LyricCardRenderer.renderCard(
                                    context = context,
                                    trackTitle = track.title,
                                    artistName = track.artist,
                                    lyricsText = lyricsText,
                                    artworkBitmap = artworkBitmap,
                                    style = selectedStyle,
                                    backgroundColor = selectedBgColor.toArgb(),
                                    textColor = selectedTextColor.toArgb(),
                                    secondaryTextColor = selectedSecondaryTextColor.toArgb()
                                )
                                withContext(Dispatchers.Main) {
                                    LyricCardRenderer.shareImage(context, cardBitmap, track.title)
                                    isExporting = false
                                    onDismissRequest()
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE2C48D),
                        contentColor = Color(0xFF1D1712)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
    }

    // Edit Lyric Text Dialog
    if (isEditingText) {
        AlertDialog(
            onDismissRequest = { isEditingText = false },
            title = { Text("Edit Lyric Text") },
            text = {
                OutlinedTextField(
                    value = editTextBuffer,
                    onValueChange = { editTextBuffer = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    maxLines = 8
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        lyricsText = editTextBuffer
                        isEditingText = false
                    }
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { isEditingText = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) Color(0xFFE2C48D) else Color.White.copy(alpha = 0.2f),
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            val checkTint = if (color.luminance() > 0.5f) Color.Black else Color.White
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = checkTint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun Color.luminance(): Float {
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}
