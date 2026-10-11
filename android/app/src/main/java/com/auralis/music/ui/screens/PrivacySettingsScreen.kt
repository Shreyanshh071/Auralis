package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.PrivacyDataStore
import com.auralis.music.domain.model.PrivacySettings
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.launch

import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.components.bottomChromePadding

@Composable
fun PrivacySettingsScreen(
    onDismiss: () -> Unit,
    historyRepository: HistoryRepository? = null,
    searchRepository: SearchRepository? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { PrivacyDataStore(context) }
    val settings by dataStore.settingsFlow.collectAsState(initial = PrivacySettings())

    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var showClearSearchConfirm by remember { mutableStateOf(false) }

    val primaryColor = MaterialTheme.dynamicPrimary
    val surfaceColor = MaterialTheme.dynamicSurface
    val onSurface = MaterialTheme.dynamicOnSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onBackground = MaterialTheme.dynamicOnBackground
    val backgroundColor = MaterialTheme.dynamicBackground

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
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
                        contentDescription = str(R.string.back),
                        tint = onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = str(R.string.privacy),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 21.sp
                )
            }

            // ── PRIVACY SETTINGS LIST ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = bottomChromePadding(includeNavigationBar = false),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. LISTEN HISTORY
                item(key = "privacy_listen_history") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = str(R.string.listen_history),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                PrivacySwitchRow(
                                    icon = Icons.Default.History,
                                    title = str(R.string.pause_listen_history),
                                    checked = settings.pauseListenHistory,
                                    onCheckedChange = { isChecked ->
                                        scope.launch { dataStore.setPauseListenHistory(isChecked) }
                                    },
                                    primaryColor = primaryColor,
                                    onSurface = onSurface,
                                    onSurfaceVariant = onSurfaceVariant
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )

                                PrivacyActionRow(
                                    icon = Icons.Default.HistoryToggleOff,
                                    title = str(R.string.clear_listen_history),
                                    onClick = { showClearHistoryConfirm = true },
                                    primaryColor = primaryColor,
                                    onSurface = onSurface
                                )
                            }
                        }
                    }
                }

                // 2. SEARCH HISTORY
                item(key = "privacy_search_history") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = str(R.string.search_history),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                PrivacySwitchRow(
                                    icon = Icons.Default.SearchOff,
                                    title = str(R.string.pause_search_history),
                                    checked = settings.pauseSearchHistory,
                                    onCheckedChange = { isChecked ->
                                        scope.launch { dataStore.setPauseSearchHistory(isChecked) }
                                    },
                                    primaryColor = primaryColor,
                                    onSurface = onSurface,
                                    onSurfaceVariant = onSurfaceVariant
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )

                                PrivacyActionRow(
                                    icon = Icons.Default.ClearAll,
                                    title = str(R.string.clear_search_history),
                                    onClick = { showClearSearchConfirm = true },
                                    primaryColor = primaryColor,
                                    onSurface = onSurface
                                )
                            }
                        }
                    }
                }

                // 3. MISC
                item(key = "privacy_misc") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = str(R.string.misc),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            PrivacySwitchRow(
                                icon = Icons.Default.PhonelinkLock,
                                title = str(R.string.disable_screenshot),
                                subtitle = str(R.string.when_this_option_is_on_screenshots_and_t),
                                checked = settings.disableScreenshot,
                                onCheckedChange = { isChecked ->
                                    scope.launch { dataStore.setDisableScreenshot(isChecked) }
                                },
                                primaryColor = primaryColor,
                                onSurface = onSurface,
                                onSurfaceVariant = onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // ── CONFIRMATION DIALOG: CLEAR LISTEN HISTORY ──
        if (showClearHistoryConfirm) {
            AlertDialog(
                onDismissRequest = { showClearHistoryConfirm = false },
                containerColor = surfaceColor,
                title = { Text(str(R.string.clear_listen_history_2), fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Text(
                        str(R.string.all_your_listening_history_and_top_playe),
                        color = onSurfaceVariant,
                        fontSize = 13.5.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val db = com.auralis.music.data.local.AuralisDatabase.getInstance(context)
                                    db.historyDao().clearHistory()
                                    db.playCountDao().clearPlayCounts()
                                    db.playbackEventDao().clearAllEvents()
                                    historyRepository?.clearHistory()
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, str(R.string.listen_history_cleared), Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, str(R.string.error_clearing_history_x, e.message), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            showClearHistoryConfirm = false
                        }
                    ) {
                        Text(str(R.string.clear), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearHistoryConfirm = false }) {
                        Text(str(R.string.cancel), color = onSurfaceVariant)
                    }
                }
            )
        }

        // ── CONFIRMATION DIALOG: CLEAR SEARCH HISTORY ──
        if (showClearSearchConfirm) {
            AlertDialog(
                onDismissRequest = { showClearSearchConfirm = false },
                containerColor = surfaceColor,
                title = { Text(str(R.string.clear_search_history_2), fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Text(
                        str(R.string.all_previous_search_queries_and_recent_s),
                        color = onSurfaceVariant,
                        fontSize = 13.5.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val db = com.auralis.music.data.local.AuralisDatabase.getInstance(context)
                                    db.searchHistoryDao().clearSearchHistory()
                                    searchRepository?.clearSearchHistory()
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, str(R.string.search_history_cleared), Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, str(R.string.error_clearing_search_history_x, e.message), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            showClearSearchConfirm = false
                        }
                    ) {
                        Text(str(R.string.clear), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearSearchConfirm = false }) {
                        Text(str(R.string.cancel), color = onSurfaceVariant)
                    }
                }
            )
        }
    }
}

@Composable
private fun PrivacySwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    primaryColor: Color = MaterialTheme.dynamicPrimary,
    onSurface: Color = MaterialTheme.dynamicOnSurface,
    onSurfaceVariant: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(primaryColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
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
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = primaryColor,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun PrivacyActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    primaryColor: Color = MaterialTheme.dynamicPrimary,
    onSurface: Color = MaterialTheme.dynamicOnSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(primaryColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = title,
            color = onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
