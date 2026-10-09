package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.auralis.music.data.network.UpdateChecker
import com.auralis.music.data.network.UpdateInfo
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** Where the in-app update is. Lives outside the screen so a download survives leaving it. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data object Checking : UpdatePhase
    data class UpToDate(val version: String) : UpdatePhase
    data class CheckFailed(val message: String) : UpdatePhase
    data class Available(val info: UpdateInfo) : UpdatePhase
    data class Downloading(
        val info: UpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val paused: Boolean = false,
        val error: String? = null
    ) : UpdatePhase {
        val fraction: Float? get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdatePhase
}

object UpdateFlow {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val phase: StateFlow<UpdatePhase> = _phase
    private var job: Job? = null

    /** A download or finished APK is kept across visits; anything else re-checks. */
    fun open(context: Context) {
        when (_phase.value) {
            is UpdatePhase.Downloading, is UpdatePhase.ReadyToInstall, UpdatePhase.Checking -> Unit
            else -> check(context)
        }
    }

    fun check(context: Context) {
        val app = context.applicationContext
        job?.cancel()
        _phase.value = UpdatePhase.Checking
        job = scope.launch {
            val started = System.currentTimeMillis()
            val info = UpdateChecker.checkForUpdates(app)
            // Long enough to read "Checking…" instead of a flash.
            val shown = System.currentTimeMillis() - started
            if (shown < 900) kotlinx.coroutines.delay(900 - shown)
            _phase.value = when {
                info.hasUpdate -> UpdatePhase.Available(info)
                info.error != null -> UpdatePhase.CheckFailed(info.error)
                else -> UpdatePhase.UpToDate(info.currentVersion)
            }
        }
    }

    fun download(context: Context, info: UpdateInfo) {
        val url = info.downloadUrl ?: return
        val app = context.applicationContext
        val start = (_phase.value as? UpdatePhase.Downloading)?.takeIf { it.info.latestVersion == info.latestVersion }
        _phase.value = UpdatePhase.Downloading(
            info,
            downloadedBytes = start?.downloadedBytes ?: 0L,
            totalBytes = start?.totalBytes ?: (info.downloadSizeBytes ?: -1L)
        )
        job?.cancel()
        job = scope.launch {
            try {
                val apk = UpdateChecker.downloadApk(app, url, info.latestVersion, info.downloadSizeBytes) { done, total ->
                    scope.launch {
                        val cur = _phase.value as? UpdatePhase.Downloading ?: return@launch
                        if (!cur.paused) _phase.value = cur.copy(downloadedBytes = done, totalBytes = if (total > 0) total else cur.totalBytes)
                    }
                }
                _phase.value = UpdatePhase.ReadyToInstall(info, apk)
                install(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val cur = _phase.value as? UpdatePhase.Downloading
                _phase.value = (cur ?: UpdatePhase.Downloading(info, 0L, -1L))
                    .copy(paused = true, error = e.localizedMessage ?: "Download failed")
            }
        }
    }

    fun pause() {
        val cur = _phase.value as? UpdatePhase.Downloading ?: return
        job?.cancel()
        _phase.value = cur.copy(paused = true, error = null)
    }

    /** Starts the system installer, or the "Install unknown apps" page when that comes first. */
    fun install(context: Context) {
        val ready = _phase.value as? UpdatePhase.ReadyToInstall ?: return
        if (UpdateChecker.needsInstallPermission(context)) {
            UpdateChecker.openInstallPermissionSettings(context)
        } else {
            UpdateChecker.installApk(context, ready.apk)
        }
    }

    /** "Later" before a download, or leaving a finished check: nothing to keep. */
    fun dismiss() {
        when (_phase.value) {
            is UpdatePhase.Downloading, is UpdatePhase.ReadyToInstall -> Unit
            else -> { job?.cancel(); _phase.value = UpdatePhase.Idle }
        }
    }
}

/**
 * Full-screen update flow: checking (wavy bar), update available with the release notes,
 * downloading with Pause, then Install through the system installer.
 */
@Composable
fun UpdateFlowScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val phase by UpdateFlow.phase.collectAsState()
    val primary = MaterialTheme.dynamicPrimary
    val onBackground = MaterialTheme.dynamicOnBackground
    val muted = onBackground.copy(alpha = 0.66f)
    var needsPermission by remember { mutableStateOf(UpdateChecker.needsInstallPermission(context)) }

    // Coming back from "Install unknown apps": refresh the hint, and carry on to the installer.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var wasMissing = UpdateChecker.needsInstallPermission(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val missing = UpdateChecker.needsInstallPermission(context)
                needsPermission = missing
                if (wasMissing && !missing && UpdateFlow.phase.value is UpdatePhase.ReadyToInstall) {
                    UpdateFlow.install(context)
                }
                wasMissing = missing
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val close = {
        UpdateFlow.dismiss()
        onDismiss()
    }
    androidx.activity.compose.BackHandler(onBack = close)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.dynamicBackground) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = close) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back), tint = onBackground)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = primary, modifier = Modifier.size(26.dp))
            }

            val info = when (val p = phase) {
                is UpdatePhase.Available -> p.info
                is UpdatePhase.Downloading -> p.info
                is UpdatePhase.ReadyToInstall -> p.info
                else -> null
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                AnimatedContent(
                    targetState = titleFor(phase),
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "updateTitle"
                ) { title ->
                    Text(title, color = onBackground, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal)
                }

                if (info != null) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        str(R.string.update_version_label, info.latestVersion),
                        color = onBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                    info.downloadSizeBytes?.let {
                        Text(str(R.string.update_size_x, formatSize(it)), color = muted, fontSize = 12.sp)
                    }
                }

                when (val p = phase) {
                    UpdatePhase.Checking -> {
                        Spacer(Modifier.height(18.dp))
                        WavyUpdateProgress(progress = null, color = primary)
                        Spacer(Modifier.height(16.dp))
                        Text(str(R.string.update_checking_body), color = muted, fontSize = 14.sp)
                    }
                    is UpdatePhase.UpToDate -> {
                        Spacer(Modifier.height(14.dp))
                        Text(str(R.string.update_up_to_date_body, p.version), color = muted, fontSize = 15.sp)
                    }
                    is UpdatePhase.CheckFailed -> {
                        Spacer(Modifier.height(14.dp))
                        Text(p.message, color = muted, fontSize = 15.sp)
                    }
                    is UpdatePhase.Downloading -> {
                        Spacer(Modifier.height(18.dp))
                        WavyUpdateProgress(progress = p.fraction ?: 0f, color = primary, animate = !p.paused)
                        Spacer(Modifier.height(10.dp))
                        val pct = ((p.fraction ?: 0f) * 100).toInt()
                        Text(
                            text = when {
                                p.error != null -> str(R.string.download_failed_x, p.error)
                                p.paused -> str(R.string.update_paused_progress, pct)
                                p.fraction == null || p.downloadedBytes == 0L -> str(R.string.update_downloading_starting)
                                else -> str(R.string.update_downloading_progress, pct)
                            },
                            color = muted, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    is UpdatePhase.ReadyToInstall -> {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (needsPermission) str(R.string.update_permission_hint) else str(R.string.update_ready_body),
                            color = primary, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                    }
                    else -> Unit
                }

                if (info != null) {
                    Spacer(Modifier.height(20.dp))
                    ReleaseNotes(info.releaseNotes, onBackground, primary)
                }
                Spacer(Modifier.height(24.dp))
            }

            // ── Bottom actions, as in the reference: a filled primary and an outlined secondary ──
            // Clear of the mini player and dock, which stay drawn over this screen.
            Column(
                Modifier.fillMaxWidth().padding(
                    com.auralis.music.ui.components.bottomChromePadding(
                        start = 22.dp, end = 22.dp, top = 14.dp, gap = 14.dp, includeNavigationBar = false
                    )
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val p = phase) {
                    UpdatePhase.Checking -> FilledAction(str(R.string.check_for_updates_2), enabled = false) {}
                    is UpdatePhase.UpToDate, is UpdatePhase.CheckFailed -> {
                        FilledAction(str(R.string.update_check_again)) { UpdateFlow.check(context) }
                    }
                    is UpdatePhase.Available -> {
                        val apkUrl = p.info.downloadUrl
                        if (apkUrl != null && apkUrl.endsWith(".apk", ignoreCase = true)) {
                            FilledAction(str(R.string.update_download_and_install)) { UpdateFlow.download(context, p.info) }
                        } else {
                            FilledAction(str(R.string.update_open_release_page)) {
                                openUrl(context, p.info.htmlUrl ?: "https://github.com/Shreyanshh071/Auralis/releases")
                            }
                        }
                        OutlinedAction(str(R.string.later), onClick = close)
                    }
                    is UpdatePhase.Downloading -> {
                        if (p.paused) {
                            FilledAction(str(R.string.update_resume)) { UpdateFlow.download(context, p.info) }
                        } else {
                            OutlinedAction(str(R.string.pause)) { UpdateFlow.pause() }
                        }
                    }
                    is UpdatePhase.ReadyToInstall -> {
                        FilledAction(str(R.string.update_install)) { UpdateFlow.install(context) }
                        OutlinedAction(str(R.string.later), onClick = close)
                    }
                    UpdatePhase.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun titleFor(phase: UpdatePhase): String = when (phase) {
    UpdatePhase.Idle, UpdatePhase.Checking -> str(R.string.update_checking_title)
    is UpdatePhase.UpToDate -> str(R.string.update_up_to_date_title)
    is UpdatePhase.CheckFailed -> str(R.string.update_check_failed_title)
    is UpdatePhase.Available -> str(R.string.update_available_title)
    is UpdatePhase.Downloading -> if (phase.paused) str(R.string.update_paused_title) else str(R.string.update_downloading_title)
    is UpdatePhase.ReadyToInstall -> str(R.string.update_ready_title)
}

@Composable
private fun FilledAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().height(54.dp)
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
private fun OutlinedAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().height(54.dp)
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.dynamicOnBackground) }
}

/**
 * Wavy bar like the player's wavy slider: a moving sine wave up to [progress], a flat track
 * after a small gap, and a dot at the end. With [progress] null it sweeps (indeterminate).
 */
@Composable
private fun WavyUpdateProgress(progress: Float?, color: Color, animate: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "updateWave")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "updateWavePhase"
    )
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "updateWaveSweep"
    )
    // A spring keeps its velocity when retargeted; a tween restarted on every 64 KB update lagged far behind.
    val shown by animateFloatAsState(
        progress ?: 0f,
        androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "updateWaveProgress"
    )
    val track = color.copy(alpha = 0.24f)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val stroke = 4.dp.toPx()
        val gap = 6.dp.toPx()
        val start = stroke / 2f
        val end = size.width - stroke / 2f
        val width = end - start
        val (from, to) = if (progress == null) {
            // A segment that grows across the track and slides off the end.
            val head = start + width * sweep * 1.3f
            val tail = start + width * (sweep * 1.3f - 0.3f).coerceAtLeast(0f)
            tail.coerceAtMost(end) to head.coerceAtMost(end)
        } else start to (start + width * shown)
        if (to - from > 1f) drawWave(from, to, color, stroke, if (animate) phase else 0f)
        // Flat track on either side of the wave, a small gap from it, and a dot at the end.
        if (from - gap > start) drawLine(track, Offset(start, center.y), Offset(from - gap, center.y), stroke, StrokeCap.Round)
        if (end - (to + gap) > 1f) drawLine(track, Offset(to + gap, center.y), Offset(end, center.y), stroke, StrokeCap.Round)
        drawCircle(color, radius = stroke * 0.7f, center = Offset(end, center.y))
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

/** GitHub release notes, lightly rendered: headings, bullets, **bold**, `code` and [links](url). */
@Composable
private fun ReleaseNotes(markdown: String?, textColor: Color, accent: Color) {
    val body = markdown?.trim().orEmpty()
    if (body.isEmpty()) {
        Text(str(R.string.update_no_notes), color = textColor.copy(alpha = 0.66f), fontSize = 14.sp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        body.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { raw ->
            val line = raw.trimStart()
            when {
                line.startsWith("#") -> Text(
                    inline(line.trimStart('#').trim(), accent),
                    color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> Row {
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

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024L * 1024L) String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    else String.format(java.util.Locale.US, "%d KB", bytes / 1024L)

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
