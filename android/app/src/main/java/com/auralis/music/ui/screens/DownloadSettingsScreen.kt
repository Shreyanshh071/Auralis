package com.auralis.music.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.data.datastore.DownloadMetadataDataStore
import com.auralis.music.data.datastore.DownloadMetadataSettings
import com.auralis.music.data.download.DownloadMetadataChoice
import com.auralis.music.ui.i18n.str
import kotlinx.coroutines.launch

@Composable
fun DownloadSettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { DownloadMetadataDataStore(context) }
    val settings by dataStore.settingsFlow.collectAsState(initial = DownloadMetadataSettings())
    val update: (DownloadMetadataChoice) -> Unit = { choice -> scope.launch { dataStore.setChoice(choice) } }

    SettingsPage(title = str(R.string.downloads), onBack = onDismiss) {
        item(key = "group_metadata") {
            SettingsGroup(str(R.string.metadata)) {
                SettingsSwitchRow(Icons.Default.Image, str(R.string.metadata_cover_artwork), str(R.string.metadata_cover_artwork_hint), settings.choice.coverArt, { update(settings.choice.copy(coverArt = it)) })
                SettingsSwitchRow(Icons.Default.Lyrics, str(R.string.metadata_lyrics), str(R.string.metadata_lyrics_hint), settings.choice.lyrics, { update(settings.choice.copy(lyrics = it)) })
                SettingsSwitchRow(Icons.Default.QuestionMark, str(R.string.metadata_ask_every_download), str(R.string.metadata_ask_every_download_hint), settings.askEveryDownload, { ask ->
                    scope.launch { dataStore.setAskEveryDownload(ask) }
                })
            }
        }
        item(key = "metadata_note") {
            Text(
                str(R.string.metadata_settings_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        }
    }
}
