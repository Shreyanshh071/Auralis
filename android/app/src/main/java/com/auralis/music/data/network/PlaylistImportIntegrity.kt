package com.auralis.music.data.network

import java.io.IOException

internal class IncompletePlaylistImport(message: String) : IOException(message)

internal object PlaylistImportIntegrity {
    fun requireComplete(source: String, actual: Int, expected: Int?, hasMore: Boolean = false) {
        if (hasMore || (expected != null && expected >= 0 && actual != expected)) {
            throw IncompletePlaylistImport("$source import is incomplete: received $actual${expected?.takeIf { it >= 0 }?.let { " of $it" }.orEmpty()} entries. Nothing was replaced. Please retry.")
        }
    }
    fun requirePage(source: String, rows: Int, parsed: Int) {
        if (rows != parsed) throw IncompletePlaylistImport("$source returned $rows entries but only $parsed could be read. Nothing was replaced. Please retry or reconnect the account.")
    }
}
