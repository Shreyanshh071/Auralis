package com.auralis.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.data.network.AppRelease
import com.auralis.music.data.network.UpdateChecker
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary

/** What each version added, read from the notes of the app's GitHub releases. */
@Composable
fun ChangelogScreen(onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = true) { onDismiss() }

    val context = LocalContext.current
    val currentVersion = remember { UpdateChecker.getCurrentVersion(context) }
    var releases by remember { mutableStateOf<List<AppRelease>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var selectedVersion by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(attempt) {
        failed = false
        val list = UpdateChecker.fetchReleases(context)
        if (list.isEmpty()) failed = true else releases = list
    }

    val onSurface = MaterialTheme.dynamicOnSurface
    val primary = MaterialTheme.dynamicPrimary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = releases?.let { list ->
        list.firstOrNull { it.version == selectedVersion }
            ?: list.firstOrNull { it.version == currentVersion }
            ?: list.first()
    }

    SettingsPage(title = str(R.string.changelog), onBack = onDismiss) {
        item(key = "changelog_body") {
            val list = releases
            when {
                list != null && shown != null -> Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        list.forEach { release ->
                            val isShown = release.version == shown.version
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isShown) primary else onSurface.copy(alpha = .07f))
                                    .clickable { selectedVersion = release.version }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    "v${release.version}",
                                    color = if (isShown) MaterialTheme.colorScheme.onPrimary else onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Auralis v${shown.version}", color = onSurface, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(
                            shown.publishedAt.takeIf { it.isNotBlank() },
                            str(R.string.changelog_installed).takeIf { shown.version == currentVersion }
                        ).joinToString(" · "),
                        color = onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    ReleaseNotes(shown.notes, onSurface, primary)
                    Spacer(Modifier.height(24.dp))
                }
                failed -> Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(str(R.string.changelog_failed), color = onSurfaceVariant, fontSize = 14.sp)
                    TextButton(onClick = { attempt++ }) { Text(str(R.string.changelog_retry), color = primary) }
                }
                else -> Text(str(R.string.changelog_loading), color = onSurfaceVariant, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}
