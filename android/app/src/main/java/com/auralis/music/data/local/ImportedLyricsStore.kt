package com.auralis.music.data.local

import android.content.Context
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** User-owned lyrics live outside the disposable online lyrics cache. */
object ImportedLyricsStore {
    data class Entry(val id: String, val title: String, val artist: String, val filename: String)
    private var directory: File? = null
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    @Synchronized fun init(context: Context) {
        directory = File(context.applicationContext.filesDir, "imported-lyrics").apply { mkdirs() }
    }
    @Synchronized internal fun initDirectory(root: File) { directory = root.apply { mkdirs() } }
    private fun file(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(requireNotNull(directory) { "Lyrics storage unavailable" }, "$hash.json")
    }
    private fun decode(file: File): JSONObject? = runCatching {
        val backup = File(file.path + ".bak")
        if (backup.exists()) {
            check(!file.exists() || file.delete())
            check(backup.renameTo(file))
        }
        JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()
    private fun files(): List<File> = directory?.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
        .map { File(it.path.removeSuffix(".bak")) }.distinct()
    @Synchronized fun entries(): List<Entry> = files().mapNotNull { f -> decode(f)?.let {
            Entry(it.getString("id"), it.getString("title"), it.getString("artist"), it.getString("filename"))
        } }.sortedBy { it.title.lowercase() }
    @Synchronized fun read(id: String?): LyricsData? {
        if (id.isNullOrBlank() || directory == null) return null
        val direct = decode(file(id))
        val json = direct ?: files().asSequence().mapNotNull(::decode).firstOrNull { obj ->
                val aliases = obj.optJSONArray("aliases") ?: JSONArray()
                (0 until aliases.length()).any { aliases.optString(it) == id }
            } ?: return null
        return runCatching { TtmlParser.parse(json.getString("ttml"), LyricsProvider.LOCAL)
            .takeIf { it.lines.isNotEmpty() }?.copy(trackName = json.getString("title"), artistName = json.getString("artist")) }.getOrNull()
    }
    @Synchronized fun save(entry: Entry, xml: String, aliases: List<String> = emptyList()): LyricsData {
        require(xml.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "TTML file must be smaller than 2 MB" }
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Unsupported XML declarations" }
        val parsed = TtmlParser.parse(xml, LyricsProvider.LOCAL)
        require(parsed.lines.any { it.text.isNotBlank() }) { "This file contains no readable TTML lyrics" }
        val previousAliases = decode(file(entry.id))?.optJSONArray("aliases")
        val retainedAliases = (0 until (previousAliases?.length() ?: 0)).map { previousAliases!!.getString(it) }
        val json = JSONObject().put("id", entry.id).put("title", entry.title).put("artist", entry.artist)
            .put("filename", entry.filename).put("ttml", xml).put("aliases", JSONArray((retainedAliases + aliases).distinct()))
        val destination = file(entry.id)
        val backup = File(destination.path + ".bak")
        val temporary = File(destination.path + ".new")
        try {
            java.io.FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (destination.exists()) check(destination.renameTo(backup)) { "Could not replace saved lyrics" }
            check(temporary.renameTo(destination)) { "Could not save lyrics" }
            check(!backup.exists() || backup.delete()) { "Could not finish saving lyrics" }
        } catch (e: Exception) {
            if (backup.exists()) {
                destination.delete()
                backup.renameTo(destination)
            }
            throw e
        } finally { temporary.delete() }
        revision.value++
        return parsed.copy(trackName = entry.title, artistName = entry.artist)
    }
    @Synchronized fun remove(id: String) {
        val target = file(id)
        // Delete recovery files first so a removed import cannot be resurrected.
        for (saved in listOf(File(target.path + ".bak"), File(target.path + ".new"), target)) {
            check(!saved.exists() || saved.delete()) { "Could not remove saved lyrics" }
        }
        revision.value++
    }
}
