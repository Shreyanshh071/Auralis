package com.auralis.music.windows.update

import com.auralis.music.util.desktopAppDataDir
import java.io.File

internal typealias UpdateOutcome = WindowsUpdateInstall.Outcome

/**
 * Installs a downloaded, verified release over this copy of Auralis: a detached helper waits for
 * Auralis to exit, installs, records the result for the next start and reopens Auralis. Null from
 * [current] means this copy can't update itself (a development run, a portable copy, an app run
 * from its disk image), and only links to the release.
 */
internal interface UpdateInstaller {
    val updatesDirectory: File

    /** What the previous start's "Restart to update" did, read once. */
    fun reconcile(installed: String): UpdateOutcome?

    /** Writes the install record and starts the helper. The caller then closes Auralis. */
    fun start(download: File, version: String, installed: String)

    companion object {
        fun current(platform: ReleasePlatform = ReleasePlatform.current()): UpdateInstaller? = when (platform) {
            ReleasePlatform.WINDOWS -> WindowsUpdateInstall.installedExecutable()?.let(::WindowsUpdateInstaller)
            ReleasePlatform.MACOS -> MacUpdateInstall.installedBundle()?.let(::MacUpdateInstaller)
        }
    }
}

/** The per-user MSI install: msiexec upgrades it in place (see [WindowsUpdateInstall]). */
internal class WindowsUpdateInstaller(private val executable: File) : UpdateInstaller {
    override val updatesDirectory: File by lazy { WindowsUpdateInstall.updatesDirectory() }

    override fun reconcile(installed: String): UpdateOutcome? = WindowsUpdateInstall.reconcile(updatesDirectory, installed)

    override fun start(download: File, version: String, installed: String) {
        val plan = WindowsUpdateInstall.HelperPlan(
            waitForProcesses = WindowsUpdateInstall.processesToAwait(executable),
            installDirectory = executable.parentFile,
            install = WindowsUpdateInstall.msiexecCommand(download, File(updatesDirectory, "install-$version.log")),
            relaunch = listOf(executable.absolutePath),
            appLog = File(desktopAppDataDir(), "auralis.log"),
            resultFile = File(updatesDirectory, WindowsUpdateInstall.RESULT),
            label = download.name,
        )
        WindowsUpdateInstall.writeRecord(updatesDirectory, version, installed, download)
        WindowsUpdateInstall.start(plan)
    }
}

/** An Auralis.app this user can replace: the helper swaps in the new bundle (see [MacUpdateInstall]). */
internal class MacUpdateInstaller(private val bundle: File) : UpdateInstaller {
    override val updatesDirectory: File by lazy { MacUpdateInstall.updatesDirectory() }

    override fun reconcile(installed: String): UpdateOutcome? = MacUpdateInstall.reconcile(updatesDirectory, installed)

    override fun start(download: File, version: String, installed: String) {
        val plan = MacUpdateInstall.HelperPlan(
            waitForPid = ProcessHandle.current().pid(),
            download = download,
            target = bundle,
            version = version,
            bundleId = MacUpdateInstall.BUNDLE_ID,
            workDirectory = File(updatesDirectory, "install-work"),
            appLog = File(desktopAppDataDir(), "auralis.log"),
            resultFile = File(updatesDirectory, WindowsUpdateInstall.RESULT),
            label = download.name,
        )
        WindowsUpdateInstall.writeRecord(updatesDirectory, version, installed, download)
        MacUpdateInstall.start(plan)
    }
}
