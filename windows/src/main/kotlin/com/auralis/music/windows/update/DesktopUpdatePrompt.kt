package com.auralis.music.windows.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.i18n.desktopText
import kotlinx.coroutines.launch

/** One line for Settings → About → Updates. */
internal fun updateStatusText(state: DesktopUpdater.State, platform: ReleasePlatform = DesktopUpdater.platform): String = when (state) {
    DesktopUpdater.State.Idle -> ""
    DesktopUpdater.State.Checking -> desktopText("Checking for updates…")
    is DesktopUpdater.State.UpToDate -> desktopText("You are running the latest release (%1\$s).", "${platform.label} ${state.installed}")
    is DesktopUpdater.State.Available -> desktopText("Published release: %1\$s · Installed: %2\$s",
        "${platform.label} ${state.release.displayVersion}", "${platform.label} ${com.auralis.music.windows.settings.DesktopReleaseCheck.version}")
    is DesktopUpdater.State.Downloading -> desktopText("Downloading Auralis %1\$s… %2\$s",
        state.release.displayVersion, "${if (state.total > 0) state.bytes * 100 / state.total else 0}%")
    is DesktopUpdater.State.Ready -> desktopText("Auralis %1\$s is ready. Restart Auralis to update.", state.release.displayVersion)
    // Carries versions, file names and installer codes: not catalog copy.
    is DesktopUpdater.State.Failed -> state.message
}

/**
 * A non-blocking card over the page: "Auralis <v> is ready — Restart to update" with the release
 * notes, a link to a release this copy can't install itself, or what the last update did.
 */
@Composable
internal fun DesktopUpdatePrompt(modifier: Modifier = Modifier) {
    val state by DesktopUpdater.state.collectAsState()
    val hidden by DesktopUpdater.promptHidden.collectAsState()
    val report by DesktopUpdater.report.collectAsState()
    val restarting by DesktopUpdater.restarting.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val ready = state as? DesktopUpdater.State.Ready
    val available = state as? DesktopUpdater.State.Available
    val failedReport = report?.takeIf { !it.succeeded }
    val visible = !hidden && (report != null || ready != null || available != null)
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 }) {
        Surface(shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .97f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(.75.dp, Color.White.copy(alpha = .15f)), shadowElevation = 10.dp,
            modifier = Modifier.widthIn(max = 380.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val version = ready?.release?.displayVersion ?: available?.release?.displayVersion ?: report?.version.orEmpty()
                val title = when {
                    failedReport != null -> desktopText("Auralis couldn't update to %1\$s", failedReport.version)
                    ready != null -> desktopText("Auralis %1\$s is ready", version)
                    available != null -> desktopText("Auralis %1\$s is available", version)
                    else -> desktopText("Auralis was updated to %1\$s", version)
                }
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val detail = when {
                    failedReport != null -> failedReport.message
                    ready != null -> desktopText("Restart to update. Your library and settings stay as they are.")
                    available != null -> desktopText(if (DesktopUpdater.platform == ReleasePlatform.MACOS)
                        "Download the new version from its release page." else "Download the installer from its release page.")
                    else -> ""
                }
                if (detail.isNotEmpty()) Text(detail, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (available != null && failedReport == null && DesktopUpdater.suggestMoveToApplications) {
                    Text(desktopText("Move Auralis to Applications to update automatically."),
                        fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val notes = remember(ready, available) {
                    DesktopReleases.plainNotes((ready?.release ?: available?.release)?.notes.orEmpty())
                }
                if (notes.isNotEmpty() && failedReport == null) {
                    Box(Modifier.heightIn(max = 150.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(notes, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .86f))
                    }
                }
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    when {
                        ready != null -> {
                            TextButton({ DesktopUpdater.hidePrompt() }, enabled = !restarting) { Text(desktopText("Later")) }
                            Button({ scope.launch { error = DesktopUpdater.restartToUpdate() } }, enabled = !restarting) {
                                Text(desktopText(when {
                                    restarting -> "Restarting…"
                                    failedReport != null -> "Try again"
                                    else -> "Restart to update"
                                }))
                            }
                        }
                        available != null -> {
                            TextButton({ DesktopUpdater.hidePrompt() }) { Text(desktopText("Later")) }
                            Button({
                                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(available.release.pageUrl)) }
                                DesktopUpdater.hidePrompt()
                            }) { Text(desktopText("View release")) }
                        }
                        else -> TextButton({ DesktopUpdater.dismissReport() }) { Text(desktopText("OK")) }
                    }
                }
            }
        }
    }
}
