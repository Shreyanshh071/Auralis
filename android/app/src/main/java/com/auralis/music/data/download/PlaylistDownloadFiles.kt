package com.auralis.music.data.download

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.auralis.music.domain.model.Track
import java.io.File
import java.io.FileInputStream
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
    fun publish(source: File, folderName: String, fileName: String): Pair<String, Long> {
        require(source.isFile && source.length() > 1024) { "Source download is missing or incomplete" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishMediaStore(source, folderName, fileName)
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = PlaylistDownloadPaths.legacyFolder(root, folderName)
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create playlist download folder" }
            val target = PlaylistDownloadPaths.legacyFile(root, folderName, fileName)
            val partial = File(dir, ".${target.name}.partial")
            partial.delete()
            source.copyTo(partial, overwrite = true)
            check(partial.length() == source.length()) { "Playlist download copy is incomplete" }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Unable to finalize playlist download" }
            target.toURI().toString() to target.length()
        }
    }

    private fun publishMediaStore(source: File, folderName: String, fileName: String): Pair<String, Long> {
        val resolver = context.contentResolver
        val relativePath = PlaylistDownloadPaths.relativePath(folderName)
        val displayName = PlaylistDownloadPaths.sanitize(fileName, "Track.m4a")
        findPublished(relativePath, displayName)?.let { (existing, bytes) ->
            if (bytes == source.length() && verify(existing.toString())) return existing.toString() to bytes
            resolver.delete(existing, null, null)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Unable to create public playlist download"
        }
        try {
            resolver.openOutputStream(uri, "w")!!.use { output -> FileInputStream(source).use { it.copyTo(output) } }
            val completed = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            check(resolver.update(uri, completed, null, null) == 1) { "Unable to finalize public playlist download" }
            check(verify(uri.toString())) { "Published playlist download could not be verified" }
            return uri.toString() to source.length()
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun findPublished(relativePath: String, displayName: String): Pair<Uri, Long>? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE)
        val normalizedPath = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
        val altPath = normalizedPath.removeSuffix("/")
        val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?) AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(normalizedPath, altPath, displayName),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)) to cursor.getLong(1)
        }
    }

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

    fun deleteFolderIfEmpty(folderName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(root, "Auralis/${PlaylistDownloadPaths.sanitize(folderName)}")
        runCatching { if (folder.isDirectory && folder.listFiles().isNullOrEmpty()) folder.delete() }
    }
}
