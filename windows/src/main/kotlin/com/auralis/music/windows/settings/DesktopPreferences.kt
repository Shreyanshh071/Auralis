package com.auralis.music.windows.settings

import com.auralis.music.util.KeyValueStore
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.util.desktopAppDataDir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import com.sun.jna.platform.win32.Advapi32Util.*
import androidx.compose.runtime.mutableStateOf

/** Desktop-only preferences; existing phone-compatible stores retain their values. */
internal class DesktopPreferencesStore(private val disk: KeyValueStore, private val storageKey: String = "settings") {
    private val snapshot = mutableStateOf(runCatching { JSONObject(disk.getString(storageKey, "{}")) }.getOrElse { JSONObject() })
    private val mutable = MutableStateFlow(snapshot.value)
    val changes = mutable.asStateFlow()
    fun bool(key: String, default: Boolean = false) = snapshot.value.optBoolean(key, default)
    fun number(key: String, default: Double) = snapshot.value.optDouble(key, default)
    fun text(key: String, default: String = "") = snapshot.value.optString(key, default)
    fun configuration(): JSONObject = snapshot.value
    @Synchronized fun set(key: String, value: Any) {
        val next = JSONObject(snapshot.value.toString()).put(key, value)
        disk.putStrings(mapOf(storageKey to next.toString()))
        snapshot.value = next
        mutable.value = next
    }
}
internal object DesktopPreferences {
    val store by lazy { DesktopPreferencesStore(PropertiesFileKeyValueStore(File(desktopAppDataDir(), "desktop-preferences.properties"), synchronous = true)) }
}

internal object WindowsAutostart {
    fun executable(): File = sequenceOf(
        System.getProperty("jpackage.app-path"),
        ProcessHandle.current().info().command().orElse(null),
        File(System.getenv("LOCALAPPDATA") ?: File(System.getProperty("user.home"), "AppData/Local").path, "Auralis/Auralis.exe").path
    ).filterNotNull().map(::File).firstOrNull { it.name.equals("Auralis.exe", true) && it.isFile }
        ?: error("Install Auralis before enabling start at login.")

    fun enabled() = WindowsStartupRegistration(executable()).enabled()
    fun set(enabled: Boolean) = WindowsStartupRegistration(executable()).set(enabled)
}

/** Reads Windows' registration as well as its approval, rather than remembering a UI-only flag. */
internal class WindowsStartupRegistration(
    private val executable: File,
    private val name: String = "Auralis",
    private val runKey: String = "Software\\Microsoft\\Windows\\CurrentVersion\\Run",
    private val approvalKey: String = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
) {
    private val hive = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
    private val command get() = "\"${executable.absolutePath}\" --autostart"
    fun enabled(): Boolean {
        if (!registryKeyExists(hive, runKey) || !registryValueExists(hive, runKey, name)) return false
        if (!registryGetStringValue(hive, runKey, name).equals(command, ignoreCase = true)) return false
        if (registryKeyExists(hive, approvalKey) && registryValueExists(hive, approvalKey, name)) {
            val approval = registryGetBinaryValue(hive, approvalKey, name)
            if (approval.isNotEmpty() && (approval[0].toInt() and 1) != 0) return false
        }
        return executable.isFile
    }
    fun set(enabled: Boolean) {
        if (enabled) {
            check(executable.isFile) { "Install Auralis before enabling start at login." }
            registryCreateKey(hive, runKey)
            registrySetStringValue(hive, runKey, name, command)
            // An explicit enable in Auralis also clears Windows' older disabled decision for
            // this entry only. Do not touch approval state when simply opening Settings.
            if (registryKeyExists(hive, approvalKey) && registryValueExists(hive, approvalKey, name))
                registryDeleteValue(hive, approvalKey, name)
        } else if (registryKeyExists(hive, runKey) && registryValueExists(hive, runKey, name)) {
            registryDeleteValue(hive, runKey, name)
        }
        check(enabled() == enabled) { "Windows couldn't apply start at login." }
    }
}

internal object DesktopReleaseCheck {
    const val REPOSITORY = "https://github.com/Shreyanshh071/Auralis"
    val version: String get() = System.getProperty("jpackage.app-version") ?: bundledVersion
    private val bundledVersion by lazy {
        java.util.Properties().apply {
            checkNotNull(DesktopReleaseCheck::class.java.classLoader.getResourceAsStream("windows-version.properties")).use(::load)
        }.getProperty("version")
    }
    fun check(beta: Boolean): Pair<String, String?> {
        val client = com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
        val releases = org.json.JSONArray()
        var page = 1
        do {
            val request = okhttp3.Request.Builder().url("https://api.github.com/repos/Shreyanshh071/Auralis/releases?per_page=100&page=$page")
                .header("Accept", "application/vnd.github+json").build()
            val hasNext = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Release check failed (HTTP ${response.code}). Try again." }
                val batch = org.json.JSONArray(response.body!!.string())
                for (index in 0 until batch.length()) releases.put(batch.getJSONObject(index))
                response.header("Link").orEmpty().contains("rel=\"next\"")
            }
            page++
        } while (hasNext)
        return result(releases, beta, version)
    }
    fun result(releases: org.json.JSONArray, beta: Boolean, installed: String): Pair<String, String?> {
        val release = WindowsReleases.latest(releases, beta)
            ?: return "No published Windows release found." to null
        val current = WindowsReleases.Version.parse(installed)
        return if (current != null && release.version <= current)
            com.auralis.music.ui.i18n.desktopText("You are running the latest release (%1\$s).", "Windows $installed") to null
        else com.auralis.music.ui.i18n.desktopText("Published release: %1\$s · Installed: %2\$s", "Windows ${release.displayVersion}", "Windows $installed") to release.url
    }
    fun diagnostics(): String = listOf("Auralis for Windows $version", "OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        "Architecture: ${System.getProperty("os.arch")}", "Java: ${System.getProperty("java.version")}",
        "Processors: ${Runtime.getRuntime().availableProcessors()}", "Memory limit: ${Runtime.getRuntime().maxMemory() / 1048576} MiB",
        "YouTube connected: ${com.auralis.music.data.network.YouTubeSession.isSignedIn}").joinToString("\n")
}
