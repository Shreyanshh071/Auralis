package com.auralis.music.desktop.library

import java.io.File

/** Delete only empty download directories inside the verified root; never delete their contents. */
fun pruneEmptyDownloadFolders(folder: File, root: File, removeRoot: Boolean = false) {
    val boundary = root.canonicalFile
    var current = folder.canonicalFile
    require(current.toPath().startsWith(boundary.toPath())) { "Invalid download folder" }
    while (current.toPath().startsWith(boundary.toPath()) && (current != boundary || removeRoot)) {
        if (!current.isDirectory || current.list()?.isEmpty() != true || !current.delete()) break
        current = current.parentFile ?: break
    }
}
