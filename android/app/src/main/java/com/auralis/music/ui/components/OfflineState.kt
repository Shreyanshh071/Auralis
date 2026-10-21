package com.auralis.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicPrimary

/** Shown in place of any page that needs YouTube Music while there is no network. */
@Composable
fun OfflineState(
    modifier: Modifier = Modifier,
    onOpenDownloads: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.dynamicPrimary.copy(alpha = 0.7f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = str(R.string.you_are_offline),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.dynamicOnBackground
            )
            Text(
                text = str(R.string.offline_play_downloads_hint),
                fontSize = 13.sp,
                color = MaterialTheme.dynamicOnBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            if (onOpenDownloads != null) {
                FilledTonalButton(onClick = onOpenDownloads, modifier = Modifier.padding(top = 4.dp)) {
                    Text(str(R.string.offline_open_downloads))
                }
            }
        }
    }
}
