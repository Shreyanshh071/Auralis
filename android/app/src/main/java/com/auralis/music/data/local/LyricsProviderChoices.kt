package com.auralis.music.data.local

import com.auralis.music.domain.model.LyricsProvider
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.abs

/** Explicit choices survive cache clearing and are scoped to the actual recording. */
object LyricsProviderChoices {
    private var directory: File? = null
    @Synchronized fun init(root: File) { directory = root.apply { mkdirs() } }
    private fun file(trackId: String, recordingId: String): File? {
        val hash = MessageDigest.getInstance("SHA-256").digest("$trackId\u0000$recordingId".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return directory?.let { File(it, "$hash.properties") }
    }
    @Synchronized fun read(trackId: String, recordingId: String, title: String, artist: String, durationMs: Long): LyricsProvider? = runCatching {
        val file = file(trackId, recordingId) ?: return null
        val backup = File(file.path + ".bak")
        if (backup.isFile) {
            check(!file.exists() || file.delete())
            check(backup.renameTo(file))
        }
        if (!file.isFile) return null
        val data = Properties().apply { file.inputStream().use { load(it) } }
        if (data.getProperty("title") != title || data.getProperty("artist") != artist) return null
        val savedDuration = data.getProperty("duration").toLong()
        if (durationMs > 0 && savedDuration > 0 && abs(durationMs - savedDuration) > 1500L) return null
        LyricsProvider.valueOf(data.getProperty("provider"))
    }.getOrNull()
    @Synchronized fun save(trackId: String, recordingId: String, title: String, artist: String, durationMs: Long, provider: LyricsProvider) {
        val target = file(trackId, recordingId) ?: return
        val data = Properties().apply {
            setProperty("title", title); setProperty("artist", artist)
            setProperty("duration", durationMs.toString()); setProperty("provider", provider.name)
        }
        val temporary = File(target.path + ".tmp")
        java.io.FileOutputStream(temporary).use { data.store(it, null); it.fd.sync() }
        val backup = File(target.path + ".bak")
        check(!backup.exists() || backup.delete())
        check(!target.exists() || target.renameTo(backup))
        try {
            check(temporary.renameTo(target))
            backup.delete()
        } catch (e: Exception) {
            backup.renameTo(target)
            throw e
        }
    }
}
