package com.auralis.music.windows.home

import org.json.JSONObject
import java.io.File

/** Private, selected-account cache. Never read for guests or packaged with installer defaults. */
internal class YouTubeHomeCache(private val directory: File) {
    private fun file(key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "youtube-$key.json")
    }
    fun read(key: String): JSONObject? = runCatching {
        val root = JSONObject(file(key).readText())
        root.takeIf { System.currentTimeMillis() - it.getLong("savedAt") in 0..7 * 86_400_000L }
    }.getOrNull()

    @Synchronized fun write(key: String, page: String, root: JSONObject) {
        runCatching {
            val target = file(key)
            directory.mkdirs()
            val data = read(key) ?: JSONObject()
            data.put("savedAt", System.currentTimeMillis()).put(page, root)
            val temp = File(directory, target.name + ".tmp")
            temp.writeText(data.toString())
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
