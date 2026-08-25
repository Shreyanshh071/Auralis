package com.auralis.music.domain.model

/**
 * Synchronization precision tier for audio lyrics.
 */
enum class SyncType {
    RICHSYNC,   // Syllable-by-syllable karaoke timestamps (word level)
    LINE_SYNC,  // Standard line-by-line LRC timestamps
    PLAIN       // Static, non-synchronized text lyrics
}
