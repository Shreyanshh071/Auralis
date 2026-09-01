package com.auralis.music.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.UpdaterDataStore
import com.auralis.music.data.datastore.UpdaterSettings
import com.auralis.music.data.network.UpdateChecker
import com.auralis.music.data.network.UpdateInfo
import kotlinx.coroutines.launch

@Composable
fun UpdaterScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { UpdaterDataStore(context) }
    val settings by dataStore.settingsFlow.collectAsState(initial = UpdaterSettings())

    val currentVersion = remember { UpdateChecker.getCurrentVersion(context) }
    var isChecking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onBackground = MaterialTheme.colorScheme.onBackground

    Surface(
        modifier = Modifier.fillMaxSize(),
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
                    text = "Updater",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 21.sp
                )
            }

            // ── UPDATER SECTIONS LIST ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. CURRENT VERSION
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Current version",
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 18.dp)
                            ) {
                                Text(
                                    text = "Version: $currentVersion",
                                    color = onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "universal - FOSS",
                                    color = onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                // 2. UPDATE SETTINGS
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Update settings",
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
                                UpdaterSwitchRow(
                                    icon = Icons.Default.Update,
                                    title = "Automatically check for updates",
                                    checked = settings.autoCheckUpdates,
                                    onCheckedChange = { isChecked ->
                                        scope.launch { dataStore.setAutoCheckUpdates(isChecked) }
                                    }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )

                                UpdaterSwitchRow(
                                    icon = Icons.Default.NotificationsNone,
                                    title = "Enable update notifications",
                                    checked = settings.enableNotifications,
                                    onCheckedChange = { isChecked ->
                                        scope.launch { dataStore.setEnableNotifications(isChecked) }
                                    }
                                )
                            }
                        }
                    }
                }

                // 3. CHECK FOR UPDATES
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Check for updates",
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isChecking) {
                                        isChecking = true
                                        scope.launch {
                                            val info = UpdateChecker.checkForUpdates(context)
                                            isChecking = false
                                            updateResult = info
                                            if (info.hasUpdate) {
                                                showUpdateDialog = true
                                            } else {
                                                if (info.error != null) {
                                                    Toast.makeText(context, "Update check: ${info.error}", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Auralis is up to date (v$currentVersion)", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(primaryColor.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isChecking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = primaryColor
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = primaryColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Text(
                                    text = if (isChecking) "Checking for updates..." else "Check for updates",
                                    color = onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── UPDATE AVAILABLE DIALOG ──
        if (showUpdateDialog && updateResult != null) {
            val info = updateResult!!
            AlertDialog(
                onDismissRequest = {
                    if (!isDownloading) showUpdateDialog = false
                },
                containerColor = surfaceColor,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Version Available", fontWeight = FontWeight.Bold, color = onBackground)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Auralis v${info.latestVersion} is ready to install.",
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor,
                            fontSize = 14.5.sp
                        )
                        if (!info.releaseNotes.isNullOrBlank() && !isDownloading) {
                            Text(
                                text = info.releaseNotes,
                                color = onSurfaceVariant,
                                fontSize = 13.sp,
                                maxLines = 8
                            )
                        }

                        if (isDownloading) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = primaryColor,
                                    trackColor = primaryColor.copy(alpha = 0.2f)
                                )
                                Text(
                                    text = if (downloadProgress > 0f) "Downloading update... ${(downloadProgress * 100).toInt()}%" else "Starting download...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !isDownloading,
                        onClick = {
                            val apkUrl = info.downloadUrl
                            if (!apkUrl.isNullOrBlank() && apkUrl.endsWith(".apk", ignoreCase = true)) {
                                isDownloading = true
                                downloadProgress = 0f
                                scope.launch {
                                    val res = UpdateChecker.downloadAndInstallApk(
                                        context = context,
                                        downloadUrl = apkUrl,
                                        versionName = info.latestVersion,
                                        onProgress = { p -> downloadProgress = p }
                                    )
                                    isDownloading = false
                                    if (res.isSuccess) {
                                        showUpdateDialog = false
                                    } else {
                                        Toast.makeText(context, "Download failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                val url = info.htmlUrl ?: "https://github.com/Shreyanshh071/Auralis/releases"
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not open download link", Toast.LENGTH_SHORT).show()
                                }
                                showUpdateDialog = false
                            }
                        }
                    ) {
                        Text(if (isDownloading) "Downloading..." else "Update Now", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (!isDownloading) {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("Later", color = onSurfaceVariant)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun UpdaterSwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

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

        Text(
            text = title,
            color = onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )

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
