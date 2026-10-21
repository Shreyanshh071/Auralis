package com.auralis.music.util

import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/**
 * [KeyValueStore] in a properties file (the desktop counterpart of SharedPreferences). Reads come
 * from memory; writes go to disk on a background thread, like SharedPreferences.apply().
 */
class PropertiesFileKeyValueStore(private val file: File, private val synchronous: Boolean = false) : KeyValueStore {
    private val values = Properties()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "settings-writer").apply { isDaemon = true } }

    init {
        if (file.exists()) runCatching { file.inputStream().use { values.load(it) } }
    }

    override fun getString(key: String, default: String): String? = synchronized(values) { values.getProperty(key, default) }

    @Synchronized override fun putStrings(values: Map<String, String>) {
        synchronized(this.values) { values.forEach { (k, v) -> this.values.setProperty(k, v) } }
        save()
    }

    @Synchronized override fun clear() {
        synchronized(values) { values.clear() }
        save()
    }

    private fun save() {
        val snapshot = synchronized(values) { Properties().also { it.putAll(values) } }
        val write = {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.outputStream().use { snapshot.store(it, null); if (synchronous) it.fd.sync() }
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                Unit
        }
        if (synchronous) write() else writer.execute { runCatching { write() } }
    }
}

/** Where the desktop app keeps its files: %APPDATA%\Auralis on Windows, the usual places elsewhere. */
fun desktopAppDataDir(): File {
    System.getProperty("auralis.dataDir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home")
    return when {
        os.contains("win") -> File(System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming"), "Auralis")
        os.contains("mac") -> File(home, "Library/Application Support/Auralis")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "auralis")
    }
}
