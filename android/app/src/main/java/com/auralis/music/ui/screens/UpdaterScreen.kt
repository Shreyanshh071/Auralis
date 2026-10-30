package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import com.auralis.music.data.datastore.UpdaterDataStore
import com.auralis.music.data.datastore.UpdaterSettings
import com.auralis.music.data.network.UpdateChecker
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import kotlinx.coroutines.launch

@Composable
fun UpdaterScreen(
    onDismiss: () -> Unit
) {
    androidx.activity.compose.BackHandler(enabled = true) {
        onDismiss()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { UpdaterDataStore(context) }
    val settings by dataStore.settingsFlow.collectAsState(initial = UpdaterSettings())

    val currentVersion = remember { UpdateChecker.getCurrentVersion(context) }
    var showUpdateFlow by rememberSaveable { mutableStateOf(false) }
    val updatePhase by UpdateFlow.phase.collectAsState()
    val isChecking = updatePhase == UpdatePhase.Checking

    val primaryColor = MaterialTheme.dynamicPrimary
    val onSurface = MaterialTheme.dynamicOnSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    SettingsPage(title = str(R.string.updater), onBack = onDismiss) {
        // 1. CURRENT VERSION
        item {
            SettingsGroup(str(R.string.current_version)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = str(R.string.version_x, currentVersion),
                        color = onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = str(R.string.universal_foss),
                        color = onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 2. UPDATE SETTINGS
        item {
            SettingsGroup(str(R.string.update_settings)) {
                SettingsSwitchRow(
                    icon = Icons.Default.Update,
                    title = str(R.string.automatically_check_for_updates),
                    checked = settings.autoCheckUpdates,
                    onCheckedChange = { isChecked ->
                        scope.launch { dataStore.setAutoCheckUpdates(isChecked) }
                    }
                )
                SettingsSwitchRow(
                    icon = Icons.Default.NotificationsNone,
                    title = str(R.string.enable_update_notifications),
                    checked = settings.enableNotifications,
                    onCheckedChange = { isChecked ->
                        scope.launch { dataStore.setEnableNotifications(isChecked) }
                    }
                )
            }
        }

        // 3. CHECK FOR UPDATES
        item {
            SettingsGroup(str(R.string.check_for_updates_2)) {
                SettingsRow(
                    icon = Icons.Default.Refresh,
                    title = if (isChecking) str(R.string.checking_for_updates) else str(R.string.check_for_updates_2),
                    busy = isChecking,
                    onClick = {
                        showUpdateFlow = true
                        UpdateFlow.open(context)
                    }
                )
            }
        }
    }

    if (showUpdateFlow) {
        UpdateFlowScreen(onDismiss = { showUpdateFlow = false })
    }

}

