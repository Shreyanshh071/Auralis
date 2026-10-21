package com.auralis.music.windows.media

import com.auralis.music.domain.model.Track
import com.auralis.music.util.Log
import com.auralis.music.util.desktopAppDataDir
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
import kotlinx.coroutines.*
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Treat owned popups as part of Auralis, rather than as another foreground application. */
internal fun isApplicationInBackground(window: Window?): Boolean {
    if (window == null || !window.isVisible) return true
    var active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    while (active != null) {
        if (active === window) return false
        active = active.owner
    }
    return true
}

/** Windows' notification center, independent of the optional system tray icon. */
internal object WindowsSongNotifications {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    fun show(track: Track) {
        scope.launch {
            if (!com.auralis.music.windows.settings.DesktopSystemPreferences.store.settings.value.songNotifications) return@launch
            runCatching { WindowsToast.send(track.title, track.artist) }
                .onFailure { Log.w("AuralisNotifications", "Notification failed: ${it.message}") }
        }
    }
    suspend fun test() = withContext(Dispatchers.IO) {
        WindowsToast.send("Auralis", "Song notifications", tag = "notification-test")
    }
}

/** WinRT is supplied by Windows; no web service, account, or additional package is needed. */
internal object WindowsToast {
    const val APP_ID = "Auralis.Desktop"
    fun send(title: String, body: String, appId: String = APP_ID, tag: String = "now-playing", verifyAndRemove: Boolean = false) {
        require(appId.matches(Regex("[A-Za-z0-9.-]+")))
        require(tag.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val key = "Software\\Classes\\AppUserModelId\\$appId"
        Advapi32Util.registryCreateKey(HKEY_CURRENT_USER, key)
        Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, key, "DisplayName", "Auralis")
        Advapi32Util.registrySetIntValue(HKEY_CURRENT_USER, key, "ShowInSettings", 1)
        val icon = File(desktopAppDataDir(), "notification-icon.png")
        if (!icon.exists()) javaClass.classLoader.getResourceAsStream("auralis_logo.png")?.use { input ->
            icon.parentFile.mkdirs()
            icon.outputStream().use { input.copyTo(it) }
        }
        if (icon.isFile) Advapi32Util.registrySetStringValue(HKEY_CURRENT_USER, key, "IconUri", icon.absolutePath)
        val payload = Base64.getEncoder().encodeToString(xml(title, body).toByteArray(Charsets.UTF_8))
        // Only validated identifiers and base64 enter executable source. Song metadata is XML data.
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] > ${'$'}null
            [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType=WindowsRuntime] > ${'$'}null
            [Windows.UI.Notifications.ToastNotifier, Windows.UI.Notifications, ContentType=WindowsRuntime] > ${'$'}null
            [Windows.UI.Notifications.NotificationSetting, Windows.UI.Notifications, ContentType=WindowsRuntime] > ${'$'}null
            [Windows.UI.Notifications.ToastNotificationHistory, Windows.UI.Notifications, ContentType=WindowsRuntime] > ${'$'}null
            [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType=WindowsRuntime] > ${'$'}null
            ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$appId')
            if (${'$'}null -ne ${'$'}notifier.Setting -and ${'$'}notifier.Setting.ToString() -ne 'Enabled') { throw "Windows notifications: ${'$'}(${'$'}notifier.Setting)" }
            ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}xml.LoadXml([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$payload')))
            ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
            ${'$'}toast.Tag = '$tag'
            ${'$'}toast.Group = 'playback'
            ${'$'}toast.ExpirationTime = [DateTimeOffset]::Now.AddMinutes(2)
            ${'$'}notifier.Show(${'$'}toast)
            ${if (verifyAndRemove) """
                Start-Sleep -Milliseconds 700
                ${'$'}history = [Windows.UI.Notifications.ToastNotificationManager]::History
                if (-not (@(${'$'}history.GetHistory('$appId')) | Where-Object Tag -eq '$tag')) { throw 'Notification was not delivered to Windows notification history' }
                ${'$'}history.Remove('$tag', 'playback', '$appId')
            """ else ""}
            'DELIVERED'
        """.trimIndent()
        val executable = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/WindowsPowerShell/v1.0/powershell.exe")
        val guardedScript = "try {\n$script\n} catch { [Console]::Error.WriteLine(${'$'}_.Exception.Message + ' (line ' + ${'$'}_.InvocationInfo.ScriptLineNumber + ')'); exit 1 }"
        val encoded = Base64.getEncoder().encodeToString(guardedScript.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder(executable.absolutePath, "-NoLogo", "-NoProfile", "-NonInteractive", "-OutputFormat", "Text", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Windows notification service timed out.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0 && "DELIVERED" in output) {
            // Avoid exposing PowerShell's encoded source or complete metadata in error messages.
            Regex("Windows notifications: \\w+").find(output)?.value ?: output.trim().take(400).ifBlank { "Windows couldn't deliver the notification." }
        }
    }

    fun xml(title: String, body: String): String {
        fun escape(text: String) = text.filter { it == '\n' || it == '\t' || it >= ' ' }
            .take(1000).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<toast duration=\"short\"><visual><binding template=\"ToastGeneric\"><text>${escape(title)}</text><text>${escape(body)}</text></binding></visual><audio silent=\"true\"/></toast>"
    }
}
