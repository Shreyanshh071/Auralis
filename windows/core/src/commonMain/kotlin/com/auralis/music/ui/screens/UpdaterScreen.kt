package com.auralis.music.ui.screens

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.Switch

import com.auralis.music.ui.i18n.Strings

import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import com.auralis.music.data.datastore.UpdaterDataStore
import com.auralis.music.data.datastore.UpdaterSettings
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import kotlinx.coroutines.launch
import com.auralis.music.ui.components.bottomChromePadding

@Composable
fun UpdaterScreen(
    onDismiss: () -> Unit
) {
    com.auralis.music.ui.components.PlatformBackHandler(enabled = true) {
        onDismiss()
    }

    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val scope = rememberCoroutineScope()
    val dataStore = com.auralis.music.data.datastore.LocalSettingsStores.current.updater
    val updater = com.auralis.music.ui.settings.LocalSettingsPlatform.current.updater
    val settings by dataStore.settingsFlow.collectAsState(initial = UpdaterSettings())

    val currentVersion = com.auralis.music.ui.settings.LocalSettingsPlatform.current.appVersion
    var showUpdateFlow by rememberSaveable { mutableStateOf(false) }
    val isChecking = updater?.isChecking?.collectAsState()?.value ?: false

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
                        contentDescription = str(Strings.back),
                        tint = onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = str(Strings.updater),
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
                contentPadding = bottomChromePadding(includeNavigationBar = false),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. CURRENT VERSION
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = str(Strings.current_version),
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
                                    text = str(Strings.version_x, currentVersion),
                                    color = onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = str(Strings.universal_foss),
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
                            text = str(Strings.update_settings),
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
                                    title = str(Strings.automatically_check_for_updates),
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
                                    title = str(Strings.enable_update_notifications),
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
                            text = str(Strings.check_for_updates_2),
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
                                    .clickable {
                                        if (updater != null) {
                                            showUpdateFlow = true
                                            updater.checkNow()
                                        } else {
                                            platformActions.showMessage(com.auralis.music.ui.settings.PlatformNotes.noInAppUpdates)
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
                                    text = if (isChecking) str(Strings.checking_for_updates) else str(Strings.check_for_updates_2),
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

        if (showUpdateFlow) {
            updater?.UpdateFlowOverlay(onDismiss = { showUpdateFlow = false })
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
