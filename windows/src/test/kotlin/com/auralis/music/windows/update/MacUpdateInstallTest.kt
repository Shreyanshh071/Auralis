package com.auralis.music.windows.update

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MacUpdateInstallTest {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private fun plan(
        target: File = File("/Users/listener/Applications/Auralis.app"),
        download: File = File("/Users/listener/Library/Caches/Auralis Updates/Auralis-1.2.0.dmg"),
        work: File = File("/Users/listener/Library/Caches/Auralis Updates/install-work"),
        log: File = File("/Users/listener/Library/Application Support/Auralis/auralis.log"),
        result: File = File("/Users/listener/Library/Caches/Auralis Updates/${WindowsUpdateInstall.RESULT}"),
        pid: Long = 4242, version: String = "1.2.0",
    ) = MacUpdateInstall.HelperPlan(pid, download, target, version, MacUpdateInstall.BUNDLE_ID, work, log, result, download.name)

    @Test fun theBundleIsFoundAroundThePackagedLauncher() {
        assertEquals(File("/Applications/Auralis.app"), MacUpdateInstall.bundleOf("/Applications/Auralis.app/Contents/MacOS/Auralis"))
        assertNull(MacUpdateInstall.bundleOf("/usr/local/bin/auralis"))
        assertNull(MacUpdateInstall.bundleOf(null), "A development run")
        assertNull(MacUpdateInstall.bundleOf(""))
    }

    @Test fun copiesOnTheDiskImageOrTranslocatedOnlyLinkToTheRelease() {
        assertTrue(MacUpdateInstall.runsFromReadOnlyCopy("/Volumes/Auralis/Auralis.app/Contents/MacOS/Auralis"))
        assertTrue(MacUpdateInstall.runsFromReadOnlyCopy("/private/var/folders/x1/T/AppTranslocation/1A2B/d/Auralis.app/Contents/MacOS/Auralis"))
        assertFalse(MacUpdateInstall.runsFromReadOnlyCopy("/Applications/Auralis.app/Contents/MacOS/Auralis"))
        assertFalse(MacUpdateInstall.runsFromReadOnlyCopy(null))
    }

    @Test fun onlyABundleThisUserCanReplaceUpdatesItself() {
        val apps = kotlin.io.path.createTempDirectory("auralis-apps").toFile()
        try {
            val bundle = File(apps, "Auralis.app").apply { File(this, "Contents/MacOS").mkdirs() }
            val launcher = File(bundle, "Contents/MacOS/Auralis").apply { writeText("") }.path
            assertEquals(bundle, MacUpdateInstall.installedBundle(launcher) { true })
            assertNull(MacUpdateInstall.installedBundle(launcher) { it != apps.absoluteFile }, "A folder that needs an administrator")
            assertNull(MacUpdateInstall.installedBundle(launcher) { it != bundle }, "A bundle owned by someone else")
            assertNull(MacUpdateInstall.installedBundle(File(apps, "Missing.app/Contents/MacOS/Auralis").path) { true })
            assertNull(MacUpdateInstall.installedBundle(null) { true })
        } finally { apps.deleteRecursively() }
        assertEquals(File("/Users/listener", "Library/Caches/Auralis Updates"), MacUpdateInstall.updatesDirectory("/Users/listener"))
    }

    @Test fun shellLiteralsKeepEverythingLiteral() {
        assertEquals("'/Users/a b/Auralis.app'", MacUpdateInstall.shQuote("/Users/a b/Auralis.app"))
        assertEquals("'Listener'\\''s Mac'", MacUpdateInstall.shQuote("Listener's Mac"))
        assertEquals("'\$HOME `id` \$(id)'", MacUpdateInstall.shQuote("\$HOME `id` \$(id)"), "Nothing expands inside single quotes")
    }

    @Test fun theHelperIsDetachedAndCarriesPathsQuoted() {
        val target = File("/Users/Listener's Mac/Applications/Auralis.app")
        val command = MacUpdateInstall.helperCommand(plan(target = target))
        assertEquals(listOf("/usr/bin/nohup", "/bin/sh", "-c"), command.take(3))
        val script = command[3]
        assertEquals(MacUpdateInstall.helperScript(plan(target = target)), script)
        assertTrue(script.contains("target=${MacUpdateInstall.shQuote(target.absolutePath)}"), script)
        assertTrue(script.contains("kill -0 4242"))
        assertTrue(script.contains("hdiutil attach -nobrowse -readonly -noautoopen"))
        assertTrue(script.contains("bundleId='com.auralis.music'"))
        assertTrue(script.contains("version='1.2.0'"))
        assertTrue(script.contains("codesign --verify --deep --strict"))
        // The old bundle is moved back if the new one can't take its place.
        assertTrue(script.contains("mv \"\$old\" \"\$target\""))
        val reopen = "'/usr/bin/open' ${MacUpdateInstall.shQuote(target.absolutePath)}"
        assertTrue(script.contains(reopen), script)
        assertTrue(script.indexOf("printf '%s' \"\$code\" > \"\$result\"") < script.indexOf(reopen), "The result is written before Auralis reopens")
    }

    @Test fun theNextStartReportsWhatTheInstallDid() {
        val dir = kotlin.io.path.createTempDirectory("auralis-mac-record").toFile()
        fun record(code: Int?, started: Long = 0) {
            WindowsUpdateInstall.writeRecord(dir, "1.2.0", "1.1.0", File(dir, "Auralis-1.2.0.dmg"))
            val json = org.json.JSONObject(File(dir, WindowsUpdateInstall.RECORD).readText()).put("started", started)
            File(dir, WindowsUpdateInstall.RECORD).writeText(json.toString())
            if (code != null) File(dir, WindowsUpdateInstall.RESULT).writeText("$code")
        }
        try {
            assertNull(MacUpdateInstall.reconcile(dir, "1.1.0"))
            record(0)
            assertEquals(UpdateOutcome("1.2.0", true, "Auralis was updated to 1.2.0."), MacUpdateInstall.reconcile(dir, "1.2.0"))
            assertNull(MacUpdateInstall.reconcile(dir, "1.2.0"), "Reported once")
            record(MacUpdateInstall.SIGNATURE_FAILED)
            assertEquals("The new Auralis failed its code-signature check. Auralis 1.1.0 is still installed.", MacUpdateInstall.reconcile(dir, "1.1.0")!!.message)
            record(MacUpdateInstall.REPLACE_FAILED)
            assertTrue(MacUpdateInstall.reconcile(dir, "1.1.0")!!.message.contains("old one was put back"))
            record(0)
            assertFalse(MacUpdateInstall.reconcile(dir, "1.1.0")!!.succeeded, "Success with the old version still running is not success")
            record(null, started = 1_000_000)
            assertNull(MacUpdateInstall.reconcile(dir, "1.1.0", now = 1_060_000), "The helper may still be running")
            assertEquals("The update didn't finish. Auralis 1.1.0 is still installed.",
                MacUpdateInstall.reconcile(dir, "1.1.0", now = 1_000_000 + 60 * 60_000)!!.message)
        } finally { dir.deleteRecursively() }
    }

    /** End to end on a Mac: a zipped Auralis.app replaces the installed one only after Auralis exits. */
    @Test fun theHelperSwapsTheBundleAfterAuralisExits() {
        if (!isMac) return
        val dir = kotlin.io.path.createTempDirectory("auralis mac's helper").toFile()
        val app = ProcessBuilder("/bin/sleep", "1.5").start()
        try {
            fun bundle(at: File, version: String) {
                File(at, "Contents").mkdirs()
                File(at, "Contents/Info.plist").writeText("""<?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0"><dict>
                    <key>CFBundleIdentifier</key><string>com.auralis.music</string>
                    <key>CFBundleShortVersionString</key><string>$version</string>
                    <key>CFBundlePackageType</key><string>APPL</string>
                    </dict></plist>""".trimIndent())
                assertEquals(0, ProcessBuilder("codesign", "--force", "--sign", "-", at.path).inheritIO().start().waitFor())
            }
            val installed = File(dir, "Applications/Auralis.app").also { bundle(it, "1.1.0") }
            val staging = File(dir, "staging/Auralis.app").also { bundle(it, "1.2.0") }
            val zip = File(dir, "Auralis-1.2.0.zip")
            assertEquals(0, ProcessBuilder("ditto", "-c", "-k", "--keepParent", staging.path, zip.path).start().waitFor())
            val log = File(dir, "auralis.log").apply { writeText("09:00:00.000 I/AuralisMac: earlier line\n") }
            val reopened = File(dir, "reopened")
            // A stand-in for `open`: the fake bundle has no executable to launch.
            val plan = plan(target = installed, download = zip, work = File(dir, "work"), log = log,
                result = File(dir, WindowsUpdateInstall.RESULT), pid = app.pid())
                .copy(relaunch = listOf("/usr/bin/touch", reopened.path))
            val helper = ProcessBuilder(MacUpdateInstall.helperCommand(plan).drop(1)).start()
            assertTrue(helper.waitFor(60, TimeUnit.SECONDS))
            assertFalse(app.isAlive)
            assertEquals("0", File(dir, WindowsUpdateInstall.RESULT).readText().trim())
            assertTrue(File(installed, "Contents/Info.plist").readText().contains("<string>1.2.0</string>"))
            assertFalse(File(installed.path + ".old").exists() || File(installed.path + ".new").exists() || File(dir, "work").exists())
            assertTrue(reopened.isFile, "Auralis is reopened")
            assertTrue(log.readLines().last().matches(Regex("\\d\\d:\\d\\d:\\d\\d\\.\\d{3} I/AuralisUpdate: Install exited with 0 for Auralis-1.2.0.zip")))
        } finally {
            app.destroyForcibly()
            dir.deleteRecursively()
        }
    }
}
