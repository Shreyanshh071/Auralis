package com.auralis.music.windows.notch

import androidx.compose.runtime.*
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.util.Log
import com.auralis.music.windows.immersive.MacLockScreenBridge
import com.auralis.music.windows.settings.DesktopPreferences
import com.auralis.music.windows.settings.DesktopPreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NotchPreferences(private val store: DesktopPreferencesStore = DesktopPreferences.store) {
    var enabled: Boolean
        get() = store.bool("macAuralisNotch", true)
        set(value) = store.set("macAuralisNotch", value)
}

internal object NotchStatus {
    private val mutable = MutableStateFlow("")
    val message = mutable.asStateFlow()
    fun set(message: String) { mutable.value = message }
}

private data class NotchRequest(
    val enabled: Boolean, val locked: Boolean, val hasTrack: Boolean,
    val local: Boolean, val state: VlcAudioPlayer.State, val resolving: Boolean
)

/** Common desktop player state stays in Kotlin; the macOS shell uses Boring Notch's SwiftUI. */
@Composable
internal fun AuralisNotch(
    playback: DesktopPlaybackController, lockScreenPreview: Boolean = false,
    onOpenPlayer: () -> Unit, onOpenSettings: () -> Unit
) {
    val bridge = remember { MacNotchBridge.create() } ?: return
    val scope = rememberCoroutineScope()
    val openPlayer by rememberUpdatedState(onOpenPlayer)
    val openSettings by rememberUpdatedState(onOpenSettings)
    val helper = remember(playback) {
        NativeNotchClient { line ->
            scope.launch {
                val command = parseNotchCommand(line, playback.queue.value.current?.id, playback.lengthMs.value) ?: return@launch
                when (command.action) {
                    NotchAction.PLAY_PAUSE -> playback.togglePlayPause()
                    NotchAction.PREVIOUS -> playback.previous()
                    NotchAction.NEXT -> playback.next()
                    NotchAction.SEEK -> playback.seekTo(command.positionMs!!)
                    NotchAction.OPEN_PLAYER -> openPlayer()
                    NotchAction.OPEN_SETTINGS -> openSettings()
                }
            }
        }
    }
    val revision by DesktopPreferences.store.changes.collectAsState()
    val enabled = remember(revision) { NotchPreferences().enabled }
    val locked by MacLockScreenBridge.isLocked.collectAsState()
    val queue by playback.queue.collectAsState()
    val state by playback.state.collectAsState()
    val resolving by playback.resolving.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val request by rememberUpdatedState(NotchRequest(enabled, locked || lockScreenPreview, queue.current != null,
        remote == null, state, resolving))

    DisposableEffect(bridge, helper) {
        onDispose { helper.close(); bridge.close(); NotchStatus.set("") }
    }
    LaunchedEffect(bridge, helper) {
        MacLockScreenBridge.startObserving()
        val session = NotchSession()
        var reportedFailure = false
        while (isActive) {
            val now = System.nanoTime() / 1_000_000
            val r = request
            val wanted = session.update(r.enabled, r.locked, r.hasTrack, r.local, r.state, r.resolving, now)
            try {
                // Start hidden before acquiring another app's visibility, so a missing/crashed
                // helper can never leave Boring Notch hidden with no replacement surface.
                val canPresent = !wanted || withContext(Dispatchers.IO) { helper.prepare() }
                val environment = bridge.update(wanted && canPresent, now)
                val snapshot = playback.notchSnapshot(environment.allowed)
                val healthy = withContext(Dispatchers.IO) {
                    if (!r.enabled) { helper.stop(); true }
                    else canPresent && helper.update(snapshot)
                }
                if (!healthy) bridge.update(false, now)
                NotchStatus.set(when {
                    environment.conflict -> "Boring Notch is keeping the notch visible. Quit it to use Auralis’s notch; its settings stay unchanged."
                    !healthy -> "The notch player is unavailable. Try restarting Auralis."
                    else -> ""
                })
                reportedFailure = false
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                withContext(Dispatchers.IO) { helper.stop() }
                NotchStatus.set("The notch is unavailable on this macOS version.")
                if (!reportedFailure) Log.w("AuralisNotch", "Notch update failed", error)
                reportedFailure = true
            }
            delay(250)
        }
    }
    LaunchedEffect(enabled, locked, lockScreenPreview, remote != null) {
        if (!enabled || locked || lockScreenPreview || remote != null) withContext(Dispatchers.IO) {
            helper.update(playback.notchSnapshot(false))
        }
    }
}

private fun DesktopPlaybackController.notchSnapshot(visible: Boolean): NotchSnapshot {
    val q = queue.value
    val track = q.current
    val position = positionMs.value
    return NotchSnapshot(
        visible = visible && track != null && connectRemote.value == null,
        trackId = track?.id.orEmpty(), title = track?.title.orEmpty(), artist = track?.artist.orEmpty(),
        artwork = track?.thumbnail.orEmpty(), playing = state.value == VlcAudioPlayer.State.PLAYING,
        buffering = resolving.value || state.value == VlcAudioPlayer.State.BUFFERING,
        positionMs = position, durationMs = lengthMs.value.takeIf { it > 0 } ?: ((track?.duration ?: 0) * 1000),
        canPrevious = q.index > 0 || position > 3_000,
        canNext = q.hasNext || (!roomActive.value && repeatMode.value == DesktopPlaybackController.RepeatMode.ALL)
    )
}
