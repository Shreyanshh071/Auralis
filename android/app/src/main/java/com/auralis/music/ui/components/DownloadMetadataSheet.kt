package com.auralis.music.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.data.download.DownloadMetadataChoice
import com.auralis.music.data.download.DownloadMetadataPrompt
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicPrimary

private val CheckRowShape = RoundedCornerShape(18.dp)
private val CheckMarkShape = RoundedCornerShape(7.dp)

/** Lives at the app root, so every download button shares one popup. */
@Composable
internal fun DownloadMetadataPromptHost() {
    DisposableEffect(Unit) {
        DownloadMetadataPrompt.attachHost()
        onDispose { DownloadMetadataPrompt.detachHost() }
    }
    val request by DownloadMetadataPrompt.request.collectAsState()
    request?.let { key(it) { DownloadMetadataSheet(it) } }
}

@Composable
private fun DownloadMetadataSheet(request: DownloadMetadataPrompt.Request) {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(request.initial) }
    var rememberChoices by remember { mutableStateOf(false) }

    AuralisOptionSheet(
        onDismiss = DownloadMetadataPrompt::cancel,
        title = {
            Column {
                Text(str(R.string.download_metadata_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    str(R.string.download_metadata_message),
                    color = LocalContentColor.current.copy(alpha = .68f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    ) { close ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetadataCheckRow(str(R.string.metadata_cover_artwork), str(R.string.metadata_cover_artwork_hint), choice.coverArt) {
                choice = choice.copy(coverArt = it)
            }
            MetadataCheckRow(str(R.string.metadata_lyrics), str(R.string.metadata_lyrics_hint), choice.lyrics) {
                choice = choice.copy(lyrics = it)
            }
        }
        Spacer(Modifier.height(8.dp))
        MetadataCheckRow(str(R.string.metadata_dont_ask_again), null, rememberChoices) { rememberChoices = it }
        Spacer(Modifier.height(14.dp))
        val foreground = LocalContentColor.current
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(foreground.copy(alpha = .10f))
                    .clickable { close(DownloadMetadataPrompt::cancel) },
                contentAlignment = Alignment.Center
            ) {
                Text(str(R.string.cancel), color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.dynamicPrimary)
                    .clickable {
                        val chosen: DownloadMetadataChoice = choice
                        val remembered = rememberChoices
                        close { DownloadMetadataPrompt.answer(context, request, chosen, remembered) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(str(R.string.download), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun MetadataCheckRow(label: String, hint: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val accent = MaterialTheme.dynamicPrimary
    val foreground = LocalContentColor.current
    val fill by animateColorAsState(if (checked) accent.copy(alpha = .2f) else Color.Transparent, label = "metadataFill")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CheckRowShape)
            .background(fill)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CheckMarkShape)
                .then(
                    if (checked) Modifier.background(accent)
                    else Modifier.border(1.5.dp, foreground.copy(alpha = .35f), CheckMarkShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = foreground, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, color = foreground.copy(alpha = .68f), fontSize = 12.5.sp)
        }
    }
}
