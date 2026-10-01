package com.auralis.music.data.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.auralis.music.domain.model.Track
import java.io.File
import java.security.MessageDigest

object PlaylistDownloadPaths {
    fun sanitize(value: String, fallback: String = "Playlist"): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\.{2,}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ' ')
        return cleaned.ifBlank { fallback }.take(80)
    }

    fun stableFolder(playlistName: String): String = sanitize(playlistName)

    fun collisionFolder(playlistName: String, playlistId: String): String =
        "${sanitize(playlistName)} (${shortHash(playlistId)})"

    fun trackFileName(index: Int, track: Track): String =
        "%02d - %s.m4a".format(index + 1, sanitize(track.title, "Track"))

    fun relativePath(folderName: String): String {
        val base = Environment.DIRECTORY_DOWNLOADS ?: "Download"
        return "$base/Auralis/${sanitize(folderName)}/"
    }

    fun legacyFolder(root: File, folderName: String): File =
        File(root, "Auralis/${sanitize(folderName)}")

    fun legacyFile(root: File, folderName: String, fileName: String): File =
        File(legacyFolder(root, folderName), sanitize(fileName, "Track.m4a"))

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
}

class PlaylistDownloadFiles(private val context: Context) {
    private val managed = ManagedPlaylistDownloadStorage(context.filesDir)

    // Managed offline copies are app data, not public exports. Android removes
    // this directory on Clear storage/uninstall, just like the global audio store.
    fun publish(source: File, folderName: String, fileName: String): Pair<String, Long> =
        managed.publish(source, folderName, fileName)

    fun verify(uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") File(requireNotNull(uri.path)).let { it.isFile && it.length() > 1024 }
            else context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize > 1024 } == true
        }.getOrDefault(false)
    }

    fun delete(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") File(requireNotNull(uri.path)).delete()
            else context.contentResolver.delete(uri, null, null)
        }
    }

    fun deleteFolderIfEmpty(folderName: String) = managed.deleteFolderIfEmpty(folderName)
}

/** File-only implementation so persistence and boundary checks can be tested without Android. */
internal class ManagedPlaylistDownloadStorage(filesDir: File) {
    private val root = File(filesDir, "playlist_downloads").canonicalFile

    private fun folder(folderName: String): File =
        File(root, PlaylistDownloadPaths.sanitize(folderName)).canonicalFile.also {
            require(it.parentFile == root) { "Playlist folder escapes managed storage" }
        }

    fun publish(source: File, folderName: String, fileName: String): Pair<String, Long> {
        require(source.isFile && source.length() > 1024) { "Source download is missing or incomplete" }
        val dir = folder(folderName)
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create managed playlist folder" }
        val target = File(dir, PlaylistDownloadPaths.sanitize(fileName, "Track.m4a")).canonicalFile
        require(target.parentFile == dir) { "Playlist file escapes managed storage" }
        val partial = File.createTempFile(".download-", ".partial", dir)
        try {
            source.copyTo(partial, overwrite = true)
            check(partial.length() == source.length()) { "Playlist download copy is incomplete" }
            // Android rename is atomic and replaces the destination. Never delete
            // the old copy first: a failed copy/finalization must leave it intact.
            check(partial.renameTo(target)) { "Unable to finalize managed playlist download" }
            return target.toURI().toString() to target.length()
        } finally {
            partial.delete()
        }
    }

    fun deleteFolderIfEmpty(folderName: String) {
        val dir = folder(folderName)
        if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
    }
}
