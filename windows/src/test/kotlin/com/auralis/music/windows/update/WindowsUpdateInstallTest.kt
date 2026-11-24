package com.auralis.music.windows.update

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WindowsUpdateInstallTest {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val install = File("C:/Users/Listener's PC/AppData/Local/Auralis")
    private val updates = File(install, "updates")

    private fun plan(
        install: List<String> = WindowsUpdateInstall.msiexecCommand(File(updates, "Auralis-1.2.0.msi"), File(updates, "install-1.2.0.log"), "C:\\Windows"),
        relaunch: List<String> = listOf(File(this.install, "Auralis.exe").path),
        directory: File = this.install, log: File = File("C:/Users/Listener's PC/AppData/Roaming/Auralis/auralis.log"),
        result: File = File(updates, WindowsUpdateInstall.RESULT), pids: List<Long> = listOf(42, 41),
    ) = WindowsUpdateInstall.HelperPlan(pids, directory, install, relaunch, log, result, "Auralis-1.2.0.msi")

    @Test fun msiexecRunsPassiveWithoutRestartingAndLogs() {
        val command = WindowsUpdateInstall.msiexecCommand(File("D:/u/Auralis-1.2.0.msi"), File("D:/u/install-1.2.0.log"), "C:\\Windows")
        assertEquals(listOf(File("C:\\Windows", "System32\\msiexec.exe").path, "/i", File("D:/u/Auralis-1.2.0.msi").absolutePath,
            "/passive", "/norestart", "/l*v", File("D:/u/install-1.2.0.log").absolutePath), command)
        assertEquals(File("C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe").path, WindowsUpdateInstall.powershell("C:\\Windows"))
        if (isWindows) assertEquals("C:\\Windows\\System32\\msiexec.exe", command.first())
    }

    @Test fun theHelperCommandCarriesTheScriptEncodedAndPathsSafelyQuoted() {
        val command = WindowsUpdateInstall.helperCommand(plan(), "powershell.exe")
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand"), command.dropLast(1))
        assertTrue(command.last().matches(Regex("[A-Za-z0-9+/=]+")), "No quoting is needed on the command line")
        val script = String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)
        assertEquals(WindowsUpdateInstall.helperScript(plan()), script)
        // The apostrophe in "Listener's PC" is doubled inside PowerShell's single quotes.
        assertTrue(script.contains("'${File(install, "Auralis.exe").path.replace("'", "''")}'"), script)
        assertTrue(script.contains("@(42,41)"))
        assertTrue(script.contains("/i \"${File(updates, "Auralis-1.2.0.msi").absolutePath.replace("'", "''")}\" /passive /norestart"), script)
        assertTrue(script.contains("msiexec exited with"))
    }

    @Test fun powershellLiteralsDoubleEveryKindOfSingleQuote() {
        assertEquals("'C:\\a''b'", WindowsUpdateInstall.psQuote("C:\\a'b"))
        assertEquals("'x\u2019\u2019y\u2018\u2018z'", WindowsUpdateInstall.psQuote("x\u2019y\u2018z"))
        assertEquals("'\$env:TEMP `n'", WindowsUpdateInstall.psQuote("\$env:TEMP `n"), "Nothing expands inside single quotes")
    }

    @Test fun argumentsAreQuotedTheWayWindowsReadsThemBack() {
        assertEquals("/passive", WindowsUpdateInstall.windowsArgument("/passive"))
        assertEquals("\"C:\\Program Files\\a.msi\"", WindowsUpdateInstall.windowsArgument("C:\\Program Files\\a.msi"))
        assertEquals("\"C:\\dir with space\\\\\"", WindowsUpdateInstall.windowsArgument("C:\\dir with space\\"))
        assertEquals("\"say \\\"hi\\\"\"", WindowsUpdateInstall.windowsArgument("say \"hi\""))
        assertEquals("\"\"", WindowsUpdateInstall.windowsArgument(""))
    }

    @Test fun onlyThePerUserMsiInstallUpdatesItself() {
        val local = kotlin.io.path.createTempDirectory("auralis-local").toFile()
        try {
            val exe = File(local, "Auralis/Auralis.exe").apply { parentFile.mkdirs(); writeText("") }
            val portable = File(local, "Portable/Auralis/Auralis.exe").apply { parentFile.mkdirs(); writeText("") }
            assertEquals(exe.canonicalFile, WindowsUpdateInstall.installedExecutable(exe.path, local.path)?.canonicalFile)
            assertNull(WindowsUpdateInstall.installedExecutable(portable.path, local.path))
            assertNull(WindowsUpdateInstall.installedExecutable(null, local.path), "A development run")
            assertNull(WindowsUpdateInstall.installedExecutable(File(local, "missing/Auralis.exe").path, local.path))
            // The MSI's uninstall empties the install folder during an upgrade: downloads live beside it.
            val updates = WindowsUpdateInstall.updatesDirectory(local.path)
            assertEquals(File(local, "Auralis Updates"), updates)
            assertFalse(updates.canonicalPath.startsWith(exe.parentFile.canonicalPath + File.separator))
        } finally { local.deleteRecursively() }
    }

    @Test fun theNextStartReportsWhatTheInstallDid() {
        val dir = kotlin.io.path.createTempDirectory("auralis-install-record").toFile()
        fun record(code: Int?, started: Long = 0) {
            WindowsUpdateInstall.writeRecord(dir, "1.2.0", "1.1.0", File(dir, "Auralis-1.2.0.msi"))
            val json = org.json.JSONObject(File(dir, WindowsUpdateInstall.RECORD).readText()).put("started", started)
            File(dir, WindowsUpdateInstall.RECORD).writeText(json.toString())
            if (code != null) File(dir, WindowsUpdateInstall.RESULT).writeText("$code")
        }
        try {
            assertNull(WindowsUpdateInstall.reconcile(dir, "1.1.0"))
            record(0)
            assertEquals(WindowsUpdateInstall.Outcome("1.2.0", true, "Auralis was updated to 1.2.0."), WindowsUpdateInstall.reconcile(dir, "1.2.0"))
            assertFalse(File(dir, WindowsUpdateInstall.RECORD).exists() || File(dir, WindowsUpdateInstall.RESULT).exists())
            assertNull(WindowsUpdateInstall.reconcile(dir, "1.2.0"), "Reported once")
            record(1603)
            val failed = assertNotNull(WindowsUpdateInstall.reconcile(dir, "1.1.0"))
            assertFalse(failed.succeeded)
            assertTrue(failed.message.contains("1603") && failed.message.contains("1.1.0 is still installed"), failed.message)
            record(1618)
            assertTrue(WindowsUpdateInstall.reconcile(dir, "1.1.0")!!.message.contains("Another installation"))
            record(3010)
            assertEquals("Restart Windows to finish updating to 1.2.0.", WindowsUpdateInstall.reconcile(dir, "1.1.0")!!.message)
            record(0)
            assertFalse(WindowsUpdateInstall.reconcile(dir, "1.1.0")!!.succeeded, "Success with the old version still running is not success")
            // The helper hasn't written a result yet and the install started a minute ago: wait.
            record(null, started = 1_000_000)
            assertNull(WindowsUpdateInstall.reconcile(dir, "1.1.0", now = 1_060_000))
            assertTrue(File(dir, WindowsUpdateInstall.RECORD).exists())
            assertEquals("The update didn't finish. Auralis 1.1.0 is still installed. Details: install-1.2.0.log in %LOCALAPPDATA%\\Auralis Updates.",
                WindowsUpdateInstall.reconcile(dir, "1.1.0", now = 1_000_000 + 60 * 60_000)!!.message)
        } finally { dir.deleteRecursively() }
    }

    @Test fun theHelperWaitsForAuralisThenLogsTheInstallerCodeAndReopens() {
        if (!isWindows) return
        val dir = kotlin.io.path.createTempDirectory("auralis helper's test").toFile()
        val app = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Milliseconds 1500")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val log = File(dir, "auralis.log").apply { writeText("09:00:00.000 I/AuralisWindows: earlier line\r\n") }
            val order = File(dir, "order.txt")
            val reopened = File(dir, "reopened.txt")
            fun powershell(script: String) = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-EncodedCommand", Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE)))
            val helper = WindowsUpdateInstall.HelperPlan(
                waitForProcesses = listOf(app.pid()),
                installDirectory = File(dir, "install"),
                // Stand-ins for msiexec and Auralis.exe: the installer records whether Auralis was still running.
                install = powershell("if (Get-Process -Id ${app.pid()} -ErrorAction SilentlyContinue) { \$state = 'alive' } else { \$state = 'gone' }; " +
                    "Set-Content -LiteralPath ${WindowsUpdateInstall.psQuote(order.path)} -Value \$state; exit 1603"),
                relaunch = powershell("Set-Content -LiteralPath ${WindowsUpdateInstall.psQuote(reopened.path)} -Value 'reopened'"),
                appLog = log, resultFile = File(dir, WindowsUpdateInstall.RESULT), label = "Auralis-1.2.0.msi",
            )
            val process = ProcessBuilder(WindowsUpdateInstall.helperCommand(helper))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process.outputStream.close()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "The helper finishes")
            assertEquals("gone", order.readText().trim(), "The installer ran only after Auralis exited")
            assertEquals("1603", File(dir, WindowsUpdateInstall.RESULT).readText().trim())
            val lines = log.readLines()
            assertEquals("09:00:00.000 I/AuralisWindows: earlier line", lines.first())
            assertTrue(lines.last().matches(Regex("\\d\\d:\\d\\d:\\d\\d\\.\\d{3} I/AuralisUpdate: msiexec exited with 1603 for Auralis-1.2.0.msi")), lines.last())
            val deadline = System.currentTimeMillis() + 10_000
            while (!reopened.isFile && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("reopened", reopened.readText().trim())
        } finally {
            app.destroyForcibly()
            dir.deleteRecursively()
        }
    }

    @Test fun theLauncherProcessIsAwaitedOnlyWhenItIsAuralis() {
        val current = ProcessHandle.current()
        assertEquals(listOf(current.pid()), WindowsUpdateInstall.processesToAwait(File("Z:/nowhere/Auralis.exe"), current))
        val parentCommand = current.parent().flatMap { it.info().command() }.orElse(null) ?: return
        assertEquals(listOf(current.pid(), current.parent().get().pid()), WindowsUpdateInstall.processesToAwait(File(parentCommand), current))
    }
}
