package com.auralis.music.ui.screens

import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.StorageDataStore
import com.auralis.music.data.datastore.StorageSettings
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.util.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StorageSettingsScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { StorageDataStore(context) }
    val settings by dataStore.settingsFlow.collectAsState(initial = StorageSettings())

    var showClearDownloadsConfirm by remember { mutableStateOf(false) }

    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var songCacheBytes by remember { mutableLongStateOf(0L) }
    var imageCacheBytes by remember { mutableLongStateOf(0L) }

    val refreshStorageMetrics: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            val dBytes = StorageUtils.getDownloadedSongsSizeBytes()
            val sBytes = StorageUtils.getSongCacheSizeBytes(context)
            val iBytes = StorageUtils.getImageCacheSizeBytes(context)
            withContext(Dispatchers.Main) {
                downloadedBytes = dBytes
                songCacheBytes = sBytes
                imageCacheBytes = iBytes
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageMetrics()
    }

    val onBackground = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary

    // Song cache steps matching Metrolist: 512 MB, 1.1 GB, 2.2 GB, 5.5 GB, 11 GB, Unlimited (22 GB)
    val songCacheOptions = remember {
        listOf(
            512 to "512 MB",
            1100 to "1.1 GB",
            2200 to "2.2 GB",
            5500 to "5.5 GB",
            11000 to "11 GB",
            22000 to "Unlimited"
        )
    }

    // Image cache steps matching Metrolist: 128 MB, 256 MB, 537 MB, 1.0 GB, 2.0 GB
    val imageCacheOptions = remember {
        listOf(
            128 to "128 MB",
            256 to "256 MB",
            537 to "537 MB",
            1074 to "1.0 GB",
            2148 to "2.0 GB"
        )
    }

    val currentSongCacheIndex = remember(settings.maxSongCacheSizeMb) {
        val idx = songCacheOptions.indexOfFirst { it.first == settings.maxSongCacheSizeMb }
        if (idx >= 0) idx else 1 // Default 1.1 GB
    }

    val currentImageCacheIndex = remember(settings.maxImageCacheSizeMb) {
        val idx = imageCacheOptions.indexOfFirst { it.first == settings.maxImageCacheSizeMb }
        if (idx >= 0) idx else 2 // Default 537 MB
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── TOP APP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 21.sp
                )
            }

            // ── STORAGE SETTINGS LIST ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. STORAGE (DOWNLOADS)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Storage",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )

                        // Downloaded Songs
                        StorageActionCard(
                            icon = Icons.Default.Storage,
                            title = "Downloaded songs",
                            subtitle = StorageUtils.formatBytes(downloadedBytes),
                            onClick = {
                                refreshStorageMetrics()
                            }
                        )

                        // Clear all downloads
                        StorageActionCard(
                            icon = Icons.Default.DeleteOutline,
                            title = "Clear all downloads",
                            subtitle = null,
                            onClick = {
                                showClearDownloadsConfirm = true
                            }
                        )
                    }
                }

                // 2. SONG CACHE
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Song Cache",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )

                        // Enable song cache switch
                        StorageToggleCard(
                            icon = Icons.Default.Autorenew,
                            title = "Enable song cache",
                            subtitle = "Automatically cache songs for faster future playback",
                            checked = settings.songCacheEnabled,
                            onCheckedChange = { isChecked ->
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                scope.launch {
                                    dataStore.setSongCacheEnabled(isChecked)
                                }
                            }
                        )

                        // Max song cache size slider
                        AnimatedVisibility(visible = settings.songCacheEnabled) {
                            StorageStepSliderCard(
                                icon = Icons.Default.Autorenew,
                                title = "Max song cache size",
                                options = songCacheOptions,
                                currentStep = currentSongCacheIndex,
                                onStepChange = { newStep ->
                                    val sizeMb = songCacheOptions[newStep].first
                                    scope.launch(Dispatchers.IO) {
                                        dataStore.setMaxSongCacheSizeMb(sizeMb)
                                    }
                                },
                                usedBytes = songCacheBytes,
                                maxBytes = songCacheOptions[currentSongCacheIndex].first * 1024L * 1024L
                            )
                        }

                        // Clear song cache
                        StorageActionCard(
                            icon = Icons.Default.DeleteSweep,
                            title = "Clear song cache",
                            subtitle = null,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                scope.launch(Dispatchers.IO) {
                                    val freed = StorageUtils.clearSongCache(context)
                                    val freedStr = StorageUtils.formatBytes(freed)
                                    withContext(Dispatchers.Main) {
                                        refreshStorageMetrics()
                                        Toast.makeText(context, "Song cache cleared ($freedStr freed)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }

                // 3. IMAGE CACHE
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Image Cache",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )

                        // Max image cache size slider
                        StorageStepSliderCard(
                            icon = Icons.Default.ImageSearch,
                            title = "Max image cache size",
                            options = imageCacheOptions,
                            currentStep = currentImageCacheIndex,
                            onStepChange = { newStep ->
                                val sizeMb = imageCacheOptions[newStep].first
                                scope.launch(Dispatchers.IO) {
                                    dataStore.setMaxImageCacheSizeMb(sizeMb)
                                }
                            },
                            usedBytes = imageCacheBytes,
                            maxBytes = imageCacheOptions[currentImageCacheIndex].first * 1024L * 1024L
                        )

                        // Clear image cache
                        StorageActionCard(
                            icon = Icons.Default.DeleteSweep,
                            title = "Clear image cache",
                            subtitle = null,
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                scope.launch(Dispatchers.IO) {
                                    val freed = StorageUtils.clearImageCache(context)
                                    val freedStr = StorageUtils.formatBytes(freed)
                                    withContext(Dispatchers.Main) {
                                        refreshStorageMetrics()
                                        Toast.makeText(context, "Image cache cleared ($freedStr freed)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // ── CONFIRMATION DIALOG: CLEAR ALL DOWNLOADS ──
    if (showClearDownloadsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDownloadsConfirm = false },
            title = { Text("Clear all downloads?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete all downloaded songs from your device. You can download them again anytime.", fontSize = 14.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDownloadsConfirm = false
                        AuralisDownloadManager.clearAllDownloads()
                        refreshStorageMetrics()
                    }
                ) {
                    Text("Clear All", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDownloadsConfirm = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun StorageActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(16.dp)
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val iconBg = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun StorageToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val cardShape = RoundedCornerShape(16.dp)
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val iconBg = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardBg)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            thumbContent = if (checked) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                        tint = Color.Black
                    )
                }
            } else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun StorageStepSliderCard(
    icon: ImageVector,
    title: String,
    options: List<Pair<Int, String>>,
    currentStep: Int,
    onStepChange: (Int) -> Unit,
    usedBytes: Long,
    maxBytes: Long
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val cardShape = RoundedCornerShape(16.dp)
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val iconBg = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackBgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)

    val stepCount = options.size
    var localStep by remember(currentStep) { mutableIntStateOf(currentStep) }
    var isDragging by remember { mutableStateOf(false) }

    val stepFraction = if (stepCount > 1) localStep.toFloat() / (stepCount - 1).toFloat() else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = stepFraction,
        animationSpec = tween(durationMillis = if (isDragging) 40 else 180),
        label = "stepSliderFraction"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardBg)
            .padding(16.dp)
    ) {
        // Header Row: Icon + Title + Value Label
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = options.getOrNull(localStep)?.second ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── METROLIST STEP SEGMENTED SLIDER CANVAS (BUTTER-SMOOTH) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(stepCount) {
                    detectTapGestures { offset ->
                        val width = size.width.toFloat()
                        if (width > 0f && stepCount > 1) {
                            val rawFraction = (offset.x / width).coerceIn(0f, 1f)
                            val targetStep = kotlin.math.round(rawFraction * (stepCount - 1)).toInt().coerceIn(0, stepCount - 1)
                            if (targetStep != localStep) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                localStep = targetStep
                                onStepChange(targetStep)
                            }
                        }
                    }
                }
                .pointerInput(stepCount) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val width = size.width.toFloat()
                            if (width > 0f && stepCount > 1) {
                                val rawFraction = (offset.x / width).coerceIn(0f, 1f)
                                val targetStep = kotlin.math.round(rawFraction * (stepCount - 1)).toInt().coerceIn(0, stepCount - 1)
                                if (targetStep != localStep) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    localStep = targetStep
                                }
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            onStepChange(localStep)
                        },
                        onDragCancel = {
                            isDragging = false
                            onStepChange(localStep)
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val width = size.width.toFloat()
                            if (width > 0f && stepCount > 1) {
                                val rawFraction = (change.position.x / width).coerceIn(0f, 1f)
                                val targetStep = kotlin.math.round(rawFraction * (stepCount - 1)).toInt().coerceIn(0, stepCount - 1)
                                if (targetStep != localStep) {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    localStep = targetStep
                                }
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val centerY = h / 2f
                val trackHeightPx = with(density) { 16.dp.toPx() }
                val trackRadiusPx = with(density) { 8.dp.toPx() }
                val thumbWidthPx = with(density) { 5.dp.toPx() }
                val thumbHeightPx = with(density) { 26.dp.toPx() }
                val dotRadiusPx = with(density) { 2.2.dp.toPx() }

                // 1. Background Inactive Capsule Track
                drawRoundRect(
                    color = trackBgColor,
                    topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                    size = Size(w, trackHeightPx),
                    cornerRadius = CornerRadius(trackRadiusPx, trackRadiusPx)
                )

                // 2. Active Filled Capsule Track (Left)
                val activeWidth = (w * animatedFraction).coerceIn(0f, w)
                if (activeWidth > 0f) {
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(0f, centerY - trackHeightPx / 2f),
                        size = Size(activeWidth, trackHeightPx),
                        cornerRadius = CornerRadius(trackRadiusPx, trackRadiusPx)
                    )
                }

                // 3. Discrete Step Dots (●)
                if (stepCount > 1) {
                    for (i in 0 until stepCount) {
                        val dotX = (i.toFloat() / (stepCount - 1).toFloat()) * w
                        val isInsideActive = dotX <= activeWidth
                        drawCircle(
                            color = if (isInsideActive) Color.Black.copy(alpha = 0.65f) else primaryColor.copy(alpha = 0.85f),
                            radius = dotRadiusPx,
                            center = Offset(dotX.coerceIn(dotRadiusPx + 4f, w - dotRadiusPx - 4f), centerY)
                        )
                    }
                }

                // 4. Vertical Divider Thumb Bar
                val thumbX = (activeWidth - thumbWidthPx / 2f).coerceIn(0f, w - thumbWidthPx)
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(thumbX, centerY - thumbHeightPx / 2f),
                    size = Size(thumbWidthPx, thumbHeightPx),
                    cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── THIN REAL USAGE PROGRESS BAR & STATS TEXT ──
        val usageFraction = if (maxBytes > 0L) (usedBytes.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f) else 0f
        val usedFormatted = StorageUtils.formatBytes(usedBytes)
        val maxFormatted = StorageUtils.formatBytes(maxBytes)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(usageFraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(primaryColor.copy(alpha = 0.70f))
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$usedFormatted / $maxFormatted",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}
