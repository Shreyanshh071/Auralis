package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.auralis.music.data.datastore.PrivacyDataStore
import com.auralis.music.domain.model.PrivacySettings
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.launch

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

    SettingsPage(title = str(R.string.privacy), onBack = onDismiss) {
        // 1. LISTEN HISTORY
        item(key = "privacy_listen_history") {
            SettingsGroup(str(R.string.listen_history)) {
                SettingsSwitchRow(
                    icon = Icons.Default.History,
                    title = str(R.string.pause_listen_history),
                    checked = settings.pauseListenHistory,
                    onCheckedChange = { isChecked ->
                        scope.launch { dataStore.setPauseListenHistory(isChecked) }
                    },

                )
                SettingsRow(
                    icon = Icons.Default.HistoryToggleOff,
                    title = str(R.string.clear_listen_history),
                    onClick = { showClearHistoryConfirm = true },
                )
            }
        }

        // 2. SEARCH HISTORY
        item(key = "privacy_search_history") {
            SettingsGroup(str(R.string.search_history)) {
                SettingsSwitchRow(
                    icon = Icons.Default.SearchOff,
                    title = str(R.string.pause_search_history),
                    checked = settings.pauseSearchHistory,
                    onCheckedChange = { isChecked ->
                        scope.launch { dataStore.setPauseSearchHistory(isChecked) }
                    },

                )
                SettingsRow(
                    icon = Icons.Default.ClearAll,
                    title = str(R.string.clear_search_history),
                    onClick = { showClearSearchConfirm = true },
                )
            }
        }

        // 3. MISC
        item(key = "privacy_misc") {
            SettingsGroup(str(R.string.misc)) {
                SettingsSwitchRow(
                    icon = Icons.Default.PhonelinkLock,
                    title = str(R.string.disable_screenshot),
                    subtitle = str(R.string.when_this_option_is_on_screenshots_and_t),
                    checked = settings.disableScreenshot,
                    onCheckedChange = { isChecked ->
                        scope.launch { dataStore.setDisableScreenshot(isChecked) }
                    },

                )
            }
        }
    }

    // ── CONFIRMATION DIALOG: CLEAR LISTEN HISTORY ──
    if (showClearHistoryConfirm) {
        com.auralis.music.ui.components.AuralisConfirmDialog(
            title = str(R.string.clear_listen_history_2),
            message = str(R.string.all_your_listening_history_and_top_playe),
            confirmLabel = str(R.string.clear),
            onDismiss = { showClearHistoryConfirm = false },
            onConfirm = {
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
                    },
        )
    }

    // ── CONFIRMATION DIALOG: CLEAR SEARCH HISTORY ──
    if (showClearSearchConfirm) {
        com.auralis.music.ui.components.AuralisConfirmDialog(
            title = str(R.string.clear_search_history_2),
            message = str(R.string.all_previous_search_queries_and_recent_s),
            confirmLabel = str(R.string.clear),
            onDismiss = { showClearSearchConfirm = false },
            onConfirm = {
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
                    },
        )
    }

}

