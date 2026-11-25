package com.auralis.music.windows.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.ui.i18n.desktopText
import com.auralis.music.windows.search.DesktopArtwork
import com.auralis.music.windows.update.DesktopRelease
import com.auralis.music.windows.update.DesktopUpdater
import com.auralis.music.windows.update.ReleasePlatform
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

// The phone's About and Updater pages (android AboutScreen, UpdaterScreen, UpdateFlowScreen), on the desktop.

private const val AURALIS_WEBSITE_URL = "https://auralis-self-nu.vercel.app/"
private const val DEVELOPER_GITHUB_URL = "https://github.com/Shreyanshh071"
private const val DEVELOPER_AVATAR_URL = "https://avatars.githubusercontent.com/u/319473344?v=4"
private const val BUY_ME_A_CHAI_URL = "https://buymeachai.in/shreyanshh071"
private const val DEVELOPER_DISCORD_URL = "https://discordapp.com/users/1494604092083802263"
private const val DEVELOPER_DISCORD_NAME = "shreyanshh12_3"

/** GitHub login and, when set, a display name; the avatar and link come from the login. */
private data class Contributor(val login: String, val name: String? = null)

private val CONTRIBUTORS = listOf(
    Contributor("IshaanThakur09", "Ishaan Thakur"),
    Contributor("sharthak-sev"),
    Contributor("blitzuu"),
)

private val isMacOs = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** The phone's update switches, kept for the desktop updater. */
internal object UpdatePreferences {
    var autoCheck: Boolean
        get() = DesktopPreferences.store.bool("updateAutoCheck", default = true)
        set(value) = DesktopPreferences.store.set("updateAutoCheck", value)
    var prompts: Boolean
        get() = DesktopPreferences.store.bool("updatePrompts", default = true)
        set(value) = DesktopPreferences.store.set("updatePrompts", value)
}

private fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

// ─────────────────────────────── About ───────────────────────────────

@Composable
internal fun AboutPane(onCheckForUpdates: () -> Unit) {
    val onBackground = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var message by remember { mutableStateOf<String?>(null) }

    // ── Hero: logo, name, version pills, links and the three actions ──
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(118.dp).clip(CircleShape).background(Color(0xFF141414))
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource("auralis_logo.png"), desktopText("Auralis logo"), Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(16.dp))
        Text("Auralis", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = onBackground)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VersionPill(DesktopReleaseCheck.version)
            VersionPill(if (isMacOs) "MACOS" else "WINDOWS")
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            SocialIcon(GitHubIcon, desktopText("GitHub"), 22) { openUrl(DesktopReleaseCheck.REPOSITORY) }
            SocialIcon(Icons.Outlined.Language, desktopText("Website"), 24) { openUrl(AURALIS_WEBSITE_URL) }
            SocialIcon(Icons.Outlined.LocalCafe, desktopText("Buy Me a Chai"), 24) { openUrl(BUY_ME_A_CHAI_URL) }
        }
        Spacer(Modifier.height(22.dp))
        // Three action tiles side by side: an icon bead, what it does, and a line of context.
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2_500); copied = false } }
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(Icons.Outlined.LocalCafe, desktopText("Buy Me a Chai"), desktopText("Support Auralis"),
                    Modifier.weight(1f).fillMaxHeight(), accent = true) { openUrl(BUY_ME_A_CHAI_URL) }
                ActionTile(Icons.Outlined.SystemUpdateAlt, desktopText("Check for Updates"),
                    desktopText("Version %1\$s", DesktopReleaseCheck.version), Modifier.weight(1f).fillMaxHeight(), onClick = onCheckForUpdates)
                ActionTile(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, desktopText("Copy diagnostics"),
                    desktopText(if (copied) "Copied to clipboard" else "For bug reports"), Modifier.weight(1f).fillMaxHeight()) {
                    clipboard.setText(AnnotatedString(DesktopReleaseCheck.diagnostics()))
                    copied = true
                }
            }
            message?.let {
                Text(it, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }

    // ── Lead developer ──
    AboutSection("Lead Developer") {
        PersonCard(
            avatar = DEVELOPER_AVATAR_URL,
            name = "Shreyansh",
            role = desktopText("Creator & Lead Developer"),
            modifier = Modifier.fillMaxWidth()
        ) {
            CircleLink(GitHubIcon, 19) { openUrl(DEVELOPER_GITHUB_URL) }
            CircleLink(Icons.Outlined.Language, 20) { openUrl(AURALIS_WEBSITE_URL) }
            CircleLink(DiscordIcon, 19) {
                clipboard.setText(AnnotatedString(DEVELOPER_DISCORD_NAME))
                message = desktopText("Discord user: %1\$s copied to clipboard", DEVELOPER_DISCORD_NAME)
                openUrl(DEVELOPER_DISCORD_URL)
            }
        }
    }

    // ── Contributors: the phone's cards, side by side on the wider desktop pane ──
    AboutSection("Contributors") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            CONTRIBUTORS.forEach { person ->
                PersonCard(
                    avatar = "https://github.com/${person.login}.png",
                    name = person.name ?: person.login,
                    role = desktopText("Contributor"),
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    CircleLink(GitHubIcon, 19) { openUrl("https://github.com/${person.login}") }
                }
            }
        }
    }

    // Attribution the desktop's lyrics animation owes.
    AboutSection("Credits") {
        // The project the lyrics animation is inspired by, as a card like the people above it.
        val spicyRepo = "https://github.com/Spikerko/spicy-lyrics"
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { openUrl(spicyRepo) }
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .8f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) { DesktopArtwork("https://github.com/Spikerko.png", Modifier.fillMaxSize(), 18.dp, "Spicy Lyrics") }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Spicy Lyrics", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = onBackground)
                    Text("Spikerko/spicy-lyrics", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    Text(desktopText("Animation inspired by Spicy Lyrics"), fontSize = 13.sp, color = muted)
                }
                Spacer(Modifier.width(12.dp))
                CircleLink(GitHubIcon, 19) { openUrl(spicyRepo) }
            }
        }
        Text(desktopText("Lyrics providers and community contributors are credited alongside the lyrics they supply."),
            Modifier.padding(horizontal = 4.dp), color = muted, fontSize = 13.sp)
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().settingJumpTarget(title, group = true), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(desktopText(title), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.width(12.dp))
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
        }
        content()
    }
}

@Composable
private fun PersonCard(avatar: String, name: String, role: String, modifier: Modifier, links: @Composable RowScope.() -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, primary.copy(alpha = .8f), CircleShape),
                contentAlignment = Alignment.Center
            ) { DesktopArtwork(avatar, Modifier.fillMaxSize(), 36.dp, name) }
            Spacer(Modifier.height(14.dp))
            Text(name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(role, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f).heightIn(min = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, content = links)
        }
    }
}

@Composable
private fun VersionPill(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SocialIcon(icon: ImageVector, description: String, size: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp).pointerHoverIcon(PointerIcon.Hand)) {
        Icon(icon, description, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f), modifier = Modifier.size(size.dp))
    }
}

@Composable
private fun CircleLink(icon: ImageVector, size: Int, onClick: () -> Unit) {
    Surface(
        shape = CircleShape, color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
        modifier = Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f), modifier = Modifier.size(size.dp))
        }
    }
}

/**
 * A tall action tile: an icon in a tinted circle over a title and a muted line. [accent] tints the
 * tile with the theme colour so the one ask that matters (support) leads the row.
 */
@Composable
private fun ActionTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val primary = MaterialTheme.colorScheme.primary
    val base = if (accent) primary.copy(alpha = .16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
    val fill by androidx.compose.animation.animateColorAsState(
        if (hovered) (if (accent) primary.copy(alpha = .24f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .11f)) else base,
        tween(140), label = "tileFill")
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "tileScale")
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, if (accent) primary.copy(alpha = .28f) else Color.White.copy(alpha = .06f), shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(primary.copy(alpha = if (accent) .30f else .16f)),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (accent) Color.White else primary)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun PillOutlinedButton(text: String, modifier: Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.height(46.dp).pointerHoverIcon(PointerIcon.Hand)
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────────── Updates ───────────────────────────────

@Composable
internal fun UpdatesPane() {
    val scope = rememberCoroutineScope()
    val version = DesktopReleaseCheck.version
    val update by DesktopUpdater.state.collectAsState()
    val latest by DesktopUpdater.latestRelease.collectAsState()
    val restarting by DesktopUpdater.restarting.collectAsState()
    val revision by DesktopPreferences.store.changes.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val onBackground = MaterialTheme.colorScheme.onSurface
    val muted = onBackground.copy(alpha = .66f)
    val check: () -> Unit = { message = null; scope.launch { DesktopUpdater.check(manual = true) } }

    // Like the phone's updater: opening it checks, unless a check, download or result is already here.
    LaunchedEffect(Unit) { if (update == DesktopUpdater.State.Idle) DesktopUpdater.check(manual = true) }

    val release: DesktopRelease? = when (val s = update) {
        is DesktopUpdater.State.Available -> s.release
        is DesktopUpdater.State.Downloading -> s.release
        is DesktopUpdater.State.Ready -> s.release
        else -> null
    }

    // ── One status card: an orb for the state, what it means, the versions, and the action on the right ──
    val checking = update == DesktopUpdater.State.Idle || update == DesktopUpdater.State.Checking
    Surface(
        shape = RoundedCornerShape(24.dp), color = onBackground.copy(alpha = .07f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .06f)),
        modifier = Modifier.fillMaxWidth().settingJumpTarget("Current version", group = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UpdateOrb(update, checking, primary)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = updateTitle(update),
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "updateTitle"
                    ) { title -> Text(title, color = onBackground, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (val s = update) {
                            DesktopUpdater.State.Idle, DesktopUpdater.State.Checking -> desktopText("Please wait while we check for the latest features.")
                            is DesktopUpdater.State.UpToDate -> desktopText("You're on the latest version, v%1\$s.", s.installed)
                            is DesktopUpdater.State.Failed -> s.message
                            is DesktopUpdater.State.Available -> desktopText(if (DesktopUpdater.platform == ReleasePlatform.MACOS)
                                "This copy can't update itself. Download the new version from the release page."
                                else "This copy can't update itself. Download the installer from the release page.")
                            is DesktopUpdater.State.Downloading -> desktopText("Auralis downloads it in the background. Keep listening.")
                            is DesktopUpdater.State.Ready -> desktopText("Downloaded. Restart Auralis to finish updating.")
                        },
                        color = muted, fontSize = 13.5.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        UpdateChip(desktopText("Installed v%1\$s", version))
                        UpdateChip(if (isMacOs) "macOS · FOSS" else "Windows · FOSS")
                        if (release != null) {
                            UpdateChip(desktopText("New v%1\$s", release.displayVersion) +
                                (if (release.installer.size > 0) " · " + com.auralis.music.util.formatBytes(release.installer.size) else ""), accent = true)
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (val s = update) {
                        DesktopUpdater.State.Idle, DesktopUpdater.State.Checking -> FilledAction(desktopText("Checking…"), enabled = false) {}
                        is DesktopUpdater.State.UpToDate, is DesktopUpdater.State.Failed -> FilledAction(desktopText("Check again"), onClick = check)
                        is DesktopUpdater.State.Available -> {
                            FilledAction(desktopText("Open release page")) { openUrl(s.release.pageUrl) }
                            OutlinedAction(desktopText("Later")) { DesktopUpdater.hidePrompt() }
                        }
                        is DesktopUpdater.State.Downloading -> Unit
                        is DesktopUpdater.State.Ready -> {
                            FilledAction(desktopText(if (restarting) "Restarting…" else "Restart to update"), enabled = !restarting) {
                                scope.launch { message = DesktopUpdater.restartToUpdate() }
                            }
                            OutlinedAction(desktopText("Later")) { DesktopUpdater.hidePrompt() }
                        }
                    }
                }
            }
            val download = update as? DesktopUpdater.State.Downloading
            if (checking || download != null) {
                val fraction = download?.let { if (it.total > 0) (it.bytes.toFloat() / it.total).coerceIn(0f, 1f) else 0f }
                Spacer(Modifier.height(20.dp))
                WavyUpdateProgress(progress = fraction, color = primary)
                if (download != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (download.bytes == 0L) desktopText("Starting download…")
                        else desktopText("Downloading update · %1\$s%%", ((fraction ?: 0f) * 100).toInt().toString()),
                        color = muted, fontSize = 12.5.sp
                    )
                }
            }
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
    }
    if (release != null) {
        Surface(shape = RoundedCornerShape(24.dp), color = onBackground.copy(alpha = .05f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(desktopText("What's new in v%1\$s", release.displayVersion), color = onBackground, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                ReleaseNotes(release.notes, onBackground, primary)
            }
        }
    }
    key(revision) {
        SettingsGroup("Update settings") {
            SettingToggle("Automatically check for updates", "Once a day while Auralis is open", UpdatePreferences.autoCheck) {
                UpdatePreferences.autoCheck = it
            }
            SettingToggle("Enable update notifications", "A card in the corner when an update is ready", UpdatePreferences.prompts) {
                UpdatePreferences.prompts = it
            }
        }
    }
    SettingsGroup("Changelog") {
        val bundledNotes = remember(version) {
            DesktopReleaseCheck::class.java.getResourceAsStream("/desktop-release-notes-$version.txt")
                ?.bufferedReader()?.use { it.readText().trim() }.orEmpty()
        }
        val shown = latest
        val notesVersion = shown?.displayVersion ?: version
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Auralis · $notesVersion", color = onBackground, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                ReleaseNotes(shown?.notes?.ifBlank { null } ?: bundledNotes.takeIf { notesVersion == version }, onBackground, primary)
                if (shown != null) {
                    Text(desktopText("View release and installers"),
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { openUrl(shown.pageUrl) }.pointerHoverIcon(PointerIcon.Hand)
                            .padding(vertical = 4.dp),
                        color = primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** The state at a glance: a glowing circle with a spinner, a tick, a warning, or the update arrow. */
@Composable
private fun UpdateOrb(state: DesktopUpdater.State, checking: Boolean, primary: Color) {
    val icon = when (state) {
        is DesktopUpdater.State.UpToDate -> Icons.Outlined.CheckCircle
        is DesktopUpdater.State.Failed -> Icons.Outlined.ErrorOutline
        is DesktopUpdater.State.Ready -> Icons.Outlined.RestartAlt
        is DesktopUpdater.State.Downloading -> Icons.Outlined.Download
        else -> Icons.Outlined.SystemUpdateAlt
    }
    val tone = if (state is DesktopUpdater.State.Failed) Color(0xFFFF6B6B) else primary
    Box(
        Modifier.size(64.dp).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(tone.copy(alpha = .42f), tone.copy(alpha = .12f))))
            .border(1.dp, tone.copy(alpha = .35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (checking) CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Icon(icon, null, Modifier.size(30.dp), tint = Color.White)
    }
}

@Composable
private fun UpdateChip(text: String, accent: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(50), color = if (accent) primary.copy(alpha = .18f) else Color.White.copy(alpha = .07f),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (accent) primary.copy(alpha = .35f) else Color.White.copy(alpha = .08f))) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            color = if (accent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun updateTitle(state: DesktopUpdater.State): String = when (state) {
    DesktopUpdater.State.Idle, DesktopUpdater.State.Checking -> desktopText("Checking for update…")
    is DesktopUpdater.State.UpToDate -> desktopText("Auralis is up to date")
    is DesktopUpdater.State.Failed -> desktopText("Couldn't check for updates")
    is DesktopUpdater.State.Available -> desktopText("Update available")
    is DesktopUpdater.State.Downloading -> desktopText("Downloading update…")
    is DesktopUpdater.State.Ready -> desktopText("Update ready to install")
}

@Composable
private fun FilledAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.widthIn(min = 170.dp).height(44.dp).pointerHoverIcon(PointerIcon.Hand)) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun OutlinedAction(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(28.dp),
        modifier = Modifier.widthIn(min = 170.dp).height(44.dp).pointerHoverIcon(PointerIcon.Hand)) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The phone's wavy bar: a moving sine wave up to [progress], a flat track after a small gap,
 * and a dot at the end. With [progress] null it sweeps (indeterminate).
 */
@Composable
private fun WavyUpdateProgress(progress: Float?, color: Color) {
    val transition = rememberInfiniteTransition(label = "updateWave")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "sweep")
    // A spring keeps its velocity when retargeted, so frequent progress updates don't lag.
    val shown by animateFloatAsState(progress ?: 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "progress")
    val track = color.copy(alpha = .24f)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val stroke = 4.dp.toPx()
        val gap = 6.dp.toPx()
        val start = stroke / 2f
        val end = size.width - stroke / 2f
        val width = end - start
        val (from, to) = if (progress == null) {
            val head = start + width * sweep * 1.3f
            val tail = start + width * (sweep * 1.3f - .3f).coerceAtLeast(0f)
            tail.coerceAtMost(end) to head.coerceAtMost(end)
        } else start to (start + width * shown)
        if (to - from > 1f) drawWave(from, to, color, stroke, phase)
        if (from - gap > start) drawLine(track, Offset(start, center.y), Offset(from - gap, center.y), stroke, StrokeCap.Round)
        if (end - (to + gap) > 1f) drawLine(track, Offset(to + gap, center.y), Offset(end, center.y), stroke, StrokeCap.Round)
        drawCircle(color, radius = stroke * .7f, center = Offset(end, center.y))
    }
}

private fun DrawScope.drawWave(from: Float, to: Float, color: Color, stroke: Float, phase: Float) {
    val wavelength = 26.dp.toPx()
    val amplitude = 3.dp.toPx()
    val path = Path()
    var x = from
    path.moveTo(x, center.y + amplitude * sin(((x / wavelength) - phase) * 2f * PI.toFloat()))
    while (x < to) {
        x = (x + 2f).coerceAtMost(to)
        path.lineTo(x, center.y + amplitude * sin(((x / wavelength) - phase) * 2f * PI.toFloat()))
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Release notes, lightly rendered like the phone: headings, bullets, **bold**, `code` and [links](url). */
@Composable
private fun ReleaseNotes(markdown: String?, textColor: Color, accent: Color) {
    val body = markdown?.trim().orEmpty()
    if (body.isEmpty()) {
        Text(desktopText("No release notes for this version."), color = textColor.copy(alpha = .66f), fontSize = 14.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        body.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { raw ->
            val line = raw.trimStart()
            when {
                line.startsWith("#") -> Text(inline(line.trimStart('#').trim(), accent), color = textColor, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") || line.startsWith("• ") -> Row {
                    Text("•  ", color = textColor, fontSize = 14.sp, lineHeight = 21.sp)
                    Text(inline(line.drop(2).trim(), accent), color = textColor, fontSize = 14.sp, lineHeight = 21.sp)
                }
                else -> Text(inline(line, accent), color = textColor, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

private val INLINE_REGEX = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)""")

private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    INLINE_REGEX.findAll(text).forEach { m ->
        append(text.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(color = accent)) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) { append(m.groupValues[3]) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

// ─────────────────────────────── Brand icons ───────────────────────────────

private fun brandIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.White))
        .build()

/** The phone's ic_github drawable. */
private val GitHubIcon: ImageVector by lazy {
    brandIcon("GitHub", "M12,2C6.477,2 2,6.484 2,12.017c0,4.425 2.865,8.18 6.839,9.504 0.5,0.092 0.682,-0.217 0.682,-0.483 0,-0.237 -0.008,-0.868 -0.013,-1.703 -2.782,0.605 -3.369,-1.343 -3.369,-1.343 -0.454,-1.158 -1.11,-1.466 -1.11,-1.466 -0.908,-0.62 0.069,-0.608 0.069,-0.608 1.003,0.07 1.53,1.032 1.53,1.032 0.892,1.53 2.341,1.088 2.91,0.832 0.092,-0.647 0.35,-1.088 0.636,-1.338 -2.22,-0.253 -4.555,-1.113 -4.555,-4.951 0,-1.093 0.39,-1.988 1.029,-2.688 -0.103,-0.253 -0.446,-1.272 0.098,-2.65 0,0 0.84,-0.27 2.75,1.026A9.564,9.564 0,0 1,12 6.844c0.85,0.004 1.705,0.115 2.504,0.337 1.909,-1.296 2.747,-1.027 2.747,-1.027 0.546,1.379 0.202,2.398 0.1,2.651 0.64,0.7 1.028,1.595 1.028,2.688 0,3.848 -2.339,4.695 -4.566,4.943 0.359,0.309 0.678,0.92 0.678,1.855 0,1.338 -0.012,2.419 -0.012,2.747 0,0.268 0.18,0.58 0.688,0.482A10.019,10.019 0,0 0,22 12.017C22,6.484 17.522,2 12,2z")
}

/** The phone's ic_discord drawable. */
private val DiscordIcon: ImageVector by lazy {
    brandIcon("Discord", "M20.317 4.37a19.791 19.791 0 0 0-4.885-1.515.074.074 0 0 0-.079.037c-.21.375-.444.864-.608 1.25a18.27 18.27 0 0 0-5.487 0 12.64 12.64 0 0 0-.617-1.25.077.077 0 0 0-.079-.037A19.736 19.736 0 0 0 3.677 4.37a.07.07 0 0 0-.032.027C.533 9.046-.32 13.58.099 18.057a.082.082 0 0 0 .031.057 19.9 19.9 0 0 0 5.993 3.03.078.078 0 0 0 .084-.028c.462-.63.874-1.295 1.226-1.994.021-.041.001-.09-.041-.106a13.107 13.107 0 0 1-1.872-.892.077.077 0 0 1-.008-.128 10.2 10.2 0 0 0 .372-.292.074.074 0 0 1 .077-.01c3.929 1.793 8.18 1.793 12.061 0a.074.074 0 0 1 .078.01c.12.098.246.198.373.292a.077.077 0 0 1-.006.127 12.299 12.299 0 0 1-1.873.894.077.077 0 0 0-.041.107c.36.698.772 1.362 1.225 1.993a.076.076 0 0 0 .084.028 19.839 19.839 0 0 0 6.002-3.03.077.077 0 0 0 .032-.054c.5-5.177-.838-9.674-3.549-13.66a.061.061 0 0 0-.031-.028zM8.02 15.33c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.956-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.956 2.418-2.157 2.418zm7.975 0c-1.183 0-2.157-1.085-2.157-2.419 0-1.333.955-2.419 2.157-2.419 1.21 0 2.176 1.096 2.157 2.42 0 1.333-.946 2.418-2.157 2.418z")
}
