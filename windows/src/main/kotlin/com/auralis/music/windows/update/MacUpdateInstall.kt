package com.auralis.music.windows.update

import java.io.File
import java.nio.file.Files

/**
 * Installing a downloaded release on macOS. A detached `/bin/sh` helper waits for Auralis to exit,
 * opens the disk image (or zip), checks that it holds Auralis at the expected version with an
 * intact signature, copies it beside the installed Auralis.app and swaps the two (putting the old
 * one back if the swap fails), records its exit code for the next start and reopens Auralis.
 *
 * Only a bundle this user can replace updates itself, so no administrator prompt is ever needed.
 * A copy run from the mounted disk image or from Gatekeeper's translocation folder only links to
 * the release. User data in ~/Library/Application Support/Auralis is never part of the bundle.
 */
internal object MacUpdateInstall {
    const val BUNDLE_ID = "com.auralis.music"

    const val OK = 0
    const val COULD_NOT_OPEN = 10
    const val NOT_AURALIS = 11
    const val WRONG_VERSION = 12
    const val SIGNATURE_FAILED = 13
    const val COPY_FAILED = 14
    const val REPLACE_FAILED = 15

    data class HelperPlan(
        val waitForPid: Long,
        /** The verified disk image or zip. */
        val download: File,
        /** The installed Auralis.app. */
        val target: File,
        val version: String,
        val bundleId: String,
        /** Scratch space for the mount point or unzipped app, removed afterwards. */
        val workDirectory: File,
        val appLog: File,
        val resultFile: File,
        val label: String,
        val relaunch: List<String> = listOf("/usr/bin/open", target.absolutePath),
    )

    /** The .app bundle around a packaged launcher (…/Auralis.app/Contents/MacOS/Auralis). */
    fun bundleOf(appPath: String?): File? {
        val path = appPath?.takeIf { it.isNotBlank() } ?: return null
        return generateSequence(File(path)) { it.parentFile }.firstOrNull { it.name.endsWith(".app", ignoreCase = true) }
    }

    /** Running from the mounted disk image, or from the read-only copy Gatekeeper translocates a quarantined app to. */
    fun runsFromReadOnlyCopy(appPath: String? = System.getProperty("jpackage.app-path")): Boolean {
        val path = bundleOf(appPath)?.path?.replace('\\', '/') ?: return false
        return path.startsWith("/Volumes/") || path.contains("/AppTranslocation/")
    }

    /**
     * The installed bundle this updater replaces, or null for a development run, a copy on the disk
     * image or translocated, or one in a folder this user can't write to.
     */
    fun installedBundle(
        appPath: String? = System.getProperty("jpackage.app-path"),
        writable: (File) -> Boolean = { Files.isWritable(it.toPath()) },
    ): File? {
        val bundle = bundleOf(appPath)?.takeIf { it.isDirectory } ?: return null
        if (runsFromReadOnlyCopy(appPath)) return null
        val parent = bundle.absoluteFile.parentFile ?: return null
        return bundle.takeIf { writable(parent) && writable(it) }
    }

    /** Caches, never inside the bundle the helper replaces. */
    fun updatesDirectory(home: String = System.getProperty("user.home")): File = File(home, "Library/Caches/Auralis Updates")

    /** A POSIX shell single-quoted literal: nothing inside expands, and each quote is closed, escaped and reopened. */
    fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** `nohup` keeps the helper running after Auralis exits. The script is one argument: no shell parses the command line. */
    fun helperCommand(plan: HelperPlan): List<String> = listOf("/usr/bin/nohup", "/bin/sh", "-c", helperScript(plan))

    fun helperScript(plan: HelperPlan): String = """
        log=${shQuote(plan.appLog.absolutePath)}
        result=${shQuote(plan.resultFile.absolutePath)}
        target=${shQuote(plan.target.absolutePath)}
        download=${shQuote(plan.download.absolutePath)}
        work=${shQuote(plan.workDirectory.absolutePath)}
        version=${shQuote(plan.version)}
        bundleId=${shQuote(plan.bundleId)}
        label=${shQuote(plan.label)}
        note() { printf '%s %s/AuralisUpdate: %s\n' "${'$'}(date +%H:%M:%S).000" "${'$'}1" "${'$'}2" >> "${'$'}log" 2>/dev/null; }
        i=0
        while kill -0 ${plan.waitForPid} 2>/dev/null && [ ${'$'}i -lt 180 ]; do sleep 0.5; i=${'$'}((i + 1)); done
        kill -0 ${plan.waitForPid} 2>/dev/null && note W "Process ${plan.waitForPid} is still running"
        code=$OK
        mount=""
        src="${'$'}work/src"
        rm -rf "${'$'}work"
        mkdir -p "${'$'}work" || code=$COULD_NOT_OPEN
        if [ ${'$'}code -eq $OK ]; then
            case "${'$'}download" in
                *.dmg|*.DMG)
                    mount="${'$'}work/mount"
                    mkdir -p "${'$'}mount"
                    if hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "${'$'}mount" "${'$'}download" >/dev/null 2>&1; then src="${'$'}mount"; else mount=""; code=$COULD_NOT_OPEN; fi
                    ;;
                *)
                    mkdir -p "${'$'}src"
                    ditto -x -k "${'$'}download" "${'$'}src" >/dev/null 2>&1 || code=$COULD_NOT_OPEN
                    ;;
            esac
        fi
        app=""
        if [ ${'$'}code -eq $OK ]; then
            for candidate in "${'$'}src"/*.app "${'$'}src"/*/*.app; do
                [ -d "${'$'}candidate" ] || continue
                id=${'$'}(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "${'$'}candidate/Contents/Info.plist" 2>/dev/null)
                if [ "${'$'}id" = "${'$'}bundleId" ]; then app="${'$'}candidate"; break; fi
            done
            [ -n "${'$'}app" ] || code=$NOT_AURALIS
        fi
        if [ ${'$'}code -eq $OK ]; then
            found=${'$'}(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "${'$'}app/Contents/Info.plist" 2>/dev/null)
            [ "${'$'}found" = "${'$'}version" ] || { note W "Expected version ${'$'}version, found ${'$'}found"; code=$WRONG_VERSION; }
        fi
        if [ ${'$'}code -eq $OK ]; then
            codesign --verify --deep --strict "${'$'}app" >/dev/null 2>&1 || code=$SIGNATURE_FAILED
        fi
        staged="${'$'}target.new"
        old="${'$'}target.old"
        if [ ${'$'}code -eq $OK ]; then
            rm -rf "${'$'}staged"
            ditto "${'$'}app" "${'$'}staged" >/dev/null 2>&1 || { rm -rf "${'$'}staged"; code=$COPY_FAILED; }
        fi
        if [ ${'$'}code -eq $OK ]; then
            rm -rf "${'$'}old"
            if mv "${'$'}target" "${'$'}old"; then
                if mv "${'$'}staged" "${'$'}target"; then
                    rm -rf "${'$'}old"
                    xattr -dr com.apple.quarantine "${'$'}target" >/dev/null 2>&1
                else
                    mv "${'$'}old" "${'$'}target"
                    rm -rf "${'$'}staged"
                    code=$REPLACE_FAILED
                fi
            else
                rm -rf "${'$'}staged"
                code=$REPLACE_FAILED
            fi
        fi
        if [ -n "${'$'}mount" ]; then
            hdiutil detach "${'$'}mount" >/dev/null 2>&1 || hdiutil detach -force "${'$'}mount" >/dev/null 2>&1
        fi
        rm -rf "${'$'}work"
        note I "Install exited with ${'$'}code for ${'$'}label"
        printf '%s' "${'$'}code" > "${'$'}result"
        ${plan.relaunch.joinToString(" ", transform = ::shQuote)} >/dev/null 2>&1 || note E "Couldn't reopen Auralis"
    """.trimIndent() + "\n"

    fun start(plan: HelperPlan) {
        val process = ProcessBuilder(helperCommand(plan))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
    }

    /** What the last "Restart to update" did, read once at the next start. */
    fun reconcile(directory: File, installed: String, now: Long = System.currentTimeMillis()): UpdateOutcome? {
        val (target, code) = WindowsUpdateInstall.takeRecord(directory, now) ?: return null
        val installedVersion = Version.parse(installed)
        val targetVersion = Version.parse(target)
        val upgraded = installedVersion != null && targetVersion != null && installedVersion >= targetVersion
        return when {
            upgraded && (code == null || code == OK) -> UpdateOutcome(target, true, "Auralis was updated to $target.")
            code == OK -> UpdateOutcome(target, false, "The update finished, but Auralis is still $installed. Details: auralis.log in ~/Library/Application Support/Auralis.")
            else -> UpdateOutcome(target, false, describe(code) + " Auralis $installed is still installed.")
        }
    }

    fun describe(code: Int?): String = when (code) {
        null -> "The update didn't finish."
        COULD_NOT_OPEN -> "The downloaded update couldn't be opened."
        NOT_AURALIS -> "The download doesn't contain Auralis."
        WRONG_VERSION -> "The download holds a different version than its release."
        SIGNATURE_FAILED -> "The new Auralis failed its code-signature check."
        COPY_FAILED -> "The new Auralis couldn't be copied next to the installed one."
        REPLACE_FAILED -> "The new Auralis couldn't replace the installed one, so the old one was put back."
        else -> "The update stopped with error $code."
    }
}
