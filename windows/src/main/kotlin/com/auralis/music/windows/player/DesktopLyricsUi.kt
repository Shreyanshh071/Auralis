package com.auralis.music.windows.player

import com.auralis.music.ui.components.TextButton
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.lyrics.DefaultLyricsUi
import com.auralis.music.ui.lyrics.LyricsUiPlatform

val DesktopLyricsUi = LyricsUiPlatform(
    roundedMouseHover = true,
    strings = DefaultLyricsUi.strings,
    backHandler = { enabled, onBack -> com.auralis.music.ui.components.PlatformBackHandler(enabled, onBack) },
    romanizedLine = { text, color, size, align, modifier ->
        val settings by com.auralis.music.data.datastore.ContentSettingsStore.current.collectAsState()
        if (settings.romanizationEnabled) {
            val romanized = remember(text, settings.romanizedScripts) { com.auralis.music.domain.lyrics.LyricsRomanizer.romanize(text, settings.romanizedScripts) }
            if (romanized != null) Text(romanized, color = color, fontSize = size, textAlign = align, modifier = modifier.fillMaxWidth().padding(top = 2.dp))
        }
    },
    shareSheet = { track, text, dismiss ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(onDismissRequest = dismiss, title = { Text("Share lyrics") },
            text = { Text("$text\n\n${track.title} — ${track.artist}") },
            confirmButton = { TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString("$text\n\n${track.title} — ${track.artist}")); dismiss() }) { Text("Copy text") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
    }
)
