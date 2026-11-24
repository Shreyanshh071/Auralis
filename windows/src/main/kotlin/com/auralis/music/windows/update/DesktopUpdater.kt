package com.auralis.music.windows.update

import com.auralis.music.util.Log
import com.auralis.music.util.desktopAppDataDir
import com.auralis.music.windows.settings.DesktopPreferences
import com.auralis.music.windows.settings.DesktopReleaseCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Checks the desktop release channel at startup and every 24 hours, quietly. On Windows (the per-user
 * MSI install) it downloads and verifies the newer MSI and offers "Restart to update"; on macOS, and
 * on Windows copies that aren't the MSI install, it links to the release instead.
 */
internal object DesktopUpdater {
    private const val TAG = "AuralisUpdate"
    private const val STARTUP_DELAY_MS = 8_000L
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60_000L
    private const val TICK_MS = 60 * 60_000L

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val installed: String) : State
        /** Newer, but this copy can't install it itself: a link to the release. */
        data class Available(val release: DesktopRelease) : State
        data class Downloading(val release: DesktopRelease, val bytes: Long, val total: Long) : State
        data class Ready(val release: DesktopRelease, val file: File, val sha256: String) : State
        data class Failed(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val mutableLatestRelease = MutableStateFlow<DesktopRelease?>(null)
    val latestRelease: StateFlow<DesktopRelease?> = mutableLatestRelease.asStateFlow()
    private val mutableReport = MutableStateFlow<WindowsUpdateInstall.Outcome?>(null)
    /** What the previous start's "Restart to update" did. */
    val report: StateFlow<WindowsUpdateInstall.Outcome?> = mutableReport.asStateFlow()
    private val mutablePromptHidden = MutableStateFlow(false)
    /** "Later": no prompt again until the next start. */
    val promptHidden: StateFlow<Boolean> = mutablePromptHidden.asStateFlow()
    private val mutableRestarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = mutableRestarting.asStateFlow()

    /** Main.kt's orderly shutdown (saves the session, releases playback, exits). */
    @Volatile var closeApp: (() -> Unit)? = null

    val platform = ReleasePlatform.current()
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var started = false
    private val installed: String get() = DesktopReleaseCheck.version
    private val installTarget: File? by lazy { if (isWindows) WindowsUpdateInstall.installedExecutable() else null }
    private val updatesDirectory: File by lazy { WindowsUpdateInstall.updatesDirectory() }
    val canInstall: Boolean get() = installTarget != null
    /** An install the helper may still be running; the next start reads them. */
    private val pendingInstallFiles = setOf(WindowsUpdateInstall.RECORD, WindowsUpdateInstall.RESULT)

    /** Once the window is up. A development run checks only against an overridden feed. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            if (installTarget != null) runCatching { reconcileInstall() }.onFailure { Log.w(TAG, "Couldn't read the last update: ${it.message}") }
            val packaged = System.getProperty("jpackage.app-version") != null
            if (!packaged && System.getProperty(UpdateFeed.PROPERTY).isNullOrBlank()) return@launch
            delay(STARTUP_DELAY_MS)
            var lastCheck = 0L
            while (isActive) {
                if (System.currentTimeMillis() - lastCheck >= CHECK_INTERVAL_MS) {
                    lastCheck = System.currentTimeMillis()
                    check(manual = false)
                }
                delay(TICK_MS)
            }
        }
    }

    private fun reconcileInstall() {
        val outcome = WindowsUpdateInstall.reconcile(updatesDirectory, installed) ?: return
        Log.i(TAG, "Last update to ${outcome.version}: ${if (outcome.succeeded) "installed" else "not installed"}. ${outcome.message}")
        if (outcome.succeeded) UpdateDownloader(com.auralis.music.data.network.NetworkClientProvider.okHttpClient, UpdateHostPolicy(), updatesDirectory).cleanup()
        mutableReport.value = outcome
    }

    /**
     * Reads the feed and, where this copy can install, downloads the newer MSI. A background check
     * never replaces a ready download or available release with an error.
     */
    suspend fun check(manual: Boolean): State = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext state.value
        try {
            val previous = state.value
            // A background check stays quiet: the prompt and Settings keep what they show until it ends.
            if (manual) mutableState.value = State.Checking
            val next = try {
                runCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.javaClass.simpleName}: ${e.message}")
                val failed = State.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The update check failed. Try again.")
                if (!manual && (previous is State.Ready || previous is State.Available) && e !is UpdateVerificationException) previous else failed
            }
            mutableState.value = next
            next
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun runCheck(): State {
        val feed = UpdateFeed.fromSystem()
        if (feed.overridden) Log.i(TAG, "Reading the overridden update feed ${feed.url}")
        val downloader = UpdateDownloader(com.auralis.music.data.network.NetworkClientProvider.okHttpClient, feed.policy, updatesDirectory)
        val beta = DesktopPreferences.store.bool("betaUpdates")
        val latest = DesktopReleases.latest(downloader.fetchFeed(feed.url), platform, beta)
        mutableLatestRelease.value = latest
        val release = DesktopReleases.newerThan(latest, installed)
        if (release == null) {
            Log.i(TAG, "No newer ${platform.label} release than $installed (latest: ${latest?.displayVersion ?: "none"})")
            if (installTarget != null) downloader.cleanup(keep = pendingInstallFiles)
            return State.UpToDate(installed)
        }
        if (installTarget == null) {
            Log.i(TAG, "${platform.label} ${release.displayVersion} is available")
            return State.Available(release)
        }
        val sha256 = downloader.expectedSha256(release)
        downloader.cleanup(keep = downloader.filesFor(release) + pendingInstallFiles)
        downloader.verified(release, sha256)?.let { return State.Ready(release, it, sha256) }
        mutableState.value = State.Downloading(release, 0, release.installer.size)
        val context = coroutineContext
        val file = downloader.download(release, sha256, checkCancelled = { context.ensureActive() }) { bytes, total ->
            mutableState.value = State.Downloading(release, bytes, total)
        }
        Log.i(TAG, "Downloaded and verified ${file.name} (${file.length()} bytes, SHA-256 $sha256)")
        return State.Ready(release, file, sha256)
    }

    fun hidePrompt() {
        mutablePromptHidden.value = true
        mutableReport.value = null
    }

    fun dismissReport() { mutableReport.value = null }

    /**
     * Verifies the MSI again, starts the helper and closes Auralis through its usual shutdown. Returns
     * why it couldn't, or null once Auralis is closing.
     */
    suspend fun restartToUpdate(): String? = withContext(Dispatchers.IO) {
        val ready = state.value as? State.Ready ?: return@withContext "No update is ready to install."
        val exe = installTarget ?: return@withContext "This copy of Auralis can't update itself."
        val close = closeApp ?: return@withContext "Auralis isn't ready to restart yet."
        if (mutableRestarting.value) return@withContext null
        mutableRestarting.value = true
        try {
            if (!UpdateDownloader.matches(ready.file, ready.release.installer.size, ready.sha256)) {
                ready.file.delete()
                val message = "${ready.file.name} changed after it was verified, so Auralis won't install it."
                Log.w(TAG, message)
                mutableState.value = State.Failed(message)
                return@withContext message
            }
            val version = ready.release.displayVersion
            val plan = WindowsUpdateInstall.HelperPlan(
                waitForProcesses = WindowsUpdateInstall.processesToAwait(exe),
                installDirectory = exe.parentFile,
                install = WindowsUpdateInstall.msiexecCommand(ready.file, File(updatesDirectory, "install-$version.log")),
                relaunch = listOf(exe.absolutePath),
                appLog = File(desktopAppDataDir(), "auralis.log"),
                resultFile = File(updatesDirectory, WindowsUpdateInstall.RESULT),
                label = ready.file.name,
            )
            WindowsUpdateInstall.writeRecord(updatesDirectory, version, installed, ready.file)
            WindowsUpdateInstall.start(plan)
            Log.i(TAG, "Restarting to install ${ready.file.name} over $installed")
            withContext(Dispatchers.Main) { close() }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start the update", e)
            File(updatesDirectory, WindowsUpdateInstall.RECORD).delete()
            "Auralis couldn't start the update: ${e.message}"
        } finally {
            mutableRestarting.value = false
        }
    }
}
