package com.auralis.music.data.network

import java.io.IOException

internal class IncompletePlaylistImport(message: String) : IOException(message)

internal object PlaylistImportIntegrity {
    /**
     * [actual] counts the rows the source sent, readable or not. With [allowMissing] the source has
     * no pages left and its count may include entries it hides (deleted or region-locked songs);
     * only a shortfall too big to be those means the read was cut off.
     */
    fun requireComplete(source: String, actual: Int, expected: Int?, hasMore: Boolean = false, allowMissing: Boolean = false) {
        val mismatch = expected != null && expected >= 0 &&
            if (allowMissing) actual * 2 < expected else actual != expected
        if (hasMore || mismatch) {
            throw IncompletePlaylistImport("$source import is incomplete: received $actual${expected?.takeIf { it >= 0 }?.let { " of $it" }.orEmpty()} entries. Nothing was replaced. Please retry.")
        }
    }

    /** Local files, podcasts and removed songs have no track to import; a list with no song at all means the format changed. */
    fun requireReadable(source: String, rows: Int, parsed: Int) {
        if (rows > 0 && parsed == 0) throw IncompletePlaylistImport("$source returned $rows entries but none could be read. Nothing was replaced. Please retry or reconnect the account.")
    }
}
