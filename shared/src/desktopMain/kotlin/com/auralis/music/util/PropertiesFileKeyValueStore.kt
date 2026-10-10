package com.auralis.music.util

import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

/**
 * [KeyValueStore] in a properties file (the desktop counterpart of SharedPreferences). Reads come
 * from memory; writes go to disk on a background thread, like SharedPreferences.apply().
 */
class PropertiesFileKeyValueStore(private val file: File) : KeyValueStore {
    private val values = Properties()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-writer").apply { isDaemon = true } }

    init {
        if (file.exists()) runCatching { file.inputStream().use { values.load(it) } }
    }

    override fun getString(key: String, default: String): String? = synchronized(values) { values.getProperty(key, default) }

    override fun putStrings(values: Map<String, String>) {
        synchronized(this.values) { values.forEach { (k, v) -> this.values.setProperty(k, v) } }
        save()
    }

    override fun clear() {
        synchronized(values) { values.clear() }
        save()
    }

    private fun save() {
        val snapshot = synchronized(values) { Properties().also { it.putAll(values) } }
        writer.execute {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { snapshot.store(it, null) }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }
}

/** Where the desktop app keeps its files: %APPDATA%\Auralis on Windows, the usual places elsewhere. */
fun desktopAppDataDir(): File {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home")
    return when {
        os.contains("win") -> File(System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming"), "Auralis")
        os.contains("mac") -> File(home, "Library/Application Support/Auralis")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "auralis")
    }
}
