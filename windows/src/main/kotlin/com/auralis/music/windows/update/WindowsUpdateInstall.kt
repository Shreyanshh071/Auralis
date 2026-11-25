package com.auralis.music.windows.update

import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Installing a downloaded MSI on Windows. A detached PowerShell helper waits for Auralis to exit,
 * runs `msiexec /i "<msi>" /passive /norestart`, logs the exit code to auralis.log, writes it next to
 * the download for the next start to report, and reopens Auralis.exe.
 *
 * The MSI is per user (%LOCALAPPDATA%\Auralis) with a fixed upgrade code, so a newer one upgrades in
 * place without an administrator prompt, and a failed one rolls back to the installed version. User
 * data in %APPDATA%\Auralis is never part of the MSI. The packaged launcher runs Auralis in a job that
 * lets children break away (JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK), so the helper outlives the app.
 */
internal object WindowsUpdateInstall {
    const val RECORD = "install.json"
    const val RESULT = "install-result.txt"
    private val succeeded = setOf(0, 3010, 1641)

    data class HelperPlan(
        /** Auralis's JVM and, when there is one, the launcher process that waits on it. */
        val waitForProcesses: List<Long>,
        /** Processes still running from here after those exit (a class-data dump) would hold files the MSI replaces. */
        val installDirectory: File,
        val install: List<String>,
        val relaunch: List<String>,
        val appLog: File,
        val resultFile: File,
        val label: String,
    )

    /**
     * The per-user MSI install this updater upgrades, or null for a development run or a copy
     * elsewhere (those only get a link to the release).
     */
    fun installedExecutable(
        appPath: String? = System.getProperty("jpackage.app-path"),
        localAppData: String? = System.getenv("LOCALAPPDATA"),
    ): File? {
        val exe = appPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile } ?: return null
        val expected = localAppData?.takeIf { it.isNotBlank() }?.let { File(it, "Auralis/Auralis.exe") } ?: return null
        return exe.takeIf { runCatching { it.canonicalFile == expected.canonicalFile }.getOrDefault(false) }
    }

    /**
     * Beside the install folder, never inside it: jpackage's MSI empties %LOCALAPPDATA%\Auralis on
     * uninstall (WixRemoveFoldersEx), and a major upgrade uninstalls the old version first. An
     * `Auralis\updates` folder lost the MSI being installed and its open log (error 1306, then 1603).
     */
    fun updatesDirectory(localAppData: String? = System.getenv("LOCALAPPDATA")): File =
        File(localAppData?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), "AppData/Local").path, "Auralis Updates")

    fun msiexecCommand(msi: File, log: File, systemRoot: String? = System.getenv("SystemRoot")): List<String> = listOf(
        File(systemRoot?.takeIf { it.isNotBlank() } ?: "C:\\Windows", "System32\\msiexec.exe").path,
        "/i", msi.absolutePath, "/passive", "/norestart", "/l*v", log.absolutePath,
    )

    fun powershell(systemRoot: String? = System.getenv("SystemRoot")): String =
        File(systemRoot?.takeIf { it.isNotBlank() } ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe").path

    /** The helper's command line. The script travels Base64-encoded, so no path needs command-line quoting. */
    fun helperCommand(plan: HelperPlan, powershell: String = powershell()): List<String> = listOf(
        powershell, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-EncodedCommand", Base64.getEncoder().encodeToString(helperScript(plan).toByteArray(Charsets.UTF_16LE)),
    )

    fun helperScript(plan: HelperPlan): String {
        val install = plan.install
        val relaunch = plan.relaunch
        require(install.isNotEmpty() && relaunch.isNotEmpty())
        val pids = plan.waitForProcesses.filter { it > 0 }.joinToString(",")
        val names = (listOf(File(relaunch.first()).nameWithoutExtension, "java", "javaw")).distinct().joinToString(",") { psQuote(it) }
        return """
            ${'$'}ErrorActionPreference = 'Continue'
            ${'$'}log = ${psQuote(plan.appLog.absolutePath)}
            ${'$'}result = ${psQuote(plan.resultFile.absolutePath)}
            ${'$'}installDir = ${psQuote(plan.installDirectory.absolutePath.trimEnd('\\') + "\\")}
            ${'$'}utf8 = New-Object System.Text.UTF8Encoding(${'$'}false)
            function Write-AuralisLog([string]${'$'}level, [string]${'$'}message) {
                try { [System.IO.File]::AppendAllText(${'$'}log, (Get-Date -Format 'HH:mm:ss.fff') + " ${'$'}level/AuralisUpdate: ${'$'}message" + [Environment]::NewLine, ${'$'}utf8) } catch {}
            }
            foreach (${'$'}id in @($pids)) {
                try {
                    ${'$'}running = [System.Diagnostics.Process]::GetProcessById(${'$'}id)
                    if (-not ${'$'}running.WaitForExit(90000)) { Write-AuralisLog 'W' "Process ${'$'}id is still running" }
                } catch {}
            }
            function Get-InstallProcesses {
                @(Get-Process -Name @($names) -ErrorAction SilentlyContinue | Where-Object {
                    try { ${'$'}_.Path -and ${'$'}_.Path.StartsWith(${'$'}installDir, [System.StringComparison]::OrdinalIgnoreCase) } catch { ${'$'}false }
                })
            }
            ${'$'}deadline = (Get-Date).AddSeconds(20)
            ${'$'}busy = Get-InstallProcesses
            while (${'$'}busy.Count -gt 0 -and (Get-Date) -lt ${'$'}deadline) { Start-Sleep -Milliseconds 500; ${'$'}busy = Get-InstallProcesses }
            foreach (${'$'}process in ${'$'}busy) {
                Write-AuralisLog 'W' ("Stopping " + ${'$'}process.ProcessName + " (" + ${'$'}process.Id + ") before updating")
                try { ${'$'}process.Kill(); [void]${'$'}process.WaitForExit(10000) } catch {}
            }
            ${'$'}code = -1
            try {
                ${'$'}start = New-Object System.Diagnostics.ProcessStartInfo
                ${'$'}start.FileName = ${psQuote(install.first())}
                ${'$'}start.Arguments = ${psQuote(install.drop(1).joinToString(" ", transform = ::windowsArgument))}
                ${'$'}start.UseShellExecute = ${'$'}false
                ${'$'}installer = [System.Diagnostics.Process]::Start(${'$'}start)
                ${'$'}installer.WaitForExit()
                ${'$'}code = ${'$'}installer.ExitCode
            } catch { Write-AuralisLog 'E' ("Couldn't start the installer: " + ${'$'}_.Exception.Message) }
            Write-AuralisLog 'I' ("msiexec exited with " + ${'$'}code + " for " + ${psQuote(plan.label)})
            try { [System.IO.File]::WriteAllText(${'$'}result, [string]${'$'}code, ${'$'}utf8) } catch {}
            try {
                ${'$'}start = New-Object System.Diagnostics.ProcessStartInfo
                ${'$'}start.FileName = ${psQuote(relaunch.first())}
                ${'$'}start.Arguments = ${psQuote(relaunch.drop(1).joinToString(" ", transform = ::windowsArgument))}
                ${'$'}start.WorkingDirectory = ${psQuote(File(relaunch.first()).absoluteFile.parent ?: plan.installDirectory.absolutePath)}
                ${'$'}start.UseShellExecute = ${'$'}true
                [void][System.Diagnostics.Process]::Start(${'$'}start)
            } catch { Write-AuralisLog 'E' ("Couldn't reopen Auralis: " + ${'$'}_.Exception.Message) }
        """.trimIndent()
    }

    /**
     * A PowerShell single-quoted literal. PowerShell also treats the typographic single quotes as
     * quote marks, so each of them is doubled as well.
     */
    fun psQuote(value: String): String =
        "'" + value.replace(Regex("['\u2018\u2019\u201A\u201B]")) { it.value + it.value } + "'"

    /** One argument as CommandLineToArgvW reads it back. */
    fun windowsArgument(argument: String): String {
        if (argument.isNotEmpty() && argument.none { it == ' ' || it == '\t' || it == '"' }) return argument
        val quoted = StringBuilder("\"")
        var backslashes = 0
        for (c in argument) {
            when (c) {
                '\\' -> backslashes++
                '"' -> { quoted.append("\\".repeat(backslashes * 2 + 1)).append('"'); backslashes = 0 }
                else -> { quoted.append("\\".repeat(backslashes)).append(c); backslashes = 0 }
            }
        }
        return quoted.append("\\".repeat(backslashes * 2)).append('"').toString()
    }

    /** This JVM and the packaged launcher that started it (it holds Auralis.exe until the JVM exits). */
    fun processesToAwait(executable: File, current: ProcessHandle = ProcessHandle.current()): List<Long> =
        listOf(current.pid()) + current.parent().filter { parent ->
            parent.info().command().map { runCatching { File(it).canonicalFile == executable.canonicalFile }.getOrDefault(false) }.orElse(false)
        }.map { listOf(it.pid()) }.orElse(emptyList())

    fun start(plan: HelperPlan) {
        val process = ProcessBuilder(helperCommand(plan))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
    }

    /** [msi] is the downloaded installer: the MSI on Windows, the disk image or zip on macOS. */
    fun writeRecord(directory: File, version: String, from: String, msi: File) {
        directory.mkdirs()
        File(directory, RESULT).delete()
        File(directory, RECORD).writeText(JSONObject().put("version", version).put("from", from)
            .put("msi", msi.name).put("started", System.currentTimeMillis()).toString())
    }

    /** What the last "Restart to update" did, on either desktop OS. */
    data class Outcome(val version: String, val succeeded: Boolean, val message: String)

    /**
     * The target version and the helper's exit code (null when it wrote none) of the last install,
     * consumed once. Null when there was none, or when the helper may still be running. Shared by
     * the Windows and macOS installers, which write the same [RECORD] and [RESULT] files.
     */
    fun takeRecord(directory: File, now: Long = System.currentTimeMillis()): Pair<String, Int?>? {
        val record = File(directory, RECORD)
        val resultFile = File(directory, RESULT)
        if (!record.isFile) { resultFile.delete(); return null }
        val json = runCatching { JSONObject(record.readText()) }.getOrNull()
        val target = json?.optString("version")?.takeIf { it.isNotBlank() }
        val code = resultFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        // Started by hand while the installer runs: let the instance the helper reopens report it.
        if (code == null && json != null && now - json.optLong("started") in 0 until 15 * 60_000L) return null
        record.delete(); resultFile.delete()
        return target?.let { it to code }
    }

    /**
     * What the last "Restart to update" did, read once at the next start. Null when there was none
     * (or the installer may still be running).
     */
    fun reconcile(directory: File, installed: String, now: Long = System.currentTimeMillis()): Outcome? {
        val (target, code) = takeRecord(directory, now) ?: return null
        val installedVersion = Version.parse(installed)
        val targetVersion = Version.parse(target)
        val upgraded = installedVersion != null && targetVersion != null && installedVersion >= targetVersion
        val log = "install-$target.log"
        return when {
            upgraded && (code == null || code in succeeded) -> Outcome(target, true, "Auralis was updated to $target.")
            code == 3010 || code == 1641 -> Outcome(target, false, "Restart Windows to finish updating to $target.")
            code != null && code in succeeded -> Outcome(target, false, "The installer finished, but Auralis is still $installed. Details: $log in %LOCALAPPDATA%\\Auralis Updates.")
            else -> Outcome(target, false, describe(code) + " Auralis $installed is still installed. Details: $log in %LOCALAPPDATA%\\Auralis Updates.")
        }
    }

    fun describe(code: Int?): String = when (code) {
        null -> "The update didn't finish."
        -1 -> "Windows Installer couldn't be started."
        1602 -> "The update was cancelled."
        1603 -> "Windows Installer hit a fatal error (1603)."
        1618 -> "Another installation was running. Try again in a few minutes."
        1619, 1620 -> "Windows couldn't open the update package ($code)."
        1625 -> "A system policy blocks this installation (1625)."
        1638 -> "This version of Auralis is already installed (1638)."
        else -> "Windows Installer stopped with error $code."
    }
}
